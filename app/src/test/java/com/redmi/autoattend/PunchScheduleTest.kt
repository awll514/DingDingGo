package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import com.redmi.autoattend.data.db.PunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 排班计算的单元测试。
 *
 * 这部分是纯函数，最容易出边界 bug（跨天、跨周、补执行窗口），
 * 因此覆盖得比较细。
 */
class PunchScheduleTest {

    /** 构造一个确定的时间戳：2026-11-10 是周二 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun nextSlot_findsTodaysClockIn() {
        // 2026-11-10 周二 08:00，上班 09:00
        val now = at(2026, 11, 10, 8, 0)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val slot = PunchSchedule.nextSlot(now, settings)

        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        // 目标应该是 09:00
        val cal = Calendar.getInstance().apply { timeInMillis = slot.targetAtMillis }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun nextSlot_triggerIs75SecondsBefore() {
        val now = at(2026, 11, 10, 8, 0)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val slot = PunchSchedule.nextSlot(now, settings)!!

        val diff = slot.targetAtMillis - slot.triggerAtMillis
        assertEquals(PunchSchedule.PRE_WARM_SECONDS * 1000L, diff)
    }

    @Test
    fun nextSlot_picksClockOutAfterClockInPassed() {
        // 上班已过（10:00），应该取当天下班
        val now = at(2026, 11, 10, 10, 0)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val slot = PunchSchedule.nextSlot(now, settings)!!

        assertEquals(PunchKind.CLOCK_OUT, slot.kind)
    }

    @Test
    fun nextSlot_skipsInactiveWeekend() {
        // 2026-11-14 是周六
        val now = at(2026, 11, 14, 8, 0)
        val settings = ScheduleSettings(
            clockInMinute = 9 * 60,
            clockOutMinute = 18 * 60,
            activeDays = ScheduleSettings.WEEKDAYS,
        )
        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        // 下一个生效日应该是周一 11-16
        val cal = Calendar.getInstance().apply { timeInMillis = slot!!.targetAtMillis }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun nextSlot_returnsNullWhenNoActiveDays() {
        val now = at(2026, 11, 10, 8, 0)
        val settings = ScheduleSettings(activeDays = emptySet())
        assertNull(PunchSchedule.nextSlot(now, settings))
    }

    @Test
    fun nextSlot_allowsRetryWindowJustAfterTarget() {
        // 09:00 目标，09:01 时仍在 120 秒补执行窗口内
        val now = at(2026, 11, 10, 9, 1)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        // 触发时刻已经过，应该被夹到"现在"
        assertTrue(slot.triggerAtMillis >= now)
    }

    @Test
    fun nextSlot_skipsPastRetryWindow() {
        // 09:03 已经超过 120 秒窗口，应该跳到下班
        val now = at(2026, 11, 10, 9, 3)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val slot = PunchSchedule.nextSlot(now, settings)!!
        assertEquals(PunchKind.CLOCK_OUT, slot.kind)
    }

    @Test
    fun nextSlot_honorsSingleDayOverride() {
        // 周三是生效日，但用覆盖把上班时间改成 07:30
        val now = at(2026, 11, 11, 7, 0)
        val settings = ScheduleSettings(clockInMinute = 9 * 60, clockOutMinute = 18 * 60)
        val override = PunchSchedule.Override(
            dateKey = PunchSchedule.dateKey(now),
            kind = PunchKind.CLOCK_IN,
            minuteOfDay = 7 * 60 + 30,
        )
        val slot = PunchSchedule.nextSlot(now, settings, listOf(override))!!

        val cal = Calendar.getInstance().apply { timeInMillis = slot.targetAtMillis }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertTrue(slot.isOverride)
    }

    @Test
    fun overrideForcesWorkOnRestDay() {
        // 周六本是休息日，但覆盖要求执行
        val now = at(2026, 11, 14, 8, 0)
        val settings = ScheduleSettings(
            clockInMinute = 9 * 60,
            clockOutMinute = 18 * 60,
            activeDays = ScheduleSettings.WEEKDAYS,
        )
        val override = PunchSchedule.Override(
            dateKey = PunchSchedule.dateKey(now),
            kind = PunchKind.CLOCK_IN,
            minuteOfDay = 9 * 60,
        )
        val slot = PunchSchedule.nextSlot(now, settings, listOf(override))!!
        val cal = Calendar.getInstance().apply { timeInMillis = slot.targetAtMillis }
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun formatMinute_padsCorrectly() {
        assertEquals("00:00", PunchSchedule.formatMinute(0))
        assertEquals("09:05", PunchSchedule.formatMinute(9 * 60 + 5))
        assertEquals("23:59", PunchSchedule.formatMinute(23 * 60 + 59))
    }

    @Test
    fun startOfDay_isMidnight() {
        val now = at(2026, 11, 10, 15, 42)
        val start = PunchSchedule.startOfDay(now)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun dateKey_isStablePerDay() {
        val morning = at(2026, 11, 10, 6, 0)
        val evening = at(2026, 11, 10, 23, 0)
        assertEquals(PunchSchedule.dateKey(morning), PunchSchedule.dateKey(evening))
        assertEquals(20261110, PunchSchedule.dateKey(morning))
    }
}
