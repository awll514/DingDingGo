package com.dingdinggo.app.ble

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.BtPunchRule
import com.dingdinggo.app.core.BtTriggerStore
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.AppDatabase
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.exec.PunchAlarmReceiver
import com.dingdinggo.app.exec.PunchForegroundService
import com.dingdinggo.app.exec.PunchNotifier
import kotlinx.coroutines.runBlocking

/**
 * BLE 扫描结果的系统投递接收器。
 *
 * ## 这是整个蓝牙功能的"心脏"
 *
 * 它由**系统**在扫到匹配广告时拉起 —— 我们的进程可以是死的，
 * 整个 App 也可以被 HyperOS 冻结，只要扫描注册还在，这个广播就会到。
 * 这是把"进程存活"从可靠性依赖里摘出去的唯一办法。
 *
 * ## 硬性约束：BroadcastReceiver 只有 10 秒
 *
 * 所以这里**绝不**做任何耗时动作：不读界面、不 fork su、不启动钉钉。
 * 只做三件极轻的事：
 *   1. 解析扫到的设备，更新"在场"状态（给界面用）；
 *   2. 问 [BtPunchRule] 现在该不该打（纯函数，零 I/O）；
 *   3. 该打就把 [PunchForegroundService] 拉起来，然后立刻返回。
 *
 * 真正的打卡（点亮 → 解锁 → 拉钉钉 → 点击）全部在前台服务里做。
 *
 * ## 为什么要做"去重"
 *
 * 考勤机是持续广播的，扫描间隔 1.4 秒 —— 也就是说只要人还站在
 * 门口，这个接收器每秒可能被调用好几次。没有去重的后果是
 * 反复拉起服务、反复点击打卡按钮（钉钉会对重复打卡报警）。
 *
 * 三道去重，缺一不可：
 *   1. Room 里"今天这一班是否已 **成功**" —— 只有真成功才不再触发；
 *   2. 服务层的 `running` 互斥 —— 正在执行时新请求会被丢弃；
 *   3. [MIN_TRIGGER_INTERVAL_MS] —— 两次触发之间的硬冷却窗口。
 *
 * 【为什么不用"按天已处理"标记去重】
 * 第一版用 BtTriggerStore 的按天标记去重，是个真缺陷：
 * 打卡失败（钉钉没拉起、密码错、外勤闸门放弃、系统拒绝启动 FGS）是常态，
 * 而失败往往瞬时。一旦按天钉死，一次失败就让功能**当天永久失效**，
 * 界面上还显示"已开启"。现在改成上述三条**可恢复**的机制 ——
 * 失败后冷却 60 秒即可重试。
 */
class BleScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BleScanReceiver"

        /**
         * 硬冷却窗口：两次蓝牙触发之间至少间隔这么久。
         *
         * 选 60 秒的理由：一次完整打卡（点亮→解锁→拉钉钉→点击→判定）
         * 真机实测约 25~35 秒。冷却是为了覆盖"服务还在跑、但
         * 今日同步标记尚未写入"的那段空窗期。
         */
        private const val MIN_TRIGGER_INTERVAL_MS = 60_000L

        /**
         * 广播级去重窗口（毫秒）。
         *
         * 比 [MIN_TRIGGER_INTERVAL_MS] 小得多，只用来挡掉
         * "同一次扫描的连环投递 + 人站在机器旁持续广播"导致的
         * 高频重复处理。10 秒足以覆盖考勤机的广播周期，
         * 又不会影响"人离开再回来"的正常触发。
         */
        private const val MIN_SCAN_DEDUPE_MS = 10_000L

        /** 上一次处理广播的时刻（纯内存，不落盘） */
        @Volatile
        private var lastReceiveAtMs = 0L

        /** 保护 [lastReceiveAtMs] 的读改写，避免并发广播交错 */
        private val seenLock = Any()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()

        // ---------- -1. 扫描续期（必须放在最前面，且不受扫描去重影响）----------
        //
        // 真机实测：蓝牙栈会在约 60 秒后自行停掉 PendingIntent 扫描，
        // 且不会自动重启。靠 15 分钟的自检续期，扫描实际有效率只有约 6.7%，
        // 蓝牙触发几乎永远不响应（详见 BtScanner.startScanRenewal 的长注释）。
        //
        // 这里必须先于下面那层 10 秒去重判断：续期是**控制路径**，
        // 与"扫描结果"是两条独立的数据流，不能被去重窗口吞掉。
        if (intent.action == BtScanner.ACTION_SCAN_RENEW) {
            runCatching {
                AppConfig.init(app)
                BtTriggerStore.init(app)
            }
            BtScanner.renewAndReschedule(app)
            return
        }

        // ---------- 0. 最外层内存节流（必须在任何 I/O 之前） ----------
        //
        // ## 为什么这一层不能省
        //
        // 考勤机约每 1.4 秒广播一次，而且**人一整天都坐在工位旁边**。
        // 如果每次广播都去读 prefs / 解密 secure prefs / 查 Room，
        // 就是"每 1.4 秒一次主线程重活"——这正是 AGENTS.md 里
        // 「后台零消耗」红线明确禁止的。
        //
        // 这一层只碰两个 @Volatile Long，零 I/O、零分配：
        // 冷却期内直接返回，后面的所有昂贵操作都不会执行。
        @Suppress("UNUSED_EXPRESSION")
        synchronized(seenLock) {
            if (lastReceiveAtMs > 0L && now - lastReceiveAtMs < MIN_SCAN_DEDUPE_MS) {
                return
            }
            lastReceiveAtMs = now
        }

        // ---------- 1. 冷启动配置 ----------
        //
        // 进程被广播冷唤起时所有 StateFlow 都是构造默认值。
        // 不 reload 的话 BtTriggerStore.enabled 会是 false，
        // 整个功能静默失效 —— 这与 PunchAlarmReceiver 的注释是同一个坑。
        runCatching {
            AppConfig.init(app)
            AppConfig.reload()
            BtTriggerStore.init(app)
        }.onFailure {
            Log.e(TAG, "初始化配置失败: " + it.javaClass.simpleName)
            return
        }

        if (!BtTriggerStore.enabled.value) {
            Log.i(TAG, "蓝牙触发已关闭，忽略扫描结果")
            return
        }

        // ---------- 2. 解析扫到的设备 ----------
        val results = extractScanResults(intent)
        if (results.isEmpty()) {
            Log.w(TAG, "扫描结果为空（可能被 ROM 过滤），忽略")
            return
        }

        val matched = results.firstOrNull { matchesTargetDevice(it) }
        if (matched == null) {
            // 扫到的是别的 fe3c 设备（同楼其他考勤机），不是我们学到的这台
            Log.i(TAG, "扫描结果不匹配已学到的考勤机，忽略（共 " + results.size + " 条）")
            return
        }

        BtTriggerStore.markSeen(now)
        Log.i(
            TAG,
            "检测到考勤机 mac=" + matched.device?.address +
                " rssi=" + matched.rssi +
                " name=" + runCatching { matched.scanRecord?.deviceName }.getOrNull(),
        )

        // ---------- 3. 冷却检查 ----------
        val lastTriggerAt = BtTriggerStore.lastTriggerAt.value
        if (lastTriggerAt > 0L && now - lastTriggerAt < MIN_TRIGGER_INTERVAL_MS) {
            Log.i(TAG, "距上次蓝牙触发不足冷却窗口，忽略")
            return
        }

        // ---------- 4. 决策 ----------
        val decision = runCatching { decide(app, now) }.getOrElse { e ->
            Log.e(TAG, "决策失败: " + e.javaClass.simpleName, e)
            return
        }

        // 【只在"真的去执行"时才刷新冷却基准与最近触发记录】
        //
        // 第一版把 markTriggered 放在 shouldPunch 判断之前，结果是：
        // 每一个 SKIP（尤其 SKIP_TOO_LATE / SKIP_NOT_YET_CLOCK_OUT）
        // 都会刷新 lastTriggerAt —— 而 lastTriggerAt 同时也是
        // "上次触发时间"这个 UI 字段。
        //
        // 后果：人一整天坐在工位旁，界面上"上次触发"每分钟被刷新一次，
        // 而且 notifyBtSkipped 会被反复触发（见下方 shouldNotify）。
        //
        // 现在的分工：
        //   · SKIP：只更新 lastSeen（在场状态），不碰触发记录；
        //   · PUNCH：更新 lastTrigger 记录，冷却生效。
        if (!decision.shouldPunch) {
            Log.i(
                TAG,
                "蓝牙决策 action=" + decision.action + " msg=" + decision.message,
            )
        }

        if (!decision.shouldPunch) {
            // 不打的时候也要让用户知情 —— 但要克制：
            // "太早/太晚/今天已打" 这几种是常态，不该发通知打扰。
            if (shouldNotify(decision.action)) {
                runCatching {
                    PunchNotifier.notifyBtSkippedOnceADay(
                        app,
                        decision.message,
                        decision.kind ?: PunchKind.CLOCK_IN,
                    )
                }
            }
            return
        }

        // 到了这一步说明真的要打卡了：记流水并刷新冷却
        BtTriggerStore.markTriggered(now, decision.message)
        Log.i(
            TAG,
            "蓝牙决策 action=" + decision.action + " kind=" + decision.kind +
                " msg=" + decision.message,
        )

        // ---------- 5. 拉起执行 ----------
        val kind = decision.kind ?: return
        launchPunch(app, kind, decision.punchAtMillis)
    }

    /**
     * 解析系统塞进 Intent 的扫描结果。
     *
     * 蓝牙栈把结果放在 [BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT]（Parcelable 数组）。
     * 部分 ROM 会改字段名，所以两个 key 都试。
     */
    private fun extractScanResults(intent: Intent): List<ScanResult> {
        val key = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT

        // 首选：官方 key，Parcelable 列表（正常路径）
        //
        // getParcelableArrayListExtra 在新 SDK 上标了 deprecated，但带
        // 类型参数的旧签名在 API 36 上仍然可用且没有替代品能满足
        // "读系统注入的 Parcelable 列表"这个需求（getParcelableArrayListExtra
        // 的 Class 版对 ScanResult 数组会走不通）。这里保留并显式抑制警告。
        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(key)
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        // 兜底 1：部分 ROM 用单条 Parcelable 投递
        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<ScanResult>(key)
        }.getOrNull()?.let { return listOf(it) }

        // 兜底 2：极少数实现会塞成普通 ArrayList<*>（非泛型），
        // 逐个 instanceof 检查，避免 ClassCastException 让整个接收器崩掉。
        runCatching {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(key) as? ArrayList<*>
        }.getOrNull()?.let { raw ->
            val typed = raw.filterIsInstance<ScanResult>()
            if (typed.isNotEmpty()) return typed
        }

        return emptyList()
    }

    /**
     * 是否是考勤机。
     *
     * ## 匹配优先级（这里踩过一个必须避免的坑）
     *
     * 第一版的设计是"学到过 MAC 就必须 MAC 相等"。这是**错的**：
     *
     * BLE 未配对设备的地址是当前 RPA（Resolvable Private Address），
     * **会周期变化**。ScanResult 的 javadoc 明确写：只有已配对的设备
     * 才会返回身份地址（identity address）。考勤机不会被配对，
     * 所以它的 getAddress() 每次扫描得到的值可能不同 ——
     * 按 MAC 强匹配会导致"学到了但永远匹配不上"，功能静默失效。
     *
     * 因此改为：
     * 1. **UUID 是必要条件**（在控制器层已过滤，这里二次确认）；
     * 2. MAC **只在相等时增强可信度**，不相等**不否决**；
     * 3. 若学到过设备名且广播里的名字也一致，视为强命中。
     *
     * 结果：功能不会因为 MAC 变化而失效，同时"学到设备"仍有实际意义
     * （用于 UI 展示，以及同楼多台机器时优先认名字一致的那台）。
     */
    private fun matchesTargetDevice(result: ScanResult): Boolean {
        // 条件 1：广播里必须带 0000fe3c 服务 UUID。
        val uuids = runCatching { result.scanRecord?.serviceUuids }.getOrNull()
        val hasUuid = uuids != null && uuids.any {
            it.uuid.toString().equals(BtScanner.ATTENDANCE_SERVICE_UUID.uuid.toString(), true)
        }

        // 部分 ROM 投递的结果里 serviceUuids 为空（被裁剪），
        // 但控制器层已经按 UUID 过滤过了 —— 这种情况放行，不能因为
        // "看不到 UUID" 就否决，否则在那些 ROM 上功能完全不可用。
        val uuidUnknown = uuids == null || uuids.isEmpty()
        if (!hasUuid && !uuidUnknown) return false

        val learnedMac = BtTriggerStore.deviceMac.value
        if (learnedMac.isNotBlank()) {
            val actual = runCatching { result.device?.address }.getOrNull()
            // MAC 相等 → 强命中，直接通过
            if (actual != null && actual.equals(learnedMac, ignoreCase = true)) return true
            // MAC 不等 → 可能是 RPA 变化，继续看设备名
            val learnedName = BtTriggerStore.deviceName.value
            val actualName = runCatching { result.scanRecord?.deviceName }.getOrNull()
            if (learnedName.isNotBlank() && actualName != null &&
                actualName.equals(learnedName, ignoreCase = true)
            ) {
                return true
            }
            // 都没有：只要 UUID 对得上就认。宁可多触发一次（有安全阀兜底），
            // 也不要因为地址变化而静默失效 —— 后者用户完全无从察觉。
            return hasUuid || uuidUnknown
        }

        return hasUuid || uuidUnknown
    }

    /**
     * 拉取今日打卡状态并做决策。
     *
     * 查库用 runBlocking：onReceive 有 10 秒预算，而这里是两次
     * 走索引的本地 SQLite 查询，亚毫秒级。与 AlarmScheduler 的做法一致。
     */
    private fun decide(context: Context, now: Long): BtPunchRule.Decision {
        val settings = AppConfig.settings.value

        val done = runCatching {
            runBlocking {
                val dao = AppDatabase.get(context).punchLogDao()
                val startOfToday = PunchSchedule.startOfDay(now, 0)
                listOfNotNull(
                    dao.lastSuccessSince(PunchKind.CLOCK_IN, startOfToday),
                    dao.lastSuccessSince(PunchKind.CLOCK_OUT, startOfToday),
                )
            }
        }.getOrDefault(emptyList())

        val clockInDone = done.any { it.kind == PunchKind.CLOCK_IN }
        val clockOutDone = done.any { it.kind == PunchKind.CLOCK_OUT }

        // 【只有"真正成功"才算已处理 —— 用 Room 里的 SUCCESS 记录】
        //
        // 这里**刻意不用** BtTriggerStore 的按天"已同步"标记：
        // 那是 P1 缺陷的来源。打卡失败（钉钉没拉起来、密码错、外勤闸门放弃、
        // 系统拒绝启动 FGS）是**常态**，而失败往往是瞬时的：
        // 用户走回考勤机旁边、解锁重试一次就成了。
        //
        // 如果拉服务之前就按天钉死"今天这一班已由蓝牙处理"，
        // 那么一次失败会让这个功能**当天永久失效**，而界面上还显示"已开启"。
        //
        // 现在的去重完全靠三件事，全部是"可恢复"的：
        //   1. MIN_TRIGGER_INTERVAL_MS 冷却（60 秒）—— 挡掉同一秒的重复广播；
        //   2. 服务层 running 互斥 —— 执行期间新请求被丢弃；
        //   3. 这里的 Room SUCCESS 查询 —— 成功之后自然不再触发。
        // 于是"失败 → 60 秒后重试"这条路径是通的。

        return BtPunchRule.decide(
            nowMillis = now,
            settings = settings,
            clockInDoneToday = clockInDone,
            clockOutDoneToday = clockOutDone,
            isActiveDay = PunchSchedule.isActiveDay(now, settings),
            skippedToday = AppConfig.isSkipped(PunchSchedule.dateKey(now)),
            enabled = AppConfig.enabled.value,
            earlyWindowMinutes = BtTriggerStore.earlyWindowMinutes.value,
        )
    }

    /**
     * 哪些"不打卡"的结论值得打扰用户。
     *
     * 克制原则：只有**用户需要知情并可能采取行动**的才通知。
     * - 太早（还没到早到窗口）：常态，不打扰；
     * - 今天已打/非生效日/已跳过：用户自己设置的，不打扰；
     * - 迟到边界已过：**必须**通知 —— 用户今天没打上卡，需要手动处理；
     * - 还没到下班时间：可以在界面上看到，但常驻通知会吵，不发。
     */
    private fun shouldNotify(action: BtPunchRule.Action): Boolean =
        action == BtPunchRule.Action.SKIP_TOO_LATE

    /**
     * 拉起前台服务执行打卡。
     *
     * ## 关键：targetAt 传的是"此刻"
     *
     * 用户确认的语义是「蓝牙检测时刻 = 今日实际上班/下班时间」。
     * 所以这里把 now 作为该班次的计划时刻传下去。
     *
     * 这与手动打卡的坑**不冲突**：那次事故的根因是
     * 「target=now 让迟到闸门永远放行」。
     * 而现在 [BtPunchRule] 已经在**拉起服务之前**用配置的计划时刻
     * 做过完整的红线判定，只有确实合法的才会走到这里；
     * 引擎内部还会用同样的规则再判一次（纵深防御）。
     *
     * 服务收尾时会把 now 作为 BaseTime 写进 Room 的 scheduledAt，
     * 这样首页的「今日上班 08:47」显示的就是真实的检测时刻 ——
     * 正是用户要的"同步上下班时间"。
     */
    private fun launchPunch(context: Context, kind: PunchKind, targetAt: Long) {
        // 红线判定基准 = 今天配置的计划时刻（不是检测时刻）。
        // 检测时刻只用于"记录/显示今日实际打卡时间"。
        val settings = AppConfig.settings.value
        val dayStart = PunchSchedule.startOfDay(targetAt)
        val gateTarget = dayStart + java.util.concurrent.TimeUnit.MINUTES.toMillis(
            when (kind) {
                PunchKind.CLOCK_OUT -> settings.clockOutMinute
                else -> settings.clockInMinute
            }.toLong(),
        )
        // 【不要在这里写"今日已同步"标记】
        //
        // 见上面 decide() 的说明：那会让一次失败把整个功能当天钉死。
        // 重复拉起由 60 秒冷却 + 服务 running 互斥兜住，足够。

        val intent = Intent(context, PunchForegroundService::class.java).apply {
            action = PunchForegroundService.ACTION_EXECUTE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, targetAt)
            // 蓝牙触发是"立刻执行"，绝不走预热等待
            putExtra(PunchForegroundService.EXTRA_PREWARM, false)
            // 标记来源，服务据此决定通知文案与收尾行为
            putExtra(PunchForegroundService.EXTRA_SOURCE, PunchForegroundService.SOURCE_BLUETOOTH)
            putExtra(PunchForegroundService.EXTRA_BT_GATE_TARGET_AT, gateTarget)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "已拉起打卡服务 kind=" + kind.name + " target=" + targetAt)
        } catch (e: Throwable) {
            // 后台启动被系统拒绝 —— 这是保活链路断掉的确证，必须让用户知情
            Log.e(TAG, "startForegroundService 被拒绝: " + e.javaClass.simpleName, e)
            runCatching {
                PunchNotifier.notifyStartBlocked(context, kind)
            }
        }
    }
}
