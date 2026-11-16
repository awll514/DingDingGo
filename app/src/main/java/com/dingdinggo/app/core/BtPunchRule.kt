package com.dingdinggo.app.core

import com.dingdinggo.app.data.db.PunchKind
import java.util.concurrent.TimeUnit

/**
 * 蓝牙触发打卡的**唯一判定真值**。
 *
 * ## 为什么必须是一个纯对象
 *
 * 这个功能会真的去点钉钉的打卡按钮。点错了就产生一条无法撤销的
 * 迟到卡或早退卡 —— 那比"没打卡"严重得多（用户原话：
 * 「不打迟到卡不打早退卡」）。
 *
 * 所以这里的每一条边界都必须：
 * 1. 不碰 Context、不碰系统时钟、不读写任何存储；
 * 2. now / settings / 打卡状态全部由调用方注入；
 * 3. 被单元测试逐条钉死边界（见 BtPunchRuleTest）。
 *
 * 调用链上有两处会调用它，两处必须一致：
 * - [com.dingdinggo.app.ble.BleScanReceiver] 收到广播后先问它"该不该打"；
 * - [com.dingdinggo.app.exec.PunchEngine] 真正点击前再问一次（纵深防御）。
 *
 * ## 与 AOSP 闹钟链路的区别
 *
 * 闹钟链路的窗口是 [target-75s, target+55s]，那个下界是为了给"预热"
 * 留时间，属于技术性约束。蓝牙触发的语义完全不同：**检测到设备
 * 就等于人已经站在考勤机旁边了**，此时早到是完全合法且想要的记录。
 *
 * 因此蓝牙触发：
 * - **放宽下界**（早到窗口，默认 2 小时）——早到打卡是正常记录；
 * - **上界一个字都不放宽**（迟到停手点与闹钟链路完全相同）。
 *
 * 这正好对应用户的要求：早到没关系，但绝不打迟到卡；下班同理，
 * 只防早退，不防晚走。
 */
object BtPunchRule {

    /** 上班满 1 分钟算迟到（与 PunchEngine.LATE_GRACE_MS 同口径） */
    const val LATE_GRACE_MS = 60_000L

    /** 迟到边界前 5 秒停手，避免"决定打"到"点击生效"这段延迟跨过边界 */
    const val LATE_SAFETY_MARGIN_MS = 5_000L

    /** 下班卡最早可打时刻的余量：绝不在配置的下班时间之前打（早退比晚走严重） */
    const val CLOCK_OUT_SAFETY_MARGIN_MS = 2_000L

    /** 蓝牙触发的默认早到窗口：提前 2 小时到公司，仍然允许打上班卡 */
    const val DEFAULT_EARLY_WINDOW_MINUTES = 120

    /**
     * 早到窗口的合法范围（分钟）。
     *
     * 上限取 8 小时而不是 24 小时：窗口是"提前多久算正常上班"，
     * 不应该是"从昨天开始算"。若允许到 1440 分钟，下界会落到
     * **昨天** 09:30，于是今天凌晨 00:10 检测到考勤机也会判上班卡 ——
     * 不产生迟到记录，但会写出一条荒谬的"凌晨上班卡"。
     */
    val EARLY_WINDOW_RANGE = 0..(8 * 60)

    /**
     * 判定结论。
     *
     * @param action 该做什么
     * @param kind 要打的班次；[Action.SKIP_*] 时为 null
     * @param punchAtMillis 检测到设备的时刻（= 今日实际打卡时间，同步语义的落点）
     * @param configuredTargetMillis 该班次的**计划**时刻（红线判定的基准，不随时钟漂移）
     * @param message 给用户看的中文说明（进通知 / 日志）
     */
    data class Decision(
        val action: Action,
        val kind: PunchKind?,
        val punchAtMillis: Long,
        val configuredTargetMillis: Long,
        val message: String,
    ) {
        /** 是否应该真的去执行打卡 */
        val shouldPunch: Boolean
            get() = action == Action.PUNCH_CLOCK_IN || action == Action.PUNCH_CLOCK_OUT
    }

    enum class Action {
        /** 打上班卡 */
        PUNCH_CLOCK_IN,

        /** 打下班卡 */
        PUNCH_CLOCK_OUT,

        /** 功能开关关闭 */
        SKIP_DISABLED,

        /** 今天不是生效日 */
        SKIP_INACTIVE_DAY,

        /** 今天被用户手动跳过 */
        SKIP_TODAY_SKIPPED,

        /** 今日两班都已完成 */
        SKIP_ALREADY_DONE,

        /** 还没进入早到窗口（太早了，静默等待，不打扰用户） */
        SKIP_TOO_EARLY,

        /** 已过迟到边界 → 今日上班卡作废，绝不补打（防迟到卡） */
        SKIP_TOO_LATE,

        /** 还没到下班时间 → 绝不提前打（防早退卡） */
        SKIP_NOT_YET_CLOCK_OUT,
    }

    // ---------- 三条边界：唯一实现 ----------
    //
    // 这三个函数是全域唯一的边界表达式。
    // PunchEngine 的闸门与 BleScanReceiver 的预判都只能调它们，
    // 不允许任何地方再手写一遍算式 —— 历史上正是因为
    // "同一个规则写两份"出过「第一次放行、复查拒绝」的漏卡事故。

    /**
     * 上班卡可打窗口的**下界**（蓝牙触发版）。
     *
     * @param earlyWindowMs 早到窗口。
     *        **0 表示完全不放宽**，即下界就是 targetAt 本身 ——
     *        此时只有在准点及之后才允许打上班卡（早到会被判为 TooEarly）。
     *
     *        注意这**不是**闹钟链路那个 "target-75s"：
     *        那个 75 秒是给"点亮屏幕→解锁→拉钉钉"留的准备时间，
     *        属于技术性约束，与"允许多早到"是两回事。蓝牙触发不需要
     *        预热（人已经站在机器旁），所以它的下界语义是纯粹的
     *        "允许多早"，0 就该是 0。
     */
    fun clockInEarliestAt(targetAt: Long, earlyWindowMs: Long): Long =
        targetAt - earlyWindowMs.coerceAtLeast(0L)

    /** 上班卡可打窗口的**上界**（过此即停手，避免迟到卡）。与闹钟链路完全一致 */
    fun clockInMustStopAt(targetAt: Long): Long =
        targetAt + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS

    /** 下班卡最早可打时刻。与闹钟链路完全一致，绝不提前 */
    fun clockOutEarliestAt(targetAt: Long): Long =
        targetAt + CLOCK_OUT_SAFETY_MARGIN_MS

    /** 早到窗口毫秒数（分钟配置 → 毫秒，带范围钳制） */
    fun earlyWindowMsOf(minutes: Int): Long =
        TimeUnit.MINUTES.toMillis(minutes.coerceIn(EARLY_WINDOW_RANGE).toLong())

    /**
     * 现在是否处在"需要保持 BLE 扫描活跃"的时间窗内。
     *
     * ## 为什么需要这个判定（红线权衡）
     *
     * 真机实测：蓝牙栈会在数十秒内停掉 PendingIntent 扫描，必须持续续期
     * 才能保持在线。而续期周期已压到 25 秒 —— 若 7×24 全天候续期，
     * 就是每天约 3456 次唤醒。
     *
     * 这直接与 AGENTS.md 的「后台零消耗」红线冲突：
     * "没运行时在后台的时候不要消耗资源"。
     *
     * ## 解法：只在"可能真的要用"的时间段续期
     *
     * 蓝牙触发的价值在于：人走到考勤机旁时自动打卡。
     * 而它**只可能**在两个时刻附近发生：
     *   · 上班卡：[早到窗口下界, 迟到停手点]；
     *   · 下班卡：target + 2s 之后（理论上限设到当日结束）。
     *
     * 这两个窗口之外，扫描**扫到了也不能做什么**（闸门必然拒绝）。
     * 所以窗外完全可以停掉续期，省下整夜和整个白天的空转。
     *
     * 窗口边界各留 [PAD_MS] 余量：续期本身有延迟，
     * 卡在窗口边缘才开始续期会错过开头几秒。
     *
     * @return true 表示现在应当保持扫描活跃
     */
    fun shouldKeepScanAlive(
        nowMillis: Long,
        settings: ScheduleSettings,
        earlyWindowMinutes: Int,
        isActiveDay: Boolean,
        clockInDoneToday: Boolean,
        clockOutDoneToday: Boolean,
    ): Boolean {
        // 非生效日、两班都已完成 → 今天不会再有用到扫描的机会
        if (!isActiveDay) return false
        if (clockInDoneToday && clockOutDoneToday) return false

        val dayStart = PunchSchedule.startOfDay(nowMillis)
        val clockInTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockInMinute.toLong())
        val clockOutTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockOutMinute.toLong())
        val pad = PAD_MS

        // 上班卡窗口
        if (!clockInDoneToday) {
            val start = clockInEarliestAt(clockInTarget, earlyWindowMsOf(earlyWindowMinutes)) - pad
            val end = clockInMustStopAt(clockInTarget) + pad
            if (nowMillis in start..end) return true
        }

        // 下班卡窗口：target+2s 起到当日结束。
        // 不在已完成后继续到 23:59 是没意义的（already-done 会让闸门直接跳过）
        if (!clockOutDoneToday) {
            val start = clockOutEarliestAt(clockOutTarget) - pad
            val end = PunchSchedule.startOfDay(clockOutTarget) +
                TimeUnit.DAYS.toMillis(1) - 1
            if (nowMillis in start..end) return true
        }

        return false
    }

    /**
     * 时间窗边界余量。
     *
     * 续期闹钟本身有几十毫秒到数秒的投递抖动，窗口外沿留 5 分钟足够
     * 覆盖，又不会显著扩大耗电区间。
     */
    private const val PAD_MS = 5 * 60 * 1000L

    /**
     * 核心判定：检测到考勤机蓝牙时，现在到底该做什么。
     *
     * 判定顺序是**刻意**的，不能调换：
     *
     * 1. 开关 / 生效日 / 跳过日 —— 先尊重用户的显式意愿；
     * 2. 上班卡：只有在 [早到窗口下界, 迟到停手点] 之间才允许；
     *    过了迟到停手点就**当场作废今日上班卡**，绝不补打；
     * 3. 下班卡：只有在配置的下班时间 +2 秒之后才允许；
     *    在此之前一律拒绝（这是早退红线）；
     * 4. 都完成 → 什么都不做。
     *
     * @param nowMillis 检测到设备的时刻
     * @param settings 排班设置（配置的上下班时间）
     * @param clockInDoneToday 今日上班卡是否已打成功
     * @param clockOutDoneToday 今日下班卡是否已打成功
     * @param isActiveDay 今天是否生效日
     * @param skippedToday 今天是否被用户跳过
     * @param enabled 自动打卡总开关
     * @param earlyWindowMinutes 早到窗口（分钟）
     */
    fun decide(
        nowMillis: Long,
        settings: ScheduleSettings,
        clockInDoneToday: Boolean,
        clockOutDoneToday: Boolean,
        isActiveDay: Boolean,
        skippedToday: Boolean,
        enabled: Boolean,
        earlyWindowMinutes: Int = DEFAULT_EARLY_WINDOW_MINUTES,
    ): Decision {
        val dayStart = PunchSchedule.startOfDay(nowMillis)

        // 计划时刻是**今天**的配置时间。它不随检测时刻漂移 ——
        // 一旦漂移，红线就失去意义（target=now 时任何边界都恒为通过）。
        val clockInTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockInMinute.toLong())
        val clockOutTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockOutMinute.toLong())

        if (!enabled) {
            return Decision(
                Action.SKIP_DISABLED, null, nowMillis, 0L,
                "自动打卡已关闭，忽略蓝牙触发",
            )
        }
        if (!isActiveDay) {
            return Decision(
                Action.SKIP_INACTIVE_DAY, null, nowMillis, 0L,
                "今天不是生效日，忽略蓝牙触发",
            )
        }
        if (skippedToday) {
            return Decision(
                Action.SKIP_TODAY_SKIPPED, null, nowMillis, 0L,
                "今天已被手动跳过，忽略蓝牙触发",
            )
        }

        // ---------- 上班卡 ----------
        if (!clockInDoneToday) {
            val stopAt = clockInMustStopAt(clockInTarget)
            val earliest = clockInEarliestAt(clockInTarget, earlyWindowMsOf(earlyWindowMinutes))

            if (nowMillis <= stopAt) {
                // 落在窗口内 → 可以打。早到也算合法（蓝牙语义：人在机器旁）
                if (nowMillis >= earliest) {
                    val earlyText = if (nowMillis < clockInTarget) {
                        "（比计划早 " + ((clockInTarget - nowMillis) / 60_000L) + " 分钟）"
                    } else {
                        ""
                    }
                    return Decision(
                        Action.PUNCH_CLOCK_IN,
                        PunchKind.CLOCK_IN,
                        nowMillis,
                        clockInTarget,
                        "检测到考勤机，执行上班打卡" + earlyText,
                    )
                }
                // 太早：还没进早到窗口。静默等待，不发通知（打扰用户）
                return Decision(
                    Action.SKIP_TOO_EARLY, null, nowMillis, clockInTarget,
                    "距可打卡时间还早，已等待",
                )
            }

            // 【红线】已过迟到停手点 → 今日上班卡作废，绝不补打。
            // 注意这里不 return：如果现在是晚上，且下班卡也没打，
            // 那么打下班卡仍然是对的（用户要求的是"不打迟到卡"，
            // 不是"迟到就一整天什么都不打"）。
            if (clockOutDoneToday) {
                return Decision(
                    Action.SKIP_TOO_LATE, null, nowMillis, clockInTarget,
                    "已过迟到边界，为避免迟到记录今日上班卡已放弃",
                )
            }
            if (nowMillis >= clockOutEarliestAt(clockOutTarget)) {
                return Decision(
                    Action.PUNCH_CLOCK_OUT,
                    PunchKind.CLOCK_OUT,
                    nowMillis,
                    clockOutTarget,
                    "检测到考勤机，执行下班打卡（今日上班卡已过迟到边界，未补打）",
                )
            }
            return Decision(
                Action.SKIP_TOO_LATE, null, nowMillis, clockInTarget,
                "已过迟到边界，为避免迟到记录今日上班卡已放弃",
            )
        }

        // ---------- 下班卡 ----------
        if (!clockOutDoneToday) {
            val earliestOut = clockOutEarliestAt(clockOutTarget)
            if (nowMillis >= earliestOut) {
                return Decision(
                    Action.PUNCH_CLOCK_OUT,
                    PunchKind.CLOCK_OUT,
                    nowMillis,
                    clockOutTarget,
                    "检测到考勤机，执行下班打卡",
                )
            }
            // 【红线】还没到配置的下班时间 → 绝不提前打
            val remainMin = (earliestOut - nowMillis) / 60_000L
            return Decision(
                Action.SKIP_NOT_YET_CLOCK_OUT, null, nowMillis, clockOutTarget,
                "距下班时间还有 " + PunchSchedule.formatRemainMinutes(remainMin) + "，已阻止提前打下班卡",
            )
        }

        // ---------- 都完成 ----------
        return Decision(
            Action.SKIP_ALREADY_DONE, null, nowMillis, 0L,
            "今日上下班卡均已完成",
        )
    }
}
