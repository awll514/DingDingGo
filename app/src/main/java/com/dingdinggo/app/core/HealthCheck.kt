package com.dingdinggo.app.core

import com.dingdinggo.app.ble.BtScanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dingdinggo.app.exec.PunchAccessibilityService
import com.dingdinggo.app.exec.RootShell

/**
 * 保活与权限健康度。
 *
 * 诚实原则（需求 F5 与"风险与诚实边界"）：
 * - 只报告"检测到的事实"，不承诺"绝对不死"；
 * - 每一项都给出可操作的跳转按钮；
 * - 检测不到时明确说"无法确认"，而不是假装正常。
 */
object HealthCheck {

    /**
     * 心跳间隔是否异常（= 守护中断过的间接信号）。
     *
     * ## 为什么只能是间接信号
     *
     * Android 不允许应用读取自己的 FLAG_STOPPED 状态 —— 能运行就说明
     * 系统已清除该标志（真机实测 dumpsys package 显示 flags=0x0，
     * 不含 FLAG_STOPPED）。
     *
     * 所以只能通过"我睡了多久没醒"反推：正常心跳每 10 分钟一次，
     * 若某次醒来发现间隔远超预期，说明中间断过（被 force-stop /
     * 被系统清理 / 设备长时间关机）。
     *
     * 纯函数，便于单测钉住阈值语义。
     */
    internal fun isGuardGapAbnormal(gapMs: Long): Boolean =
        gapMs > GUARD_GAP_LIMIT_MS

    /**
     * 心跳间隔告警阈值 = 正常周期（10 分钟）× 3。
     *
     * 取 3 倍的理由：Doze / 省电策略确实可能推迟心跳一两轮，
     * 但连续跳过 3 次就说明不是"推迟"而是"断了"。
     * 阈值太敏感会误报（用户被无谓打扰），太钝又失去意义。
     */
    internal const val GUARD_GAP_LIMIT_MS = 30 * 60 * 1000L

    /** 把间隔格式化成给用户看的文案 */
    internal fun formatGuardGap(gapMs: Long): String {
        val gapMin = gapMs / 60_000L
        // 【阈值修正】原来用 120 分钟作为切换点，导致 60~119 分钟都显示成
        // "119 分钟"这种不自然的文案。改到 60：满一小时就换算成小时。
        return if (gapMin < 60) {
            gapMin.toString() + " 分钟"
        } else {
            (gapMin / 60).toString() + " 小时 " + (gapMin % 60) + " 分"
        }
    }

    private const val TAG = "HealthCheck"

    /** 单项检测结果 */
    data class Item(
        val key: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
        val canFix: Boolean,
        /**
         * 该项无法被程序检测（不是"未通过"）。
         *
         * 引入这个状态的背景：自启动在 Android 上没有任何可读 API，
         * 之前把它硬编码成 ok=false，导致用户明明开了自启动，
         * 界面却显示"未开启"并计入问题数 —— 这是**误报**，比不显示更糟。
         *
         * neutral = true 的项：显示灰色圆点、不计入问题数、不参与 allOk。
         */
        val neutral: Boolean = false,
    )

    /** 整体健康度 */
    data class Report(
        val items: List<Item>,
        val alarmScheduled: Boolean,
        val nextSlotText: String?,
    ) {
        /** 已确认正常的项（不含无法检测的） */
        val knownItems: List<Item> get() = items.filter { !it.neutral }

        /** 是否全部正常（无法检测的项不参与判定，不假装它们通过） */
        val allOk: Boolean
            get() = knownItems.all { it.ok } && alarmScheduled

        /** 有问题的项（只含能确定失败的，不含无法检测的） */
        val problems: List<Item> get() = knownItems.filter { !it.ok }
    }

    /**
     * 执行一次完整检测。
     *
     * 注意：这个函数会做一些跨进程调用（root 探测、无障碍状态），
     * 应该在 IO 线程调用。
     */
    fun check(context: Context, nextSlot: NextPunchSlot?): Report {
        val items = mutableListOf<Item>()

        // 1. 精确闹钟（注意：电池白名单是官方豁免，见 AlarmScheduler.exactStatus）
        val (exactAlarm, exactDetail) = AlarmScheduler.exactStatus(context)
        items.add(
            Item(
                key = "exact_alarm",
                label = "精确闹钟",
                ok = exactAlarm,
                detail = if (exactAlarm) exactDetail else "$exactDetail，打卡可能延迟",
                canFix = !exactAlarm,
            )
        )

        // 2. 通知权限
        val notifOk = notificationEnabled(context)
        items.add(
            Item(
                key = "notification",
                label = "通知权限",
                ok = notifOk,
                detail = if (notifOk) "已开启，结果可送达" else "已关闭，打卡结果无法提醒",
                canFix = !notifOk,
            )
        )

        // 3. 无障碍服务
        //
        // 【必须双判据】只查 PunchAccessibilityService.isReady()（= 进程内 instance
        // 是否为 null）是不够的：
        //   · 重装 App 后系统已重新绑定服务，但 activity/进程可能还没走到
        //     onServiceConnected，instance 仍是 null；
        //   · 反过来，服务被系统停掉但进程还在时，instance 也可能是旧值。
        // 结果是首页健康度显示「未开启」，而系统里其实是开着的 —— 用户在
        // 设置页点「去设置」会发现已经开了，只会更困惑。
        //
        // 所以：进程内实例 **或** 系统无障碍列表里包含本服务，任一成立即视为在线。
        val accOk = PunchAccessibilityService.isReady() || isAccessibilityServiceEnabled(context)
        items.add(
            Item(
                key = "accessibility",
                label = "无障碍服务",
                ok = accOk,
                detail = if (accOk) "在线，可用无障碍通道" else "未开启，将只能依赖 root 通道",
                canFix = !accOk,
            )
        )

        // 4. root 可用性
        val rootOk = RootShell.isAvailable()
        items.add(
            Item(
                key = "root",
                label = "Root 授权",
                ok = rootOk,
                detail = if (rootOk) "可用，解锁与点击更可靠" else "不可用，仅能靠无障碍",
                canFix = !rootOk,
            )
        )

        // 5. 电池优化白名单（同时是精确闹钟的官方豁免，优先级很高）
        val batteryOk = isIgnoringBatteryOptimizations(context)
        items.add(
            Item(
                key = "battery",
                label = "电池无限制",
                ok = batteryOk,
                detail = if (batteryOk) {
                    "已加入白名单，同时豁免精确闹钟限制"
                } else {
                    "未加入：后台可能被清理，精确闹钟也会受限"
                },
                canFix = !batteryOk,
            )
        )

        // 6. 钉钉是否安装
        val dingtalkOk = isDingTalkInstalled(context)
        items.add(
            Item(
                key = "dingtalk",
                // 标签必须跟随实际结果。
                // 之前写死成"钉钉已安装"，于是没装钉钉时会出现
                // 标题说"已安装"、正文说"未检测到"的自相矛盾。
                label = if (dingtalkOk) "钉钉已安装" else "钉钉未安装",
                ok = dingtalkOk,
                detail = if (dingtalkOk) {
                    "已安装，可正常拉起"
                } else {
                    "未检测到钉钉，打卡无法进行。请先安装钉钉。"
                },
                canFix = false,
            )
        )

        // 6.5 自启动（尽量真实检测，查不出来才标中性）
        val autostartState = detectAutostart(context)
        items.add(
            Item(
                key = "autostart",
                label = when (autostartState) {
                    AutostartState.ENABLED -> "自启动"
                    AutostartState.DISABLED -> "自启动未开启"
                    AutostartState.UNKNOWN -> "自启动（需自行确认）"
                },
                ok = autostartState == AutostartState.ENABLED,
                // 只有明确"未开启"才算问题；查不出来时保持中性，不误报。
                neutral = autostartState == AutostartState.UNKNOWN,
                detail = when (autostartState) {
                    AutostartState.ENABLED ->
                        "已确认开启，重启后闹钟会自动恢复"
                    AutostartState.DISABLED ->
                        "确认未开启。重启后闹钟不会恢复，务必打开。"
                    AutostartState.UNKNOWN ->
                        "系统未提供读取接口，且 root 查询未命中。" +
                            "若已开启可忽略；关闭时重启后闹钟不会恢复。"
                },
                canFix = autostartState != AutostartState.ENABLED,
            )
        )

        // 6.9 【真机新发现】守护是否中断过
        //
        // ## 这个检查解决的是一个此前完全没有覆盖的失效场景
        //
        // 用户「强行停止」应用后，包进入 FLAG_STOPPED 状态。真机实测确认：
        //   · 所有广播都投递不到（am broadcast 返回 result=0，进程不拉起）；
        //   · JobScheduler 的 job 被系统删除；
        //   · 闹钟虽然还在系统里，但触发时同样投递不出去。
        //
        // 也就是说**保活机制全部永久失效**。而这是 Android 的设计行为，
        // 任何应用都无法绕过（也不应该绕过）。
        //
        // 问题在于：应用自己**读不到**这个状态 —— 能运行就说明标志已被
        // 系统清除（实测 dumpsys package 显示 flags=0x0，无 FLAG_STOPPED）。
        //
        // 所以唯一的信号是**心跳间隔异常**：正常 10 分钟一次，
        // 若发现距上次心跳已远超这个间隔，说明中间断过。
        // 这里如实告诉用户"守护可能断过，建议重新打开一次应用"，
        // 而不是继续显示一片绿色。
        if (AppConfig.enabled.value) {
            val lastBeat = runCatching {
                context.getSharedPreferences(
                    com.dingdinggo.app.exec.HeartbeatReceiver.PREFS,
                    Context.MODE_PRIVATE,
                ).getLong(com.dingdinggo.app.exec.HeartbeatReceiver.KEY_LAST_HEARTBEAT_AT, 0L)
            }.getOrDefault(0L)

            if (lastBeat > 0L) {
                val gapMs = System.currentTimeMillis() - lastBeat
                if (isGuardGapAbnormal(gapMs)) {
                    val gapText = formatGuardGap(gapMs)
                    items.add(
                        Item(
                            key = "guard_gap",
                            label = "守护可能中断过",
                            ok = false,
                            detail = "距上次后台心跳已过去 " + gapText + "（正常应每 10 分钟一次）。" +
                                "如果你最近「强行停止」过本应用，Android 会禁止它接收任何广播，" +
                                "所有保活与自动打卡都会失效。请重新打开一次应用恢复。",
                            canFix = false,
                        )
                    )
                }
            }
        }

        // 7. 锁屏密码是否配置
        //
        // 【审计 D1 修复】必须同时暴露"加密存储已降级"这个状态。
        //
        // AppConfig.securePrefs() 在 Keystore 失效（系统升级/数据迁移/
        // 备份恢复，注释里自述的真机故障）时会 wipeBrokenSecureStorage()
        // 删掉已存密码并降级到普通存储，同时把 secureStorageDegraded 置 true。
        //
        // 但那个标志在修复前**全项目零消费者** —— 只有写入没有读取。
        // 于是真实的失败链是静默的：
        //   密码被删 → hasPassword=false 或密码读不出 →
        //   引擎走"无密码上滑"分支 → 锁屏下解锁失败 → 漏打卡，
        //   而界面仍然说"已配置，可自动解锁"，用户完全无从知情。
        //
        // 现在把它作为**独立的健康项**如实呈现，并且优先级高于 pwdOk：
        // 降级状态下即使 pwdOk 为 true 也不可信（读出来可能是空的）。
        val degraded = AppConfig.secureStorageDegraded.value
        val pwdOk = AppConfig.hasPassword.value && !degraded
        items.add(
            Item(
                key = "password",
                label = when {
                    degraded -> "锁屏密码（加密存储已降级）"
                    pwdOk -> "锁屏密码"
                    else -> "锁屏密码未配置"
                },
                ok = pwdOk,
                detail = when {
                    degraded ->
                        "系统密钥库不可用，密码存储已降级为普通存储，原密码已失效。" +
                            "锁屏状态下很可能无法自动解锁 —— 请重新设置一次锁屏密码。"
                    pwdOk -> "已配置，可自动解锁"
                    else -> "未配置，锁屏下无法自动进入桌面"
                },
                canFix = !pwdOk,
            )
        )

        // 7.5 蓝牙触发源（只在用户开启该功能时才检查）
        //
        // 用 neutral 语义的变体：功能没开启时**不添加这一项**，
        // 而不是加一个"未启用"的灰点 —— 健康度卡片是给用户看
        // "有没有问题"的，把"你选择不用的功能"列进去只是噪音。
        // 【只读检查，绝不产生副作用】
        //
        // 第一版在这里调了 BtScanner.ensureRegistered()，那是个真缺陷：
        //   · ensureRegistered 内部会"先 stopScan 再 startScan"，
        //     用来判断健康状态的动作反而会**把已注册的扫描拆掉重建**；
        //     万一重建失败（权限被撤、配额满），就是把"好的"弄成"坏的"；
        //   · HealthCheck.check() 被 MainViewModel 在多个地方调用
        //     （启动、切 tab、改设置…），每次刷新都会重扫一遍。
        //
        // 健康检查的职责是**如实报告**，不是"顺手修一下"。
        // 真正的自愈在 KeepAliveService 的 15 分钟自检与 BootReceiver 里，
        // 那里重注册是刻意的、有明确时机的。
        //
        // 这里只读 BtTriggerStore 的记录 + 无副作用的 blocker() 判断。
        runCatching {
            BtTriggerStore.init(context)
            if (BtTriggerStore.enabled.value) {
                val blocker = BtScanner.blocker(context)
                val bgBlocker = BtScanner.backgroundBlocker(context)
                val scanOk = BtTriggerStore.scanRegistered.value
                val inWindow = BtScanner.shouldKeepScanAliveNow(context)
                val btOk = blocker == null && bgBlocker == null && (scanOk || !inWindow)
                items.add(
                    Item(
                        key = "bluetooth",
                        label = "蓝牙触发",
                        ok = btOk,
                        detail = when {
                            blocker != null -> blocker
                            bgBlocker != null -> bgBlocker
                            scanOk && BtTriggerStore.present.value ->
                                "扫描已注册 · 当前在考勤机范围内"
                            scanOk -> "扫描已注册 · 等待进入范围"
                            !inWindow -> "非打卡时间窗 · 省电休眠（将在窗口期自动恢复扫描）"
                            else -> "扫描未注册（点「修复」可立即重新注册）"
                        },
                        canFix = !btOk,
                    )
                )
            }
        }

        // 7.6 公司 Wi-Fi 触发（只在用户开启该功能时才检查）
        runCatching {
            WifiTriggerStore.init(context)
            if (WifiTriggerStore.enabled.value) {
                val targetName = WifiTriggerStore.targetSsid.value.ifBlank {
                    WifiTriggerStore.targetBssid.value
                }
                val hasTarget = targetName.isNotBlank()
                val canReadBg = BtScanner.hasBackgroundLocationPermission(context) || rootOk
                val wifiOk = hasTarget && canReadBg
                items.add(
                    Item(
                        key = "wifi_trigger",
                        label = "公司 Wi-Fi 触发",
                        ok = wifiOk,
                        detail = when {
                            !hasTarget -> "已开启但尚未记录公司 Wi-Fi，请到设置页连接后点击记录"
                            !canReadBg -> "缺少「始终允许」后台定位权限且 Root 不可用，息屏后台可能无法识别公司 Wi-Fi"
                            WifiTriggerStore.connected.value ->
                                "已连接「$targetName」"
                            else ->
                                "已记录「$targetName」，等待连入公司网络"
                        },
                        canFix = !wifiOk,
                    )
                )
            }
        }

        // 7.8 【审计 D7】上下班时间顺序
        //
        // 设反（上班晚于下班）的后果：上班卡是双边窗口
        // [target-75s, target+55s]，下班卡只有下界 target+2s，
        // 两者会互相打架，每天固定漏一张卡，而日志里闸门"看起来都正常"。
        //
        // 不阻断保存（可能是跨零点排班），只如实提示。
        if (AppConfig.enabled.value && AppConfig.scheduleOrderLooksSwapped()) {
            items.add(
                Item(
                    key = "schedule_order",
                    label = "上下班时间顺序可疑",
                    ok = false,
                    detail = "上班时间晚于或等于下班时间。" +
                        "如果你不是跨零点排班，请到「打卡时间」里检查；" +
                        "顺序设反会导致每天固定漏打一张卡。",
                    canFix = true,
                )
            )
        }

        // 7.9 【审计 F7】闹钟是否**真的**注册在系统里
        //
        // 旧实现里 alarmScheduled 只是 "nextSlot != null"（纯算出来的），
        // 与 AlarmManager 的真实状态无关 —— 这正是 P0「闹钟永久丢失」
        // 整整一天没被界面发现的原因：卡片一直显示"下次 09:30"，系统里却没有。
        //
        // 现在用 PendingIntent.FLAG_NO_CREATE 做只读探测。措辞必须诚实：
        // 探测能证明"没有"，但不能反证"一定有"（PI 存在 ≠ 闹钟没被清），
        // 所以失败时明确报警、成功时也只说"已注册"而不承诺"一定会触发"。
        if (nextSlot != null && AppConfig.enabled.value) {
            val registered = AlarmScheduler.isSlotAlarmRegistered(context, nextSlot)
            items.add(
                Item(
                    key = "alarm_registered",
                    label = if (registered) "闹钟已注册" else "闹钟未注册",
                    ok = registered,
                    detail = if (registered) {
                        "系统内已存在本次打卡的闹钟注册。"
                    } else {
                        "系统里查不到本次打卡的闹钟，今天很可能不会自动执行。" +
                            "请尝试重新打开自动打卡开关，或重启 App 让它重排。"
                    },
                    canFix = !registered,
                )
            )
        }

        // 7.10 屏幕接管悬浮窗权限（仅在开启「屏幕接管提示」时检查）
        if (AppConfig.showControlHud.value) {
            val overlayOk = Settings.canDrawOverlays(context)
            items.add(
                Item(
                    key = "overlay",
                    label = "悬浮窗权限",
                    ok = overlayOk,
                    detail = if (overlayOk) {
                        "已开启，接管提示与打卡结果浮条可正常弹出"
                    } else {
                        "未开启：自动打卡接管条与结果悬浮窗将无法显示"
                    },
                    canFix = !overlayOk,
                )
            )
        }

        // 8. 下一个闹钟
        val alarmScheduled = nextSlot != null
        val nextText = nextSlot?.let {
            val label = when (it.kind) {
                com.dingdinggo.app.data.db.PunchKind.CLOCK_IN -> "上班"
                com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> "下班"
                com.dingdinggo.app.data.db.PunchKind.MANUAL -> "手动"
            }
            label + " " + PunchSchedule.formatTime(it.targetAtMillis)
        }

        return Report(items = items, alarmScheduled = alarmScheduled, nextSlotText = nextText)
    }

    // ---------- 单项查询 ----------

    /**
     * 通知权限是否可用。
     *
     * ## 为什么要两个判据（真机踩到）
     *
     * 只查 NotificationManager.areNotificationsEnabled() 在 HyperOS 上会误报「已关闭」：
     * 实测 `dumpsys package ... POST_NOTIFICATIONS: granted=true` 的同时，
     * 该 API 仍可能返回 false（权限刚授予、或系统侧通知总开关与 App 权限分别记账）。
     *
     * 用户看到「通知权限 已关闭」，点「去设置」却发现是开着的 —— 只会更困惑。
     *
     * 所以：运行时权限已授予 **或** 通知渠道未被关闭，任一成立即视为可用。
     * （两者都为假才是真的不能发通知。）
     */
    fun notificationEnabled(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val channelOn = runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.areNotificationsEnabled()
        }.getOrDefault(false)
        return granted || channelOn
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isDingTalkInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.alibaba.android.rimet", 0)
        true
    }.getOrDefault(false)
    /**
     * 系统层面是否已启用本应用的**无障碍服务**（不依赖进程内实例）。
     *
     * ## 为什么要单独查一次
     *
     * [PunchAccessibilityService.isReady] 看的是静态 instance。
     * 但「系统已绑定服务」和「进程里 onServiceConnected 已跑过」是两件事：
     * 重装 App、进程被杀后重建、服务刚被用户打开等时刻，两者会出现不一致。
     *
     * 直接读 Settings.Secure 里的 enabled_accessibility_services 是系统事实，
     * 不受进程生命周期影响。
     *
     * 注意：这里**只用于健康度展示与决策辅助**，真正的执行仍然以
     * PunchAccessibilityService.get() 是否非空为准（拿不到实例就做不了操作）。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean = runCatching {
        val expected = context.packageName + "/" + PunchAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val globalOn = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        globalOn && enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    // ---------- 跳转 ----------

    /** 跳到本应用的通知设置 */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStart(context, intent)
    }

    /** 跳到无障碍设置 */
    fun openAccessibilitySettings(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 跳精确闹钟设置 */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** 请求忽略电池优化 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * 跳到 HyperOS 自启动管理页。
     *
     * 5 级 fallback（成功率从高到低）：
     *   1. 小米自启动管理 Activity（最直接）
     *   2. 应用权限编辑页（部分 HyperOS 版本把自启动并入这里）
     *   3. miui.intent.action.OP_AUTO_START 隐式 action
     *   4. 应用详情页（一定有）
     *   5. 应用列表页（最后的兜底）
     *
     * 每一级都先 queryIntentActivities 确认可解析，再 startActivity——
     * 直接 start 失败会抛 ActivityNotFoundException，在 MIUI 上还可能被
     * 安全中心自己拦一次，先 query 能省掉一轮异常。
     *
     * 注意：AutoStartManagementActivity **不接收定位 extra**；
     * extra_pkgname 属于 PermissionsEditorActivity（另一个页面）。
     */
    fun openAutoStartSettings(context: Context) {
        if (!isMiuiOrHyperOs()) {
            // 非小米设备：直接给应用详情页，别乱跳不存在的组件
            Log.i(TAG, "非 MIUI/HyperOS，跳应用详情页")
            openAppDetails(context)
            return
        }

        val candidates = listOf(
            // 1. 自启动管理
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
            // 2. 应用权限编辑（带包名 extra）
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
            ).putExtra("extra_pkgname", context.packageName),
            // 3. 隐式 action
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            // 4. 省电策略页
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
            ),
        )

        for ((index, intent) in candidates.withIndex()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(context, intent) && tryStart(context, intent)) {
                Log.i(TAG, "自启动管理页跳转成功（第 " + (index + 1) + " 级）")
                return
            }
        }

        Log.w(TAG, "自启动页全部失败，退到应用详情")
        openAppDetails(context)
    }

    /**
     * 尝试**真实检测**自启动状态。
     *
     * Android 官方没有任何 API 能读这个值，所以早先只能标成"无法检测"。
     * 但小米/红米上其实有两条可用的探查路径（都需要 root）：
     *
     * 1. **MIUI 系统设置数据库**：PowerKeeper 把自启动白名单存在
     *    `/data/data/com.miui.powerkeeper/databases/powerkeeper.db` 里，
     *    查 `whiteList` 表能看我们的包在不在。
     *    但直接读数据库有风险（PowerKeeper 会周期性重写），所以只读不写。
     *
     * 2. **应用权限记录**：`dumpsys appops` 里 MIUI 会记录
     *    `RUN_IN_BACKGROUND` / `BOOT_COMPLETED` 的授权状态。
     *
     * 两条都不保证 100% 准确（ROM 版本差异大），所以返回三态：
     * - ENABLED：确认已开启
     * - DISABLED：确认未开启
     * - UNKNOWN：查不出来（此时如实告知，而不是假装"没开"）
     */
    enum class AutostartState { ENABLED, DISABLED, UNKNOWN }

    fun detectAutostart(context: Context): AutostartState {
        val pkg = context.packageName

        // 路径 1：MIUI 自启动白名单（需要 root）
        if (RootShell.isAvailable()) {
            val fromDb = queryMiuiAutostartFromDb(pkg)
            if (fromDb != AutostartState.UNKNOWN) return fromDb

            // 路径 2：appops 里的自启动授权
            val fromOps = queryAutostartFromAppOps(pkg)
            if (fromOps != AutostartState.UNKNOWN) return fromOps
        }

        return AutostartState.UNKNOWN
    }

    /** 从 PowerKeeper 数据库读自启动白名单（只读，不改） */
    private fun queryMiuiAutostartFromDb(pkg: String): AutostartState {
        val out = RootShell.exec(
            "sqlite3 /data/data/com.miui.powerkeeper/databases/powerkeeper.db " +
                "'select pkgName from whiteList' 2>/dev/null",
            8_000,
        )
        if (out.isBlank() || out.contains("not found") || out.contains("Error")) {
            return AutostartState.UNKNOWN
        }
        return if (out.lineSequence().any { it.trim() == pkg }) {
            AutostartState.ENABLED
        } else {
            // 表里能读到内容但没有我们的包 —— 但不能直接断定"没开"，
            // 因为有些版本的白名单在别的表里。保守返回 UNKNOWN。
            AutostartState.UNKNOWN
        }
    }

    /** 从 appops 读后台启动授权 */
    private fun queryAutostartFromAppOps(pkg: String): AutostartState {
        val out = RootShell.exec("appops get " + pkg + " RUN_IN_BACKGROUND", 8_000)
        if (out.isBlank()) return AutostartState.UNKNOWN
        return when {
            out.contains("allow", ignoreCase = true) -> AutostartState.ENABLED
            out.contains("deny", ignoreCase = true) -> AutostartState.DISABLED
            else -> AutostartState.UNKNOWN
        }
    }

    /** 判断是否小米系 ROM */
    private fun isMiuiOrHyperOs(): Boolean {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val hasMiuiProp = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            val raw = get.invoke(null, "ro.miui.ui.version.name") as? String
            raw.isNullOrBlank().not()
        }.getOrDefault(false)
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            manufacturer.contains("xiaomi") ||
            hasMiuiProp
    }

    /** 组件/action 是否可解析 */
    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /** 跳到应用详情页 */
    /**
     * 跳到系统的蓝牙设置页。
     *
     * 用多级 fallback，与 [openAutoStartSettings] 同一套思路：
     * 先尝试直达蓝牙页，不行再退到应用详情页。
     */
    fun openBluetoothSettings(context: Context) {
        val candidates = listOf(
            android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null)),
        )
        for (intent in candidates) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    fun openOverlaySettings(context: Context) {
        val pkgUri = Uri.parse("package:" + context.packageName)
        val candidates = buildList {
            add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            if (isMiuiOrHyperOs()) {
                add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        )
                    ).putExtra("extra_pkgname", context.packageName)
                )
            }
            add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(context, intent) && tryStart(context, intent)) return
        }
        openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** 用 root 把应用加入 Doze 白名单 */
    fun addToBatteryWhitelistViaRoot(context: Context): Boolean {
        if (!RootShell.isAvailable()) return false
        val ok = RootShell.addToDeviceIdleWhitelist(context.packageName)
        if (ok) {
            Log.i(TAG, "已通过 root 加入 Doze 白名单")
        }
        return ok
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun safeStart(context: Context, intent: Intent, fallbackToDetails: Boolean = true) {
        if (tryStart(context, intent)) return
        if (fallbackToDetails) openAppDetails(context)
    }
}