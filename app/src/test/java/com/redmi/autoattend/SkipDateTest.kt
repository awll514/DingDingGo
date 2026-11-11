package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 跳过某天后，"下一次打卡"必须顺延。
 *
 * 这是本次改动最核心的行为：跳过后界面上的日期要继续往后走，
 * 而不是停在被跳过的那一天。
 */
class SkipDateTest {

    /** 2026-11-11（周三）08:00 */
    private fun wedMorning(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 11, 8, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60,
        clockOutMinute = 18 * 60,
        activeDays = setOf(2, 3, 4, 5, 6),
    )

    @Test
    fun normalNextSlotIsToday() {
        val slot = PunchSchedule.nextSlot(wedMorning(), settings)
        assertEquals(
            "今天",
            PunchSchedule.dayLabel(wedMorning(), slot!!.targetAtMillis, settings.activeDays),
        )
    }

    @Test
    fun skippingTodayMovesToTomorrow() {
        val now = wedMorning()
        val todayKey = PunchSchedule.dateKey(now)

        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = setOf(todayKey),
        )

        assertEquals(
            "明天",
            PunchSchedule.dayLabel(now, slot!!.targetAtMillis, settings.activeDays),
        )
    }

    @Test
    fun skippingTodayAndTomorrowMovesToDayAfter() {
        val now = wedMorning()
        val todayKey = PunchSchedule.dateKey(now)
        val tomorrowKey = PunchSchedule.dateKey(now + TimeUnit.DAYS.toMillis(1))

        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = setOf(todayKey, tomorrowKey),
        )

        assertEquals("周五", PunchSchedule.dayLabel(now, slot!!.targetAtMillis, settings.activeDays))
    }

    @Test
    fun skippingTheWholeWeekReturnsNull() {
        val now = wedMorning()
        val allWeek = (0..7).map {
            PunchSchedule.dateKey(now + TimeUnit.DAYS.toMillis(it.toLong()))
        }.toSet()

        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = allWeek,
        )

        assertNull("整周都跳过时不应有下一次打卡", slot)
    }

    @Test
    fun skippingTomorrowOnlyKeepsToday() {
        val now = wedMorning()
        val tomorrowKey = PunchSchedule.dateKey(now + TimeUnit.DAYS.toMillis(1))

        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = setOf(tomorrowKey),
        )

        assertEquals(
            "今天",
            PunchSchedule.dayLabel(now, slot!!.targetAtMillis, settings.activeDays),
        )
    }
}
