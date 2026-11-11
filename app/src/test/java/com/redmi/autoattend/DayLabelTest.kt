package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 日期标签测试。
 *
 * 覆盖你要求的显示规则：
 * - 今天 / 明天 用相对词；
 * - 2~6 天用周几；
 * - 7 天以上用「M月d日 周几」；
 * - 没有任何生效日时给休假文案。
 */
class DayLabelTest {

    /** 造一个确定的时刻：2026-11-11（周三）00:00 */
    private fun base(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 11, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun plusDays(ms: Long, days: Int): Long =
        ms + TimeUnit.DAYS.toMillis(days.toLong())

    @Test
    fun sameDayIsToday() {
        assertEquals(
            "今天",
            PunchSchedule.dayLabel(base(), plusDays(base(), 0), setOf(4)),
        )
    }

    @Test
    fun nextDayIsTomorrow() {
        assertEquals(
            "明天",
            PunchSchedule.dayLabel(base(), plusDays(base(), 1), setOf(4)),
        )
    }

    @Test
    fun twoDaysLaterShowsWeekday() {
        // 11/11 是周三，+2 天 = 周五
        assertEquals(
            "周五",
            PunchSchedule.dayLabel(base(), plusDays(base(), 2), setOf(5)),
        )
    }

    @Test
    fun sixDaysLaterStillShowsWeekday() {
        // +6 天 = 周二
        assertEquals(
            "周二",
            PunchSchedule.dayLabel(base(), plusDays(base(), 6), setOf(4)),
        )
    }

    @Test
    fun sevenDaysLaterShowsFullDate() {
        // +7 天 = 11/18 周三，超过一周要走完整日期
        val label = PunchSchedule.dayLabel(base(), plusDays(base(), 7), setOf(4))
        assertTrue("应包含月份日期: " + label, label.contains("11月18日"))
        assertTrue("应包含周几: " + label, label.contains("周三"))
    }

    @Test
    fun emptyActiveDaysGivesVacationText() {
        val label = PunchSchedule.vacationLabel(emptySet())
        assertTrue("休假文案应提到已关闭: " + label, label.contains("关闭"))
    }

    @Test
    fun nonEmptyActiveDaysGivesRestText() {
        val label = PunchSchedule.vacationLabel(setOf(2, 3))
        assertTrue("休息文案应提到休息日: " + label, label.contains("休息日"))
    }

    @Test
    fun pastTargetStillSaysToday() {
        // 目标时间在今天早些时候（已过去）时，不应显示成"昨天"
        val label = PunchSchedule.dayLabel(base(), base() - TimeUnit.HOURS.toMillis(3), setOf(4))
        assertEquals("今天", label)
    }
}
