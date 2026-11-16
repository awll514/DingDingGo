package com.dingdinggo.app

import com.dingdinggo.app.core.BtPunchRule
import com.dingdinggo.app.core.BtPunchRule.Action
import com.dingdinggo.app.core.ScheduleSettings
import com.dingdinggo.app.data.db.PunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 蓝牙触发的红线测试。
 *
 * ## 这组测试保护什么
 *
 * 用户对蓝牙功能的唯一硬要求是：
 *   「不打迟到卡，不打早退卡」。
 *
 * 而蓝牙触发又必须支持「检测时刻 = 今日实际上班时间」的同步语义，
 * 这意味着**判定基准必须与记录时间分离** —— 一旦混在一起，
 * 红线会因为基准漂移而失效（手动打卡历史上真的打出过深夜"上班卡"）。
 *
 * 所以这里把三条边界逐个钉死：
 *   1. 上班卡上界：晚于 target+55s 一律不打；
 *   2. 下班卡下界：早于 target+2s 一律不打；
 *   3. 早到窗口：只影响上班卡下界，绝不影响上两条。
 *
 * 另有一组"不该打扰用户"的用例，保证常态不产生噪音通知。
 */
class BtPunchRuleTest {

    /** 2026-11-13 是星期五（工作日），用固定日期让测试与运行时刻无关 */
    private fun dayStart(): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 13, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        dayStart() + TimeUnit.HOURS.toMillis(hour.toLong()) +
            TimeUnit.MINUTES.toMillis(minute.toLong()) +
            TimeUnit.SECONDS.toMillis(second.toLong())

    /** 上班 09:30 / 下班 18:30，与真机配置一致 */
    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,
        clockOutMinute = 18 * 60 + 30,
        activeDays = setOf(2, 3, 4, 5, 6, 7),
    )

    private fun decide(
        now: Long,
        clockInDone: Boolean = false,
        clockOutDone: Boolean = false,
        isActiveDay: Boolean = true,
        skipped: Boolean = false,
        enabled: Boolean = true,
        earlyMinutes: Int = BtPunchRule.DEFAULT_EARLY_WINDOW_MINUTES,
    ) = BtPunchRule.decide(
        nowMillis = now,
        settings = settings,
        clockInDoneToday = clockInDone,
        clockOutDoneToday = clockOutDone,
        isActiveDay = isActiveDay,
        skippedToday = skipped,
        enabled = enabled,
        earlyWindowMinutes = earlyMinutes,
    )

    // ==================== 上班卡：早到窗口 ====================

    @Test
    fun `提前两小时到公司 允许打上班卡`() {
        // 09:30 - 2h = 07:30。默认早到窗口正好 2 小时，边界应放行。
        val d = decide(at(7, 30))
        assertEquals(Action.PUNCH_CLOCK_IN, d.action)
        assertTrue(d.shouldPunch)
        assertEquals(PunchKind.CLOCK_IN, d.kind)
    }

    @Test
    fun `比早到窗口还早一分钟 静默等待`() {
        // 07:29 → 还没进窗口
        val d = decide(at(7, 29))
        assertEquals(Action.SKIP_TOO_EARLY, d.action)
        assertFalse(d.shouldPunch)
    }

    @Test
    fun `准点到 允许打上班卡`() {
        val d = decide(at(9, 30))
        assertEquals(Action.PUNCH_CLOCK_IN, d.action)
    }

    // ==================== 上班卡：迟到红线（核心） ====================

    @Test
    fun `上班后55秒 仍允许打卡`() {
        // 上界 = target + 55s（60s 宽限 - 5s 安全余量）
        val d = decide(at(9, 30, 55))
        assertEquals(Action.PUNCH_CLOCK_IN, d.action)
    }

    @Test
    fun `上班后56秒 拒绝打卡 绝不打迟到卡`() {
        val d = decide(at(9, 30, 56))
        assertEquals(Action.SKIP_TOO_LATE, d.action)
        assertFalse("红线：绝不打迟到卡", d.shouldPunch)
    }

    @Test
    fun `上班后10分钟 拒绝打卡`() {
        val d = decide(at(9, 40))
        assertEquals(Action.SKIP_TOO_LATE, d.action)
    }

    @Test
    fun `早到窗口放宽不影响迟到上界`() {
        // 即使把早到窗口放大到 8 小时，56 秒后依然必须拒绝
        val d = decide(at(9, 30, 56), earlyMinutes = 480)
        assertEquals(Action.SKIP_TOO_LATE, d.action)
    }

    // ==================== 下班卡：早退红线（核心） ====================

    @Test
    fun `下班前一分钟 拒绝打卡 绝不打早退卡`() {
        val d = decide(at(18, 29), clockInDone = true)
        assertEquals(Action.SKIP_NOT_YET_CLOCK_OUT, d.action)
        assertFalse("红线：绝不打早退卡", d.shouldPunch)
    }

    @Test
    fun `下班前1秒 拒绝打卡`() {
        val d = decide(at(18, 29, 59), clockInDone = true)
        assertEquals(Action.SKIP_NOT_YET_CLOCK_OUT, d.action)
    }

    @Test
    fun `下班后2秒 允许打卡`() {
        // 下界 = target + 2s
        val d = decide(at(18, 30, 2), clockInDone = true)
        assertEquals(Action.PUNCH_CLOCK_OUT, d.action)
        assertEquals(PunchKind.CLOCK_OUT, d.kind)
    }

    @Test
    fun `下班后2小时 仍允许打下班卡`() {
        // 下班只防早退，不防晚走
        val d = decide(at(20, 30), clockInDone = true)
        assertEquals(Action.PUNCH_CLOCK_OUT, d.action)
    }

    // ==================== 组合场景 ====================

    @Test
    fun `上班卡已打 晚上到公司 直接打下班卡`() {
        val d = decide(at(19, 0), clockInDone = true)
        assertEquals(Action.PUNCH_CLOCK_OUT, d.action)
    }

    @Test
    fun `上班迟到 但已是晚上 打下班卡而不是放弃整天`() {
        // 早上没赶上，晚上到公司 —— 用户要的是"不打迟到卡"，
        // 不是"迟到就一整天什么都不打"。
        val d = decide(at(19, 0), clockInDone = false, clockOutDone = false)
        assertEquals(Action.PUNCH_CLOCK_OUT, d.action)
    }

    @Test
    fun `上班迟到 且还没到下班时间 什么都不打`() {
        val d = decide(at(10, 0), clockInDone = false, clockOutDone = false)
        assertEquals(Action.SKIP_TOO_LATE, d.action)
        assertFalse(d.shouldPunch)
    }

    @Test
    fun `两班都已完成 不再触发`() {
        val d = decide(at(19, 0), clockInDone = true, clockOutDone = true)
        assertEquals(Action.SKIP_ALREADY_DONE, d.action)
    }

    // ==================== 开关与日期 ====================

    @Test
    fun `总开关关闭 一律不触发`() {
        val d = decide(at(9, 30), enabled = false)
        assertEquals(Action.SKIP_DISABLED, d.action)
    }

    @Test
    fun `非生效日 不触发`() {
        val d = decide(at(9, 30), isActiveDay = false)
        assertEquals(Action.SKIP_INACTIVE_DAY, d.action)
    }

    @Test
    fun `今天被跳过 不触发`() {
        val d = decide(at(9, 30), skipped = true)
        assertEquals(Action.SKIP_TODAY_SKIPPED, d.action)
    }

    // ==================== 边界函数本身 ====================

    @Test
    fun `上班停手点等于target加55秒`() {
        val target = at(9, 30)
        assertEquals(target + 55_000L, BtPunchRule.clockInMustStopAt(target))
    }

    @Test
    fun `下班最早时刻等于target加2秒`() {
        val target = at(18, 30)
        assertEquals(target + 2_000L, BtPunchRule.clockOutEarliestAt(target))
    }

    @Test
    fun `早到窗口为0时 下界就是target本身`() {
        // 0 = 完全不放宽（不是回退到闹钟链路的 75 秒）。
        // 那个 75 秒是预热准备时间，与"允许多早到"是两个概念。
        val target = at(9, 30)
        assertEquals(target, BtPunchRule.clockInEarliestAt(target, 0L))
    }

    @Test
    fun `早到窗口为0时 任何早到都被拒绝`() {
        val d = decide(at(9, 29), earlyMinutes = 0)
        assertEquals(Action.SKIP_TOO_EARLY, d.action)
    }

    @Test
    fun `早到窗口毫秒换算与钳制`() {
        assertEquals(TimeUnit.HOURS.toMillis(2), BtPunchRule.earlyWindowMsOf(120))
        // 负数钳到 0，不会产生"未来下界"这种荒谬窗口
        assertEquals(0L, BtPunchRule.earlyWindowMsOf(-5))
    }

    // ==================== 决策携带的时间戳语义 ====================

    @Test
    fun `允许打卡时 punchAt与基准分离`() {
        // 08:47 检测到考勤机：punchAt=08:47（今日实际时间），
        // 基准=09:30（配置时间，红线判定用）。
        val now = at(8, 47)
        val d = decide(now)
        assertEquals(Action.PUNCH_CLOCK_IN, d.action)
        assertEquals("记录用检测时刻", now, d.punchAtMillis)
        assertEquals("红线用配置时刻", at(9, 30), d.configuredTargetMillis)
    }

    @Test
    fun `拒绝时 kind 必须为空 防止误用`() {
        val d = decide(at(9, 40))
        assertNull("拒绝结论不应携带班次，避免调用方误读", d.kind)
    }
}
