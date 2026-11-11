package com.redmi.autoattend.core

import com.redmi.autoattend.data.db.PunchKind
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 下一次打卡的时间点。
 *
 * @param triggerAtMillis 闹钟触发时刻（含预热提前量）
 * @param targetAtMillis 用户设定的目标打卡时刻（不含提前量）
 * @param kind 上班还是下班
 * @param isOverride 是否来自"单日临时覆盖"
 */
data class NextPunchSlot(
    val triggerAtMillis: Long,
    val targetAtMillis: Long,
    val kind: PunchKind,
    val isOverride: Boolean = false,
)

/**
 * 打卡时间计算器。
 *
 * 纯函数式，不依赖 Android Framework，方便单元测试。
 *
 * 调度语义：
 * - 每天上班/下班各一次，只在 activeDays 内生效；
 * - 预热闹钟提前 [PRE_WARM_SECONDS] 秒触发，用来点亮屏幕、解锁、启动钉钉；
 * - 目标时间后 [RETRY_WINDOW_SECONDS] 秒内仍可补执行一次。
 */
object PunchSchedule {

    /** 预热闹钟提前量：75 秒（需求 F3.1 明确要求） */
    const val PRE_WARM_SECONDS = 75L

    /** 目标时间后的补执行窗口：120 秒（需求 F5 明确要求） */
    const val RETRY_WINDOW_SECONDS = 120L

    /** 单日临时覆盖：某天的某个类型改成指定时间；执行一次后自动清除 */
    data class Override(val dateKey: Int, val kind: PunchKind, val minuteOfDay: Int)

    /**
     * 一个"已经完成"的打卡槽位。
     *
     * ## 为什么必须有这个（用户反馈的核心问题）
     *
     * 用户原话：「手动打卡成功后必须替换掉自动打卡的时间节点」。
     *
     * 之前 [nextSlot] 只看时间和星期，完全不看"今天这一班是不是已经打过了"。
     * 于是手动打卡成功后，到点闹钟照样会再打一次 ——
     * 而重复打卡在考勤系统里可能是异常记录。
     *
     * 现在把"今日已完成的 (日期, 班次)"传进来，命中就跳过，
     * 下一次打卡自动顺延到下一班。
     */
    data class CompletedSlot(val dateKey: Int, val kind: PunchKind)

    /**
     * 计算当前时刻之后的下一个打卡点。
     *
     * 会同时考虑今天剩余的班次和接下来 7 天内的班次，取最早的一个。
     *
     * @param nowMillis 当前时间
     * @param settings 排班设置
     * @param overrides 单日临时覆盖列表
     * @param includeRetryWindow 是否把"已过目标时间但仍在补执行窗口内"的点也算作下一个
     */
    fun nextSlot(
        nowMillis: Long,
        settings: ScheduleSettings,
        overrides: List<Override> = emptyList(),
        includeRetryWindow: Boolean = true,
        skippedDateKeys: Set<Int> = emptySet(),
        completedSlots: Set<CompletedSlot> = emptySet(),
    ): NextPunchSlot? {
        if (settings.activeDays.isEmpty()) return null

        var best: NextPunchSlot? = null

        // 检查今天起 8 天，覆盖跨周场景
        for (dayOffset in 0..7) {
            val dayStart = startOfDay(nowMillis, dayOffset)
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val dateKey = dateKey(dayStart)

            // 【跳过】整日被跳过时直接略过这一天，
            // 这样"下一次打卡"会自动推到下一个生效日，
            // 界面上显示的日期也跟着变（今天 → 明天 → 周五…）。
            if (dateKey in skippedDateKeys) continue

            for (kind in listOf(PunchKind.CLOCK_IN, PunchKind.CLOCK_OUT)) {
                val override = overrides.firstOrNull { it.dateKey == dateKey && it.kind == kind }

                // 覆盖日即使是休息日也要执行
                val active = override != null || dayOfWeek in settings.activeDays
                if (!active) continue

                // 【已完成】这一班已经打过了（手动或自动成功），跳过。
                //
                // 判定粒度是 (日期, 班次)，不是笼统的"今天打过卡"：
                // 早上打了上班卡，不能影响下午的下班卡。
                if (CompletedSlot(dateKey, kind) in completedSlots) continue

                val minuteOfDay = override?.minuteOfDay ?: when (kind) {
                    PunchKind.CLOCK_IN -> settings.clockInMinute
                    PunchKind.CLOCK_OUT -> settings.clockOutMinute
                    PunchKind.MANUAL -> continue
                }

                val targetAt = dayStart + TimeUnit.MINUTES.toMillis(minuteOfDay.toLong())
                val triggerAt = targetAt - TimeUnit.SECONDS.toMillis(PRE_WARM_SECONDS)

                // 已经错过（超过补执行窗口）就跳过
                val retryDeadline = targetAt + TimeUnit.SECONDS.toMillis(RETRY_WINDOW_SECONDS)
                if (nowMillis > retryDeadline) continue

                // 若触发时刻已过但仍在窗口内，允许立刻补触发
                val effectiveTrigger = if (nowMillis >= triggerAt) {
                    if (!includeRetryWindow) continue
                    nowMillis
                } else {
                    triggerAt
                }

                val slot = NextPunchSlot(
                    triggerAtMillis = effectiveTrigger,
                    targetAtMillis = targetAt,
                    kind = kind,
                    isOverride = override != null,
                )

                if (best == null || slot.targetAtMillis < best!!.targetAtMillis) {
                    best = slot
                }
            }
        }

        return best
    }

    /**
     * 从"今日已完成记录"构造已完成槽位集合。
     *
     * 与数据库解耦：调用方只需传入 (班次, 完成时间戳) 的列表，
     * 本函数判断它们是否属于**今天**，从而得出今天哪些班已经打完。
     *
     * 之所以要求传时间戳而不是布尔值：
     * 凌晨 0 点前后，昨天的下班卡记录不能算作今天的。
     *
     * @param nowMillis 当前时刻，用于确定"今天"的范围
     * @param done 已完成记录：班次 → 完成时刻（毫秒）
     */
    fun completedSlotsForToday(
        nowMillis: Long,
        done: List<Pair<PunchKind, Long>>,
    ): Set<CompletedSlot> {
        val todayStart = startOfDay(nowMillis, 0)
        val tomorrowStart = startOfDay(nowMillis, 1)
        return done.mapNotNull { (kind, at) ->
            when {
                // 手动打卡记在 CLOCK_IN/CLOCK_OUT 上，MANUAL 不作为槽位
                kind == PunchKind.MANUAL -> null
                at in todayStart until tomorrowStart -> CompletedSlot(dateKey(todayStart), kind)
                else -> null
            }
        }.toSet()
    }

    /** 今天是否是需要打卡的生效日 */
    fun isActiveDay(nowMillis: Long, settings: ScheduleSettings): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return cal.get(Calendar.DAY_OF_WEEK) in settings.activeDays
    }

    /** 某天的 00:00:00.000 时间戳 */
    fun startOfDay(nowMillis: Long, dayOffset: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 日期键，形如 20261110，用于匹配单日覆盖 */
    fun dateKey(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    /** 把分钟数格式化成 HH:mm */
    fun formatMinute(minuteOfDay: Int): String {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 把时间戳格式化成 HH:mm:ss */
    fun formatTime(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    }

    /**
     * 把"下一次打卡是什么时候"渲染成人类友好的标签。
     *
     * 规则（对齐你的要求）：
     * - **今天** / **明天** → 直接写"今天"、"明天"；
     * - 2~6 天后 → 写"周几"（如"周五"）；
     * - 7 天及以上 → 写「M月d日」+ 周几；
     * - **不在任何生效日里** → 表示连续休假，返回休假文案。
     *
     * 这个函数是纯的，不碰系统时间，`nowMillis` 由调用方注入，
     * 所以可以被单元测试精确覆盖跨天、跨周、跨月的边界。
     */
    fun dayLabel(nowMillis: Long, targetAtMillis: Long, activeDays: Set<Int>): String {
        val todayStart = startOfDay(nowMillis, 0)
        val targetStart = startOfDay(targetAtMillis, 0)

        // 相差多少个自然日（用毫秒差除以一天，再加半小时容差抵消夏令时/时区抖动）
        val diffDays = ((targetStart - todayStart) + TimeUnit.HOURS.toMillis(12)) /
            TimeUnit.DAYS.toMillis(1)

        val cal = Calendar.getInstance().apply { timeInMillis = targetStart }
        val dow = cal.get(Calendar.DAY_OF_WEEK)

        return when {
            diffDays <= 0L -> "今天"
            diffDays == 1L -> "明天"
            diffDays in 2L..6L -> weekdayName(dow)
            else -> {
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                month.toString() + "月" + day + "日 " + weekdayName(dow)
            }
        }
    }

    /**
     * 连续休假文案。
     *
     * 当 settings.activeDays 为空，或未来 8 天内没有任何生效日时使用。
     * 语气是"祝福"而不是"错误"，因为这不是故障状态 ——
     * 用户可能确实在休假。
     */
    fun vacationLabel(activeDays: Set<Int>): String {
        if (activeDays.isEmpty()) return "已关闭全部生效日 · 好好休息 🌴"
        return "接下来都是休息日 · 祝你休假愉快 🌴"
    }

    /**
     * 直接按 yyyyMMdd 计算"今天/明天/周几/完整日期"。
     *
     * 与 [dayLabel] 的区别：那个接收毫秒时间戳，这个接收日期键。
     * 用于"被跳过的日期"这种只有 key 没有时刻的场景。
     */
    fun dayLabelForKey(nowMillis: Long, dateKey: Int): String {
        if (dateKey == 0) return ""
        val todayKey = dateKey(nowMillis)
        // 把 yyyyMMdd 还原成 Calendar 便于取周几
        val cal = Calendar.getInstance().apply {
            set(dateKey / 10000, (dateKey / 100) % 100 - 1, dateKey % 100, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)

        // 用日历天数差，而不是 86400000 整除（跨夏令时会差一天）
        val days = daysBetween(nowMillis, cal.timeInMillis)

        return when {
            days <= 0 -> "今天"
            days == 1L -> "明天"
            days in 2L..6L -> weekdayName(dow)
            else -> {
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                month.toString() + "月" + day + "日 " + weekdayName(dow)
            }
        }.let { if (days > 0 || days == 0L) it else "今天" }
    }

    /** 两个时刻之间相差的自然日数（忽略时分秒） */
    private fun daysBetween(fromMillis: Long, toMillis: Long): Long {
        val a = startOfDay(fromMillis, 0)
        val b = startOfDay(toMillis, 0)
        return Math.round((b - a).toDouble() / TimeUnit.DAYS.toMillis(1).toDouble())
    }

    /** Calendar.DAY_OF_WEEK(1..7) → "周日".."周六" */
    private fun weekdayName(dow: Int): String = when (dow) {
        Calendar.SUNDAY -> "周日"
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> ""
    }
}