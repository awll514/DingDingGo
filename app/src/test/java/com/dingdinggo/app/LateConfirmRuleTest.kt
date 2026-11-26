package com.dingdinggo.app

import com.dingdinggo.app.core.BtPunchRule
import com.dingdinggo.app.core.LateConfirmRule
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.core.ScheduleSettings
import com.dingdinggo.app.data.db.PunchKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [LateConfirmRule] 与 [BtPunchRule.shouldKeepScanAlive] 迟到确认窗口边界单元测试。
 */
class LateConfirmRuleTest {

    private fun calAt(hour: Int, minute: Int, second: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, 23, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `normal window before late stop does not trigger late prompt`() {
        val targetIn = calAt(9, 0, 0)
        val targetOut = calAt(18, 0, 0)
        val stopAt = BtPunchRule.clockInMustStopAt(targetIn)

        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = stopAt,
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = false,
                promptedDateKey = 0,
            ),
        )
    }

    @Test
    fun `first arrival trigger after late stop prompts once then suppresses same day`() {
        val targetIn = calAt(9, 0, 0)
        val targetOut = calAt(18, 0, 0)
        val lateNow = calAt(9, 1, 5)
        val todayKey = PunchSchedule.dateKey(lateNow)

        assertTrue(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = lateNow,
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = false,
                promptedDateKey = 0,
            ),
        )

        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = lateNow + 5 * 60_000L,
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = false,
                promptedDateKey = todayKey,
            ),
        )
    }

    @Test
    fun `clock in already done or past 3h max window or near clock out never prompts`() {
        val targetIn = calAt(9, 0, 0)
        val targetOut = calAt(18, 0, 0)

        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = calAt(9, 10, 0),
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = true,
                promptedDateKey = 0,
            ),
        )

        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = targetIn + LateConfirmRule.MAX_LATE_CONFIRM_WINDOW_MS + 1_000L,
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = false,
                promptedDateKey = 0,
            ),
        )

        val nearClockOut = BtPunchRule.clockOutEarliestAt(targetOut)
        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_IN,
                nowMillis = nearClockOut,
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = false,
                promptedDateKey = 0,
            ),
        )
    }

    @Test
    fun `late override only applies to clock in never clock out`() {
        val targetIn = calAt(9, 0, 0)
        val targetOut = calAt(18, 0, 0)

        assertFalse(
            LateConfirmRule.shouldPromptLateConfirm(
                kind = PunchKind.CLOCK_OUT,
                nowMillis = calAt(18, 5, 0),
                clockInTargetMillis = targetIn,
                clockOutTargetMillis = targetOut,
                clockInDoneToday = true,
                promptedDateKey = 0,
            ),
        )

        assertTrue(LateConfirmRule.isLateAllowedForKind(PunchKind.CLOCK_IN, true))
        assertFalse(LateConfirmRule.isLateAllowedForKind(PunchKind.CLOCK_IN, false))
        assertFalse(LateConfirmRule.isLateAllowedForKind(PunchKind.CLOCK_OUT, true))
    }

    @Test
    fun `scan window extends safely into morning late confirm window until done or clock out`() {
        val settings = ScheduleSettings(
            clockInMinute = 9 * 60,
            clockOutMinute = 18 * 60,
        )
        val thirtyMinLate = calAt(9, 30, 0)

        // 无延迟确认窗口：9:30 不再保活扫描
        assertFalse(
            BtPunchRule.shouldKeepScanAlive(
                nowMillis = thirtyMinLate,
                settings = settings,
                earlyWindowMinutes = 90,
                isActiveDay = true,
                clockInDoneToday = false,
                clockOutDoneToday = false,
                lateConfirmWindowMinutes = 0,
            ),
        )

        // 有 120 分钟首次迟到确认窗口且未打上班卡：9:30 保持扫描活跃
        assertTrue(
            BtPunchRule.shouldKeepScanAlive(
                nowMillis = thirtyMinLate,
                settings = settings,
                earlyWindowMinutes = 90,
                isActiveDay = true,
                clockInDoneToday = false,
                clockOutDoneToday = false,
                lateConfirmWindowMinutes = 120,
            ),
        )

        // 一旦已打上班卡：9:30 立刻停扫
        assertFalse(
            BtPunchRule.shouldKeepScanAlive(
                nowMillis = thirtyMinLate,
                settings = settings,
                earlyWindowMinutes = 90,
                isActiveDay = true,
                clockInDoneToday = true,
                clockOutDoneToday = false,
                lateConfirmWindowMinutes = 120,
            ),
        )

        // 即使给超大延展时间，进入下班可打窗前也不会越界把两个窗口连成全天候常驻
        assertFalse(
            BtPunchRule.shouldKeepScanAlive(
                nowMillis = calAt(13, 30, 0),
                settings = settings,
                earlyWindowMinutes = 90,
                isActiveDay = true,
                clockInDoneToday = false,
                clockOutDoneToday = false,
                lateConfirmWindowMinutes = 180,
            ),
        )
    }
}
