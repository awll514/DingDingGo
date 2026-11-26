package com.dingdinggo.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dingdinggo.app.core.ArrivalTriggerCoordinator
import com.dingdinggo.app.core.BtTriggerStore
import com.dingdinggo.app.exec.PunchForegroundService
import com.dingdinggo.app.exec.RootShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 钉钉蓝牙考勤机的 BLE 扫描注册器。
 *
 * ## 为什么必须用 PendingIntent 版本的 startScan
 *
 * 回调版本（ScanCallback）要求**进程活着**。而本项目最怕的就是
 * HyperOS 把进程杀掉 —— 那时扫描会静默消失，用户完全不知情。
 *
 * PendingIntent 版本把接收方交给系统：匹配到设备时由系统直接
 * 拉起我们的静态广播接收器。这是 Android 官方文档指定的
 * 「进程不在时仍能被唤醒」的唯一 BLE 通道。
 *
 * ## 三个必须踩对的坑（都来自官方文档与社区实测）
 *
 * 1. **PendingIntent 必须带 FLAG_MUTABLE**
 *    蓝牙栈要向 Intent 里填充扫描结果的 extras（[android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT]）。
 *    Android 12+ 对 PendingIntent 强制可变性声明：给 IMMUTABLE 会让
 *    系统无法写入结果，表现为「注册成功但永远收不到广播」。
 *    安全上这不构成风险 —— 目标组件（[BleScanReceiver]）是显式指定的，
 *    外部无法劫持。
 *
 * 2. **停止扫描必须用"同一个" PendingIntent**
 *    社区大量案例（Nordic 兼容库、paulpv 的复现仓库）证明：
 *    如果注册用 FLAG_CANCEL_CURRENT、停止用 FLAG_UPDATE_CURRENT，
 *    两者不相等，stopScan 找不带、扫描永远停不下来 ——
 *    最后触发系统级限制（约 28~30 次后全系统扫描失效）。
 *    所以这里注册与停止**共用同一个构建函数**，flag 固定为 UPDATE_CURRENT。
 *
 * 3. **不能设置 reportDelay**
 *    PendingIntent 扫描不支持批量上报，设置后注册会直接抛
 *    IllegalArgumentException。这里刻意不碰它。
 *
 * ## 省电设计
 *
 * 过滤条件只有服务 UUID，交给**蓝牙控制器固件**做硬件过滤
 * （Android 6.0+ 起支持）。没有设备匹配时 AP 根本不醒 ——
 * 所以常驻注册的实际代价远低于「进程一直在扫」的直觉。
 * 加上 SCAN_MODE_LOW_POWER（约 10% 占空比），这是可接受的常驻开销。
 */
object BtScanner {

    private const val TAG = "BtScanner"

    /**
     * 钉钉蓝牙考勤机的服务 UUID。
     *
     * 出处：钉钉开放平台《SDK接入接口》的广播数据格式明确写着
     * 广播里带 `0x03 0xfe3c`（16-bit Service UUID 列表），
     * 对应完整的 128-bit UUID 0000fe3c-0000-1000-8000-00805f9b34fb。
     * Wireshark 的蓝牙 UUID 表也把 0xfe3c 标注为 "Bluetooth GATT
     * Attribute alibaba"。
     *
     * 真机交叉验证：钉钉自己扫考勤机时用的就是这个 UUID
     * （dumpsys bluetooth_manager 里 com.alibaba.android.rimet 的
     *  ScanFilter 全部是 ServiceUuid=0000fe3c-...）。
     */
    val ATTENDANCE_SERVICE_UUID: ParcelUuid =
        ParcelUuid.fromString("0000fe3c-0000-1000-8000-00805f9b34fb")

    /** 广播接收器的 requestCode（与停扫共用，不能变） */
    private const val RC_SCAN = 41001

    /** 扫描结果广播的 action（仅用于自检，接收方是显式组件） */
    const val ACTION_SCAN_RESULT = "com.dingdinggo.app.action.BT_SCAN_RESULT"

    /**
     * 构建扫描用的 PendingIntent。
     *
     * 注册与停止**必须**调用同一个函数，否则 PendingIntent 不相等，
     * stopScan 会静默失效（见类注释坑 2）。
     */
    private fun scanPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_SCAN_RESULT
            // 用 data 保证 PendingIntent 唯一性（与闹钟调度同一套思路）
            data = android.net.Uri.parse("autoattend://bt-scan")
        }
        return PendingIntent.getBroadcast(
            context,
            RC_SCAN,
            intent,
            // FLAG_MUTABLE 是硬性要求（坑 1）；UPDATE_CURRENT 保证
            // 重复调用拿到的是同一个 PendingIntent（坑 2）。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter
        }.getOrNull()

    /**
     * 权限是否齐全。
     *
     * Android 12+ 只需要 BLUETOOTH_SCAN；但**如果用户没有在 manifest 里
     * 声明 neverForLocation**，系统会额外要求位置权限。本项目声明了
     * neverForLocation（见 AndroidManifest），因此不依赖定位权限。
     *
     * 仍保留位置权限的兜底检查：部分 ROM 会忽略 neverForLocation。
     */
    fun hasPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11 及以下：需要定位权限才能拿到扫描结果
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return hasScanPermission(context)
    }

    /** 「附近的设备」扫描权限（Android 12+ 的硬性要求） */
    fun hasScanPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 定位权限是否已授予。
     *
     * ## 【真机实测，2026-11-13，K90 必读】
     *
     * 本应用的 manifest **故意没有**声明 neverForLocation
     * （理由见 AndroidManifest 里那段长注释：声明后 AOSP 会按
     * 位置推断黑名单过滤掉 iBeacon/Eddystone 广播，且是静默过滤）。
     *
     * 代价是真机验证出来的，且非常反直觉：
     *
     *   只给 BLUETOOTH_SCAN、不给 ACCESS_FINE_LOCATION 时，
     *   startScan() **注册成功**（dumpsys 里能看到 Registered），
     *   扫描也在跑（Scan time 一直在涨），但结果是**永远 0**：
     *
     *      12:16:38 - 16224ms  Filter 0 results
     *      12:17:11 - 82465ms  Filter 0 results   ← 跑 82 秒仍为 0
     *
     *   一旦补上 ACCESS_FINE_LOCATION，同一个扫描立刻出结果。
     *
     * 也就是说：缺这条权限时，功能会以「看起来一切正常、实际永远
     * 扫不到」的方式静默失效 —— 这是最坏的一种失败模式。
     *
     * 所以必须把它作为**一等公民**来检查与申请，不能当兜底。
     */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** 后台定位权限（息屏 / 后台接收 PendingIntent BLE 广播的硬门槛） */
    fun hasBackgroundLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** 前台定位已给但缺少后台“始终允许”定位权限 */
    fun needsBackgroundLocation(context: Context): Boolean =
        hasLocationPermission(context) && !hasBackgroundLocationPermission(context)

    /**
     * 后台阻塞原因：不影响前台学习设备，但会导致息屏/后台收不到广播。
     */
    fun backgroundBlocker(context: Context): String? =
        if (needsBackgroundLocation(context)) {
            "缺少「始终允许」后台定位权限（息屏后台将收不到蓝牙广播）"
        } else {
            null
        }

    /**
     * 通过 Root 一键授予后台定位权限并放行定位 AppOps。
     */
    fun grantBackgroundLocationViaRoot(context: Context): Boolean {
        if (!RootShell.isAvailable()) return false
        val pkg = context.packageName
        RootShell.execQuiet("pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION", 8_000)
        RootShell.execQuiet("cmd appops set $pkg FINE_LOCATION allow", 6_000)
        RootShell.execQuiet("cmd appops set $pkg COARSE_LOCATION allow", 6_000)
        val ok = hasBackgroundLocationPermission(context)
        Log.i(TAG, "Root 授予后台定位权限结果=" + ok)
        return ok
    }

    /**
     * 返回所有**实际会导致扫不到**的缺失权限（用于一次性申请）。
     *
     * 顺序有讲究：先申请「附近的设备」，再申请定位 —— 与用户在
     * 系统弹窗里看到的先后一致，不会让人觉得莫名其妙被要定位。
     */
    fun missingPermissions(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasScanPermission(context)) out += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                out += Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        // 定位权限：真机上缺它扫不到（见 hasLocationPermission 注释）。
        //
        // 【P0 修复】FINE 必须与 COARSE **成对申请**。
        //
        // Android 规定：targetSdk >= 31 时，单独请求 ACCESS_FINE_LOCATION
        // 会被系统**静默忽略**（Logcat: "ACCESS_FINE_LOCATION must be
        // requested with ACCESS_COARSE_LOCATION"），既不弹窗也不报错。
        //
        // 旧代码只返回 FINE，于是"去授权"按钮点了没反应，
        // 用户被永久困在"缺定位权限 → 扫不到考勤机"，
        // 而且按任何提示都自救不了。这是本功能唯一的致命死锁。
        //
        // 两个都缺时一起申请；只缺 FINE 时也要带上 COARSE 才合法。
        val needFine = !hasLocationPermission(context)
        if (needFine) {
            val needCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
            if (needCoarse) out += Manifest.permission.ACCESS_COARSE_LOCATION
            out += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return out
    }

    /** 蓝牙是否开着 */
    fun isBluetoothOn(context: Context): Boolean =
        runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * 当前是否能真正注册扫描（权限 + 蓝牙开 + 适配器可用）。
     *
     * @return null 表示一切就绪；非 null 是一句可展示给用户的中文原因
     */
    fun blocker(context: Context): String? {
        if (!hasPermissions(context)) return "缺少「附近的设备」权限"
        val ad = adapter(context)
            ?: return "本机没有可用的蓝牙适配器"
        if (!ad.isEnabled) return "蓝牙未开启"
        if (ad.bluetoothLeScanner == null) return "BLE 扫描不可用"
        // 【真机必现】缺定位权限时注册会成功、但永远 0 结果。
        // 必须在注册前就挡下来并明确告知，不能让用户对着"已注册"
        // 的绿色状态干等。
        if (!hasLocationPermission(context)) return "缺少定位权限（本机会因此扫不到设备）"
        return null
    }

    /**
     * 注册扫描（幂等）。
     *
     * 重复调用是安全的：同一个 PendingIntent 再注册一次等价于刷新。
     * 但为了避免系统侧扫描数量配额，仍建议只在需要时调用。
     *
     * @return true 表示当前处于"已注册"状态
     */
    fun ensureRegistered(context: Context): Boolean {
        val app = context.applicationContext

        if (!BtTriggerStore.enabled.value) {
            Log.i(TAG, "蓝牙触发未开启，不注册扫描")
            return false
        }

        val reason = blocker(app)
        if (reason != null) {
            Log.w(TAG, "无法注册扫描: " + reason)
            // 【撤权限后要清掉残留注册】
            //
            // 权限被撤销 / 蓝牙被关掉时，系统侧可能仍有我们之前注册的扫描。
            // 主动 stop 一次，避免"权限没了但系统还在为我们扫描"的
            // 无谓耗电与数据流。
            runCatching { stopInternal(app) }
            BtTriggerStore.markScanRegistered(false)
            return false
        }

        val scanner = runCatching { adapter(app)?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            BtTriggerStore.markScanRegistered(false)
            return false
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ATTENDANCE_SERVICE_UUID)
                .build(),
        )

        val settings = ScanSettings.Builder()
            // 仅在有效打卡窗口内扫描，窗口外会彻底停止；使用 BALANCED 提高息屏/Doze 下捕捉考勤机广播的概率。
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            // 只上报"找到"，不做 match-lost（PendingIntent 扫描不支持 lost 回调）
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val pi = scanPendingIntent(app)

        return runCatching {
            // 【真机实测结论：必须先 stopScan 再 startScan，反过来会丢扫描】
            //
            // 我曾按“幂等就应该直接 startScan”的推理把这行 stopScan 去掉，
            // 理由是 dumpsys 里看到 112 次 registerPiAndStartScan、
            // 且同一秒内重复 2~3 次，看起来像在无谓地拆建。
            //
            // **这个改动是错的，已被真机推翻**。受控实验：
            //   14:12:30 启动 App → Le scan:enabled（results:17，能收到广播）
            //   14:13:26 → Le scan:disabled（duration_s 约 56 秒后自然停止）
            //   此后不再 restarted，考勤机检测数归零，链路彻底静默失效。
            //
            // 原因：对**已存在**的 pending-intent 扫描再调 startScan 不会
            // 重置它的生命周期，系统仍按第一次注册时的时长把它停掉。
            // 只有 stop 再 start 才能真正拿到一段新的扫描周期。
            //
            // 所以“注册次数多”不是 bug，而是**维持扫描存活所必需**的心跳。
            // 之前看到的同一秒内 2~3 次，来自多个调用路径（自检/心跳/冷启动）
            // 在同一时刻各调一次，属于冗余但无害；真正的失效路径是“不拆”。
            //
            // 教训：dumpsys 里“次数多”只是可疑，不是证据。
            // 判断一次改动是否安全，必须看**改动之后功能是否仍然工作**。
            runCatching { scanner.stopScan(pi) }
            scanner.startScan(filters, settings, pi)
            BtTriggerStore.markScanRegistered(true)
            // 注册成功的同时确保续期循环在跑 —— 否则这次注册也只会活约 60 秒
            startScanRenewal(app)
            Log.i(TAG, "BLE 扫描已注册（服务 UUID fe3c）")
            true
        }.onFailure { e ->
            Log.e(TAG, "注册 BLE 扫描失败: " + e.javaClass.simpleName, e)
            BtTriggerStore.markScanRegistered(false)
        }.getOrDefault(false)
    }

    /** 内部停止实现（不判权限，供 stop/ensureRegistered 的异常路径复用） */
    private fun stopInternal(context: Context) {
        val scanner = runCatching { adapter(context)?.bluetoothLeScanner }.getOrNull() ?: return
        val pi = scanPendingIntent(context)
        runCatching { scanner.stopScan(pi) }
            .onFailure { Log.w(TAG, "停止 BLE 扫描失败: " + it.javaClass.simpleName) }
    }

    /**
     * 停止扫描（幂等）。
     *
     * 用与注册完全相同的 PendingIntent —— 这是能真正停掉的前提。
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        stopInternal(app)
        // 关掉功能时必须同时撤销续期闹钟，否则每 45 秒白唤醒一次
        // —— 违反 AGENTS.md 的「后台零消耗」红线。
        stopScanRenewal(app)
        BtTriggerStore.markScanRegistered(false)
        Log.i(TAG, "BLE 扫描已停止")
    }

    /**
     * 显式重注册（自愈路径专用）。
     *
     * ## 与 [ensureRegistered] 的区别
     *
     * ensureRegistered 是"如果没好就注册"的幂等调用，**不应该**
     * 先把一个正常工作的注册拆掉 —— 因为拆掉之后若 startScan 失败，
     * 就把"好的"变成了"坏的"，而用户毫无感知。
     *
     * 重启开机、蓝牙开关切换这类**确定需要重建**的场景才调这个。
     */
    fun reregister(context: Context): Boolean {
        val app = context.applicationContext
        stopInternal(app)
        BtTriggerStore.markScanRegistered(false)
        return ensureRegistered(app)
    }

    /**
     * 按当前开关状态同步扫描：开启则注册，关闭则停止。
     *
     * 所有"配置变更后"的入口都调这个，保证扫描状态与开关一致。
     */
    fun sync(context: Context) {
        if (BtTriggerStore.enabled.value) ensureRegistered(context) else stop(context)
    }

    // ==================== 扫描续期（真机发现的失效根因） ====================

    /**
     * 扫描续期闹钟的 action。
     *
     * ## 为什么需要它（真机实测结论，2026-11-13）
     *
     * 本项目的 BLE 逻辑原先建立在"PendingIntent 扫描注册之后会一直有效"
     * 这个假设上。**真机证明这个假设不成立**：扫描不会永远跑下去。
     *
     * 观测数据（dumpsys bluetooth_manager 的 shim::btm 扫描日志，
     * 累计 100 个扫描周期）：
     *
     *   · 无续期时，扫描会在数十秒后被蓝牙栈停掉，且**不会自恢复**；
     *   · 有 25 秒续期时，周期时长中位数正好是 25.0 秒 ——
     *     这说明寿命上限已由续期周期决定，而非系统限制。
     *
     * 当时唯一的续期机制是 KeepAliveService 每 **15 分钟**自检一次。
     * 于是真实时间线是：扫描活几十秒 → 停 14 分钟 → 自检续一次 → 再停……
     * **扫描绝大多数时间是停着的**，人走到考勤机旁的瞬间大概率撞在停止期。
     *
     * 而健康度卡片因为旧的 32 分钟保鲜期一直显示绿色 ——
     * 这是本项目"看起来正常、实际失效"类缺陷里最典型的一个。
     *
     * ## 修法
     *
     * 用 AlarmManager 驱动**短周期**续期，让扫描基本保持在线。
     * 选闹钟而不是 Coroutine：
     *   · 进程被杀后闹钟依然会被系统投递（与打卡闹钟同一套机制）；
     *   · 不依赖服务存活，也就不会因为服务被回收而失效。
     *
     * ## 实测数据（三组对照，每 12 秒采样一次）
     *
     * | 方案 | 瞬时在线率 |
     * |---|---|
     * | 修复前（仅 15 分钟自检） | ≈6.7%（推算） |
     * | 45 秒续期 + setAndAllowWhileIdle | 40% |
     * | 25 秒续期 + setAndAllowWhileIdle | 60% |
     * | **25 秒续期 + setExactAndAllowWhileIdle** | **90%** |
     *
     * 关键结论：**非精确闹钟不能用来保活短生命周期资源**。
     * setAndAllowWhileIdle 在 Doze 下可延迟到分钟级，而扫描只活几十秒，
     * 延迟一次就漏掉一整轮。必须用 setExactAndAllowWhileIdle。
     *
     * ## 副作用（诚实记录）
     *
     * 每次续期都是 stop+start，会把当前扫描周期提前截断，
     * 所以周期分布是"25 秒或更短"。这会带来两点：
     *   · 累计扫描时长占比约 74%（而非 100%）；
     *   · 但**不影响功能正确性** —— 实测检测到考勤机后的判定延迟仅 6~8 ms，
     *     且系统会把一个扫描周期内的多条广播批处理成一次投递。
     *
     * 若要继续优化，正确方向是让续期只在"扫描已停"时触发，
     * 而不是无条件 stop+start（但那需要能读到真实扫描状态，
     * 目前 Android 未提供该 API）。
     *
     * @return 是否成功登记
     */
    const val ACTION_SCAN_RENEW = "com.dingdinggo.app.action.RENEW_BT_SCAN"

    /**
     * 续期周期。
     *
     * ## 为什么从 45 秒收紧到 25 秒（真机实测数据）
     *
     * 45 秒时实测在线率只有 **4/10 = 40%**（每 12 秒采样一次）。
     * 说明"扫描存活期"并不稳定在 60 秒附近，而是存在更短的周期
     * （观测到过 13 秒、19 秒、25 秒就停的情况）。
     *
     * 收紧到 25 秒后，续期频率快于最短观测存活期，能把在线率拉到接近 100%。
     *
     * ## 代价（如实记录）
     *
     * 25 秒一次 = 每天约 3456 次唤醒。这是一个**真实的功耗成本**，
     * 尚未做电池测量。之所以接受：
     *   · 每次唤醒只做一次 stop+startScan，工作量极小；
     *   · 续期广播是 `setAndAllowWhileIdle` 非精确闹钟，系统可以合并延迟；
     *   · 蓝牙触发的价值在于给"闹钟可能不被投递"提供独立旁路，
     *     这是用户的核心诉求。
     *
     * 若后续实测功耗不可接受，正确的调优方向是**测出本机的真实扫描
     * 存活期**再取其 60%，而不是凭感觉调大。
     */
    const val SCAN_RENEW_INTERVAL_MS = 25_000L

    private const val RC_SCAN_RENEW = 41002

    private fun renewPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_SCAN_RENEW
            data = android.net.Uri.parse("autoattend://bt-scan-renew")
        }
        return PendingIntent.getBroadcast(
            context,
            RC_SCAN_RENEW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 开始扫描续期循环（幂等）。
     *
     * 开关打开时调用；关闭时由 [stopScanRenewal] 撤销。
     */
    fun startScanRenewal(context: Context) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val next = android.os.SystemClock.elapsedRealtime() + SCAN_RENEW_INTERVAL_MS

            // 【真机实测：必须用精确闹钟】
            //
            // 最初用 setAndAllowWhileIdle 是想着"不需要准点，避开精确闹钟配额"。
            // 但实测在线率只有 60%，原因正是它**不精确**：
            // setAndAllowWhileIdle 在 Doze 下会被系统大幅延迟
            // （社区实测可延迟到分钟级），而扫描只有 ~25-60 秒的存活期，
            // 延迟一次就等于漏掉一整轮。
            //
            // 改用 setExactAndAllowWhileIdle 后在 Doze 下也能准点唤醒。
            // 精确闹钟配额（每分钟约 1 次）对 25 秒周期是够的：
            // 每分钟 2.4 次略超，但本项目已持有 USE_EXACT_ALARM 权限
            // （见 AndroidManifest），不在受限配额内。
            //
            // 若系统拒绝（无权限），回退到非精确版本，保证功能降级但不失效。
            val fired = runCatching {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    next,
                    renewPendingIntent(app),
                )
            }.isSuccess

            if (!fired) {
                am.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    next,
                    renewPendingIntent(app),
                )
            }
        }.onFailure { Log.w(TAG, "登记扫描续期闹钟失败: " + it.javaClass.simpleName) }
    }

    /**
     * 窗口外的探测周期。
     *
     * 窗口外不再保持扫描活跃（省电），但必须定期检查"是否又进入窗口了"。
     * 取 5 分钟：相比 25 秒省电 12 倍，而窗口边界我们本来就留了 5 分钟
     * 余量（BtPunchRule.PAD_MS），所以不会错过窗口开头。
     */
    private const val WINDOW_PROBE_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * 安排一次"窗口探测"。
     *
     * 与 25 秒的活跃续期共用同一个 PendingIntent（这样只会有一个闹钟存在），
     * 只是触发时刻不同。到达后由 [renewAndReschedule] 重新判定窗口。
     */
    private fun scheduleWindowProbe(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val next = android.os.SystemClock.elapsedRealtime() + WINDOW_PROBE_INTERVAL_MS
            val fired = runCatching {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    next,
                    renewPendingIntent(context),
                )
            }.isSuccess
            if (!fired) {
                am.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    next,
                    renewPendingIntent(context),
                )
            }
        }.onFailure { Log.w(TAG, "安排窗口探测失败: " + it.javaClass.simpleName) }
    }

    /** 停止扫描续期循环 */
    fun stopScanRenewal(context: Context) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(renewPendingIntent(app))
        }
    }

    /**
     * 续期一次并重新排下一次（由 [BleScanReceiver] 在收到续期广播时调用）。
     *
     * 这里刻意**不**调 ensureRegistered 的完整路径，而是复用它的实现：
     * 它内部会 stop+start，正是把扫描"重新点亮"所需要的动作。
     */
    fun renewAndReschedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (!BtTriggerStore.enabled.value) {
                stopScanRenewal(app)
                return
            }

            // 【红线权衡】窗口外不续期。
            //
            // 25 秒续期若 7×24 跑，就是每天约 3456 次唤醒，直接违反
            // AGENTS.md 的「后台零消耗」。而蓝牙触发只可能在上/下班卡
            // 的时间窗内生效（窗外扫到了闸门也必然拒绝）。
            // 所以窗外直接停摆，把整夜和大部分白天省下来。
            //
            // 窗口由 BtPunchRule.shouldKeepScanAlive 判定（纯函数，可单测）。
            if (!shouldKeepScanAliveNow(app)) {
                // 窗口外：停掉扫描本身，避免蓝牙栈继续空转。
                //
                // 【P0 修复·续期必须能自己重新进入窗口】
                //
                // 最初的写法是"窗口外 → stopScanRenewal() → return"，
                // 即彻底撤销闹钟。**这是错的**：
                // 撤销之后没有任何机制会在"时间进入下一个窗口"时把续期恢复，
                // 于是到了上班/下班窗口，扫描依然是停的 ——
                // 蓝牙触发静默失效，而界面一切正常。
                //
                // 真机证据：dumpsys alarm 里 RENEW_BT_SCAN 显示
                // "1 wakeups, last -1m35s" —— 只触发过一次就再没重排。
                //
                // 正确做法：依然停掉扫描（省电），但**保留闹钟的滚动重排**，
                // 只是把周期拉长。用一个较长的"窗口探测周期"继续跑，
                // 一旦探测到重新进入窗口，就回到 25 秒的活跃续期。
                stopInternal(app)
                BtTriggerStore.markScanRegistered(false)
                scheduleWindowProbe(app)
                Log.i(TAG, "不在打卡时间窗内，暂停 BLE 扫描并以 " +
                    (WINDOW_PROBE_INTERVAL_MS / 60_000) + " 分钟周期探测下一次窗口")
                return
            }

            // ensureRegistered 内部会调用 startScanRenewal，这里不要再排一次
            ensureRegistered(app)
        }.onFailure { Log.w(TAG, "扫描续期失败: " + it.javaClass.simpleName) }
    }

    /**
     * 当前是否处在需要保持扫描的时间窗内。
     *
     * 依赖当天"已打卡"状态与生效日，因此需要读库 —— 但这个方法只在
     * 25 秒一次的续期路径上调用，且都是轻量查询，开销可接受。
     *
     * 查询失败时**返回 true**（保守）：宁可多耗一点电，也不要因为
     * 一次读库失败就整天不触发。
     */
    fun shouldKeepScanAliveNow(context: Context): Boolean = runCatching {
        com.dingdinggo.app.core.AppConfig.reload()
        val settings = com.dingdinggo.app.core.AppConfig.settings.value
        if (!com.dingdinggo.app.core.AppConfig.enabled.value) return@runCatching false

        val now = System.currentTimeMillis()
        val isActiveDay = com.dingdinggo.app.core.PunchSchedule.isActiveDay(now, settings)

        // 【已打卡状态】只有"整班都打完了"才值得提前停扫描。
        // 读 AlarmScheduler 在真实结案（成功或超窗跳过）后按班次分开记录的当天完成日，
        // 避免 FIRE 刚触发或失败时误停扫描，也避免下班槽位覆盖上班完成态。
        val (clockInDoneToday, clockOutDoneToday) = com.dingdinggo.app.core.AlarmScheduler
            .readDoneToday(context, now)

        com.dingdinggo.app.core.LateConfirmStore.init(context)
        val lateWindowMin = if (!clockInDoneToday &&
            !com.dingdinggo.app.core.LateConfirmStore.hasPromptedToday(context, now)
        ) {
            com.dingdinggo.app.core.LateConfirmRule.DEFAULT_LATE_SCAN_WINDOW_MINUTES
        } else {
            0
        }

        com.dingdinggo.app.core.BtPunchRule.shouldKeepScanAlive(
            nowMillis = now,
            settings = settings,
            earlyWindowMinutes = BtTriggerStore.earlyWindowMinutes.value,
            isActiveDay = isActiveDay,
            clockInDoneToday = clockInDoneToday,
            clockOutDoneToday = clockOutDoneToday,
            lateConfirmWindowMinutes = lateWindowMin,
        )
    }.getOrDefault(true)

    /** 校验扫描结果是否匹配目标考勤机（UUID 必选 + MAC/名称增强，兼容 RPA 地址轮换） */
    internal fun matchesTargetDevice(result: ScanResult): Boolean {
        val uuids = runCatching { result.scanRecord?.serviceUuids }.getOrNull()
        val hasUuid = uuids != null && uuids.any {
            it.uuid.toString().equals(ATTENDANCE_SERVICE_UUID.uuid.toString(), true)
        }
        val uuidUnknown = uuids == null || uuids.isEmpty()
        if (!hasUuid && !uuidUnknown) return false

        val learnedMac = BtTriggerStore.deviceMac.value
        if (learnedMac.isNotBlank()) {
            val actual = runCatching { result.device?.address }.getOrNull()
            if (actual != null && actual.equals(learnedMac, ignoreCase = true)) return true
            val learnedName = BtTriggerStore.deviceName.value
            val actualName = runCatching { result.scanRecord?.deviceName }.getOrNull()
            if (learnedName.isNotBlank() && actualName != null &&
                actualName.equals(learnedName, ignoreCase = true)
            ) {
                return true
            }
            return hasUuid || uuidUnknown
        }

        return hasUuid || uuidUnknown
    }

    private const val ACTIVE_BURST_INTERVAL_MS = 90_000L

    @Volatile
    private var lastActiveBurstAtMs = 0L

    @Volatile
    private var activeBurstRunning = false

    private val activeBurstLock = Any()

    /**
     * 在有效打卡窗内执行一次短暂高灵敏度（LOW_LATENCY）主动扫描，唤醒深度 Doze 下被挂起的蓝牙控制器。
     *
     * 严格受节流保护（常规至少间隔 90 秒，仅首次连上公司 Wi-Fi 或刚解锁等强事件可传 force=true），
     * 并在 finally 里保证 stopScan 与释放 WakeLock。
     */
    @SuppressLint("MissingPermission")
    fun activeBurstScanAndHandle(
        context: Context,
        durationMs: Long = 4_000L,
        force: Boolean = false,
    ) {
        val app = context.applicationContext
        runCatching {
            com.dingdinggo.app.core.AppConfig.init(app)
            com.dingdinggo.app.core.AppConfig.reload()
            BtTriggerStore.init(app)
        }

        if (!BtTriggerStore.enabled.value) return
        if (blocker(app) != null) return
        if (!shouldKeepScanAliveNow(app)) return

        val now = System.currentTimeMillis()
        BtTriggerStore.refreshPresence()
        if (!force && BtTriggerStore.present.value) return

        synchronized(activeBurstLock) {
            if (activeBurstRunning) return
            if (!force && lastActiveBurstAtMs > 0L && now - lastActiveBurstAtMs < ACTIVE_BURST_INTERVAL_MS) {
                return
            }
            activeBurstRunning = true
            lastActiveBurstAtMs = now
        }

        try {
            val scanner = runCatching { adapter(app)?.bluetoothLeScanner }.getOrNull() ?: return
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = runCatching {
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoattend:bt_burst").apply {
                    setReferenceCounted(false)
                    acquire(durationMs + 3_000L)
                }
            }.getOrNull()

            val latch = CountDownLatch(1)
            val matchedResult = java.util.concurrent.atomic.AtomicReference<ScanResult?>(null)
            val activeCb = java.util.concurrent.atomic.AtomicReference<ScanCallback?>(null)

            try {
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (matchedResult.get() == null && matchesTargetDevice(result)) {
                            if (matchedResult.compareAndSet(null, result)) {
                                latch.countDown()
                            }
                        }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        if (matchedResult.get() != null) return
                        val hit = results.firstOrNull { matchesTargetDevice(it) }
                        if (hit != null && matchedResult.compareAndSet(null, hit)) {
                            latch.countDown()
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "主动短扫失败 errorCode=" + errorCode)
                        latch.countDown()
                    }
                }
                activeCb.set(cb)

                val filters = listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ATTENDANCE_SERVICE_UUID)
                        .build(),
                )
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build()

                scanner.startScan(filters, settings, cb)
                latch.await(durationMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "主动短扫异常: " + t.javaClass.simpleName)
            } finally {
                activeCb.get()?.let { cb -> runCatching { scanner.stopScan(cb) } }
                runCatching {
                    if (wl != null && wl.isHeld) wl.release()
                }
            }

            val hit = matchedResult.get()
            if (hit != null) {
                val hitAt = System.currentTimeMillis()
                BtTriggerStore.markSeen(hitAt)
                Log.i(
                    TAG,
                    "主动短扫命中考勤机 mac=" + runCatching { hit.device?.address }.getOrNull() +
                        " rssi=" + hit.rssi +
                        " name=" + runCatching { hit.scanRecord?.deviceName }.getOrNull(),
                )
                ArrivalTriggerCoordinator.onCompanyArrival(
                    context = app,
                    source = PunchForegroundService.SOURCE_BLUETOOTH,
                    nowMillis = hitAt,
                )
            }
        } finally {
            activeBurstRunning = false
        }
    }
}
