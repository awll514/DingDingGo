package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import com.redmi.autoattend.data.db.PunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 调度抗自激测试。
 *
 * ## 这一组测试要防的是什么
 *
 * [PunchSchedule.nextSlot] 为了支持"补执行"，会把**仍在补执行窗口内**的
 * 已过时间点也算作"下一次打卡"（effectiveTrigger = now）。
 *
 * 这带来一个危险的耦合：闹钟触发 → onReceive 重排 → nextSlot 又返回
 * **同一个班次** → 立刻再排一个闹钟 → 再触发……
 *
 * 正常情况下这个循环被"成功后跳过已完成班次"打断。但如果本次执行失败、
 * 或服务因"已有执行在进行"被忽略，就会空转到窗口耗尽。
 *
 * 修法是 AlarmScheduler 在重排前把刚触发的槽位标记为已完成
 * （PunchSchedule.CompletedSlot），让 nextSlot 让位给下一班。
 * 这里用纯函数验证那条路确实有效。
 */
class SchedulerGuardTest {

    /** 上班 09:30，下班 18:00，周一至周五 */
    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,
        clockOutMinute = 18 * 60,
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    /** 找一个确定是工作日的"当天 00:00"，避免测试跑在周末时行为不确定 */
    private fun weekdayStart(): Long {
        var millis = PunchSchedule.startOfDay(System.currentTimeMillis())
        while (Calendar.getInstance().apply { timeInMillis = millis }
                .get(Calendar.DAY_OF_WEEK) !in this.settings.activeDays
        ) {
            millis += 24 * 60 * 60 * 1000L
        }
        return millis
    }

    private fun clockInAt(dayStart: Long): Long = dayStart + settings.clockInMinute * 60_000L

    @Test
    fun withinRetryWindowStillReturnsSameSlot() {
        // 目标 09:30，当前 09:31 —— 仍在 120 秒补执行窗口内，
        // nextSlot 会返回**同一个上班班次**。这正是自激循环的成因，
        // 先确认它真实存在，否则下面的修复无从验证。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + 60_000L

        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        assertEquals(target, slot.targetAtMillis)
    }

    @Test
    fun markingSlotCompletedMakesNextSlotAdvance() {
        // 把上面那个班次标记为已完成（AlarmScheduler.markJustFired 最终走这条路），
        // 下一次计算必须顺延到**下班**，而不是继续返回上班。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + 60_000L

        val first = PunchSchedule.nextSlot(now, settings)!!
        val dateKey = PunchSchedule.dateKey(first.targetAtMillis)

        val afterMark = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(PunchSchedule.CompletedSlot(dateKey, first.kind)),
        )

        assertNotNull(afterMark)
        assertEquals(PunchKind.CLOCK_OUT, afterMark!!.kind)
        assertTrue(afterMark.targetAtMillis > first.targetAtMillis)
    }

    @Test
    fun afterRetryWindowTheSlotDisappears() {
        // 过了补执行窗口（目标 + 120 秒）后，该班次不应再被返回，
        // 否则"补执行"会变成"无限补执行"。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + (PunchSchedule.RETRY_WINDOW_SECONDS + 1) * 1000L

        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_OUT, slot!!.kind)
    }

    @Test
    fun completedSlotsDoNotLeakAcrossKinds() {
        // 早上打了上班卡，不能影响下午的下班卡。
        // 这是"手动打卡后不重复触发"修复里最容易写错的一条。
        val dayStart = weekdayStart()
        val dateKey = PunchSchedule.dateKey(dayStart)
        val now = dayStart + 8 * 60 * 60 * 1000L

        val onlyClockInDone = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_IN)),
        )
        assertNotNull(onlyClockInDone)
        assertEquals(PunchKind.CLOCK_OUT, onlyClockInDone!!.kind)

        val bothDone = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(
                PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_IN),
                PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_OUT),
            ),
        )
        // 今天两班都打完了，下一个必须是**之后的某一天**
        assertNotNull(bothDone)
        assertTrue(bothDone!!.targetAtMillis >= dayStart + 24 * 60 * 60 * 1000L)
    }
}
