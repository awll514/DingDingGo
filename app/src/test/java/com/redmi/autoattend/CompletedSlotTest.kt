package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import com.redmi.autoattend.data.db.PunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * "已完成槽位"跳过的测试。
 *
 * ## 对应的用户问题
 *
 * 用户原话：「手动打卡成功后必须替换掉自动打卡的时间节点」。
 *
 * 之前的 [PunchSchedule.nextSlot] 只看时间和星期，完全不看
 * "今天这一班是不是已经打过了"。于是手动打卡成功后，
 * 到点闹钟照样再打一次 —— 在考勤系统里就是重复记录。
 *
 * 这些用例锁定新行为：**已完成的班次必须被跳过，下一次自动顺延。**
 */
class CompletedSlotTest {

    /** 构造一个确定的日子：2026-11-11 周三 */
    private fun dayAt(hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 11, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60,      // 09:00 上班
        clockOutMinute = 18 * 60,    // 18:00 下班
        activeDays = setOf(2, 3, 4, 5, 6, 7),  // 周一到周六
    )

    private val todayKey = PunchSchedule.dateKey(dayAt(10))

    // ---------- 核心行为 ----------

    @Test
    fun withoutCompletedSlotsNextIsClockIn() {
        // 早上 08:00，什么都没打 —— 下一次应该是今天的上班卡
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(8),
            settings = settings,
        )
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
    }

    @Test
    fun completedClockInMakesNextBeClockOut() {
        // 【核心用例】上班卡已打完，现在 09:30。
        // 下一次必须是**下班卡**，不能再是上班卡。
        val completed = setOf(
            PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_IN),
        )
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(9, 30),
            settings = settings,
            completedSlots = completed,
        )
        assertNotNull(slot)
        assertEquals(
            "上班卡已完成，下一次应该是下班卡",
            PunchKind.CLOCK_OUT,
            slot!!.kind,
        )
    }

    @Test
    fun bothCompletedMovesToNextDay() {
        // 两班都打完（18:30），下一次应该是**明天**的上班卡
        val completed = setOf(
            PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_IN),
            PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_OUT),
        )
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(18, 30),
            settings = settings,
            completedSlots = completed,
        )
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        assertTrue(
            "两班都完成，下一次应该跨到明天",
            slot.targetAtMillis > dayAt(23, 59),
        )
    }

    @Test
    fun completingClockInDoesNotAffectClockOutOfOtherDay() {
        // 今天是 11-11，明天是 11-12。今天上班已打，
        // 不应该影响明天的任何班次。
        val completed = setOf(
            PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_IN),
        )
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(19),   // 今天下班时间也过了，但下班卡没打
            settings = settings,
            completedSlots = completed,
            includeRetryWindow = false,
        )
        assertNotNull(slot)
        // 今天下班卡没打且已过窗口 → 顺延到明天上班
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        val cal = Calendar.getInstance().apply { timeInMillis = slot.targetAtMillis }
        assertEquals(12, cal.get(Calendar.DAY_OF_MONTH))
    }

    // ---------- 边界：不能过度跳过 ----------

    @Test
    fun completedSlotForWrongDateDoesNotSkipToday() {
        // 昨天（11-10）的上班卡已完成，不能影响今天
        val yesterdayKey = PunchSchedule.dateKey(dayAt(10) - 24L * 60 * 60 * 1000)
        val completed = setOf(
            PunchSchedule.CompletedSlot(yesterdayKey, PunchKind.CLOCK_IN),
        )
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(8),
            settings = settings,
            completedSlots = completed,
        )
        assertEquals(
            "昨天的记录不能吃掉今天的上班卡",
            PunchKind.CLOCK_IN,
            slot!!.kind,
        )
    }

    @Test
    fun completedClockOutDoesNotSkipClockIn() {
        // 只有下班卡被标记完成（异常数据），上班卡仍应正常调度
        val completed = setOf(
            PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_OUT),
        )
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(8),
            settings = settings,
            completedSlots = completed,
        )
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
    }

    @Test
    fun emptyCompletedSetBehavesNormally() {
        val withEmpty = PunchSchedule.nextSlot(dayAt(8), settings, completedSlots = emptySet())
        val without = PunchSchedule.nextSlot(dayAt(8), settings)
        assertEquals(without?.targetAtMillis, withEmpty?.targetAtMillis)
    }

    // ---------- completedSlotsForToday 辅助函数 ----------

    @Test
    fun buildsSlotsOnlyForToday() {
        val now = dayAt(12)
        val todayMorning = dayAt(9, 5)
        val yesterdayEvening = dayAt(18) - 24L * 60 * 60 * 1000

        val result = PunchSchedule.completedSlotsForToday(
            nowMillis = now,
            done = listOf(
                PunchKind.CLOCK_IN to todayMorning,
                PunchKind.CLOCK_OUT to yesterdayEvening,   // 昨天的，不算
            ),
        )

        assertEquals(1, result.size)
        assertTrue(result.contains(PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_IN)))
        assertFalse(result.contains(PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_OUT)))
    }

    @Test
    fun manualKindIsNotASlot() {
        // MANUAL 不是可调度槽位，不应出现在已完成集合里
        val result = PunchSchedule.completedSlotsForToday(
            nowMillis = dayAt(12),
            done = listOf(PunchKind.MANUAL to dayAt(10)),
        )
        assertTrue("MANUAL 不应产生槽位", result.isEmpty())
    }

    @Test
    fun emptyDoneGivesEmptySet() {
        val result = PunchSchedule.completedSlotsForToday(dayAt(12), emptyList())
        assertTrue(result.isEmpty())
    }

    // ---------- 与跳过日期共存 ----------

    @Test
    fun skipDateStillWorksWithCompletedSlots() {
        // 今天被整日跳过 + 上班已完成 → 下一次应是明天
        val slot = PunchSchedule.nextSlot(
            nowMillis = dayAt(8),
            settings = settings,
            skippedDateKeys = setOf(todayKey),
            completedSlots = setOf(
                PunchSchedule.CompletedSlot(todayKey, PunchKind.CLOCK_IN),
            ),
        )
        assertNotNull(slot)
        val cal = Calendar.getInstance().apply { timeInMillis = slot!!.targetAtMillis }
        assertEquals(12, cal.get(Calendar.DAY_OF_MONTH))
    }
}
