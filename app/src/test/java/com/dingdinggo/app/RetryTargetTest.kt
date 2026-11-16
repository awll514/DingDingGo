package com.dingdinggo.app

import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.core.ScheduleSettings
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.exec.PunchEngine
import com.dingdinggo.app.exec.PunchNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * P0-2 回归防线：失败通知「立即重试」的目标时刻。
 *
 * ## 这个 bug 的形态
 *
 * 失败通知里的「立即重试」按钮曾把 `EXTRA_TARGET_AT` 写成
 * `System.currentTimeMillis()`。而**安全闸门的判定基准就是 targetAt**：
 *
 *   · 上班卡上界 `clockInMustStopAt = target + 55s`
 *   · 下班卡下界 `clockOutEarliestAt = target + 2s`（**且没有上界**）
 *
 * 一旦基准变成 now，两处闸门都退化成"相对当前时刻"，恒为通过。后果：
 *   · 刚被正确拒绝的迟到卡 → 点一次重试就**打出一张真实迟到记录**；
 *   · 下班卡更严重 → **任何时刻**点重试都能打出一张当日下班卡。
 *
 * ## 这个测试在防什么
 *
 * 防止有人图省事又把 `now` 写回去。核心断言：
 * **重试目标时刻必须只由"今天的计划时刻"决定，与当前时刻完全无关**。
 *
 * 有趣的是同一文件的另一个函数（蓝牙跳过通知）当年就写对了，
 * 还留了注释"刻意不用 now —— 与失败通知的同款坑"，结果失败通知那处漏改。
 * 现在两者共用 [PunchNotifier.plannedTargetAt]，不可能再分叉。
 */
class RetryTargetTest {

    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,   // 09:30
        clockOutMinute = 18 * 60 + 30, // 18:30
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    /** 取一个确定的"当天某时刻"，用于构造 now */
    private fun at(hour: Int, minute: Int): Long {
        val dayStart = PunchSchedule.startOfDay(System.currentTimeMillis())
        return dayStart + (hour * 60 + minute) * 60_000L
    }

    // ============ 核心：结果必须是计划时刻 ============

    @Test
    fun clockOutRetryUsesPlannedTime() {
        val planed = PunchNotifier.plannedTargetAt(at(20, 0), PunchKind.CLOCK_OUT, settings)
        assertEquals(
            "下班卡重试必须落在今天 18:30，而不是当前时刻",
            at(18, 30),
            planed,
        )
    }

    @Test
    fun clockInRetryUsesPlannedTime() {
        val planned = PunchNotifier.plannedTargetAt(at(14, 0), PunchKind.CLOCK_IN, settings)
        assertEquals(
            "上班卡重试必须落在今天 09:30",
            at(9, 30),
            planned,
        )
    }

    // ============ 核心：与 now 无关 ============

    /**
     * 最关键的一条：同一天内，无论什么时候点重试，结果都一样。
     *
     * 如果实现里混进了 `System.currentTimeMillis()`，这条立刻失败。
     */
    @Test
    fun plannedTimeIsIndependentOfWhenRetryIsClicked() {
        val times = listOf(
            at(0, 1) to "凌晨 00:01",
            at(9, 30) to "上班时刻",
            at(13, 0) to "下午",
            at(18, 30) to "下班时刻",
            at(23, 59) to "深夜 23:59",
        )
        val results = times.map { (now, label) ->
            label to PunchNotifier.plannedTargetAt(now, PunchKind.CLOCK_OUT, settings)
        }
        val expected = at(18, 30)
        for ((label, actual) in results) {
            assertEquals(
                "在 " + label + " 点重试，目标时刻都必须是今天 18:30",
                expected,
                actual,
            )
        }
    }

    /** 显式反证：结果**不能**等于 now（除非恰好是那一刻） */
    @Test
    fun plannedTimeIsNotNow() {
        val now = at(20, 15)
        val planned = PunchNotifier.plannedTargetAt(now, PunchKind.CLOCK_OUT, settings)
        assertNotEquals(
            "目标时刻绝不能是当前时刻 —— 那会让闸门基准漂移、红线失效",
            now,
            planned,
        )
    }

    // ============ 与安全闸门的联动：这正是 bug 的后果 ============

    /**
     * 复现 bug 的后果：如果用 now 当基准，下班卡闸门恒放行。
     *
     * 这条测试把"为什么 targetAt 必须正确"直接演示出来 ——
     * 它不是风格问题，是真的会打错卡。
     */
    /**
     * 下班卡的真正危险：下界被抹平后，**提前**也能打。
     *
     * 下班卡的闸门只有下界 `target + 2s`、没有上界（晚走应当允许）。
     * 所以必须用"在下班之前点重试"来暴露问题：
     *   · 正确实现（基准 18:30）：13:00 点重试 → 太早 → 被拒；
     *   · bug 版本（基准 now）：13:00 点重试 → 基准变成 13:00 → 放行。
     *
     * 后者就是"打出一张早退卡"的直接成因。
     */
    @Test
    fun usingNowAsTargetWouldAllowEarlyClockOut() {
        val early = at(13, 0) // 下午 13:00，还没到 18:30

        // 正确实现：基准 = 计划时刻 18:30 → 13:00 太早，必须拒绝
        val correctTarget = PunchNotifier.plannedTargetAt(early, PunchKind.CLOCK_OUT, settings)
        val verdict = PunchEngine.timeWindowVerdict(PunchKind.CLOCK_OUT, early, correctTarget)
        assertTrue(
            "以计划时刻 18:30 为基准，13:00 打下班卡必须被拒绝（防早退），当前=" + verdict,
            verdict is PunchEngine.TimeWindowVerdict.Early,
        )

        // bug 版本：基准 = now（13:00）→ 13:00 >= 13:00+2s? 否...
        // 注意 now 作为基准时下界是 now+2s，紧接着的两秒后就会放行。
        // 这里用"点重试后 3 秒"模拟真实时序（用户点按钮到服务执行）。
        val threeSecondsLater = early + 3_000L
        assertNull(
            "以 now 为基准时，3 秒后闸门就放行了 —— 这就是早退卡的成因",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_OUT, threeSecondsLater, early),
        )
    }

    /**
     * 上班卡的情形更能说明问题：以 now 为基准时，**凌晨**也能打上班卡。
     */
    @Test
    fun usingNowAsTargetWouldAllowClockInAtMidnight() {
        val midnight = at(0, 10) // 凌晨 00:10

        // 正确实现：基准是 09:30，凌晨 00:10 显然太早 → 被拒
        val correctTarget = PunchNotifier.plannedTargetAt(midnight, PunchKind.CLOCK_IN, settings)
        val verdict = PunchEngine.timeWindowVerdict(PunchKind.CLOCK_IN, midnight, correctTarget)
        assertTrue(
            "以计划时刻 09:30 为基准，凌晨 00:10 必须被判为太早（当前=" + verdict + "）",
            verdict is PunchEngine.TimeWindowVerdict.TooEarlyForClockIn,
        )

        // bug 版本：基准 = now（00:10）→ 落在 [target-75s, target+55s] 内 → 放行
        assertNull(
            "以 now 为基准时凌晨会放行 —— 这正是历史上「晚上 21:38 打出上班卡」的成因",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_IN, midnight, midnight),
        )
    }

    // ============ 天边界 ============

    /** 计划时刻必须落在"传入 now 所在的那一天"，而不是固定某天 */
    @Test
    fun plannedTimeBelongsToTheDayOfNow() {
        val todayStart = PunchSchedule.startOfDay(System.currentTimeMillis())
        val planned = PunchNotifier.plannedTargetAt(todayStart + 60_000L, PunchKind.CLOCK_IN, settings)
        assertEquals(
            "计划时刻应当属于 now 所在的那一天",
            PunchSchedule.dateKey(todayStart),
            PunchSchedule.dateKey(planned),
        )
    }
}
