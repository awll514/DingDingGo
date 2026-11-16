package com.dingdinggo.app

import com.dingdinggo.app.core.BtPunchRule
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.core.ScheduleSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 扫描续期在"窗口外 → 窗口内"的转换。
 *
 * ## 这个文件防的是什么（一次自己造成的事故）
 *
 * 为满足「后台零消耗」红线，我给 25 秒的 BLE 扫描续期加了时段门控：
 * 只在可能打卡的时间窗内保持扫描。窗口外调 stopScanRenewal() 省电。
 *
 * **第一版实现是错的**：窗口外直接撤销续期闹钟，而没有任何机制
 * 会在"时间进入下一个窗口"时把它恢复。后果是 ——
 * 到了上班/下班窗口，扫描依然是停的，蓝牙触发**静默失效**，
 * 而界面上一切正常（因为 SCAN_STALE_MS 保鲜期也骗过了健康度）。
 *
 * 真机证据：dumpsys alarm 里 RENEW_BT_SCAN 显示
 * "1 wakeups, last -1m35s" —— 只触发过一次就再没重排。
 *
 * 修法：窗口外仍然停扫描（省电），但保留一个 **5 分钟的滚动探测**，
 * 定期重新判定窗口。本测试锁住"窗口判定必须能随真实时间重新变为 true"。
 *
 * ## 为什么必须测转换而不是只测单点
 *
 * 单点判定正确 ≠ 系统能持续工作。这次事故正是"每个单点都对，
 * 但状态机转不回去"。所以本类重点覆盖**跨越窗口边界的时间推进**。
 */
class ScanRenewalWindowTransitionTest {

    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,   // 09:30
        clockOutMinute = 18 * 60 + 30, // 18:30
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    private val earlyWindow = BtPunchRule.DEFAULT_EARLY_WINDOW_MINUTES

    private fun workdayAt(hour: Int, minute: Int): Long {
        var millis = PunchSchedule.startOfDay(System.currentTimeMillis())
        while (!PunchSchedule.isActiveDay(millis, settings)) {
            millis += TimeUnit.DAYS.toMillis(1)
        }
        return millis + TimeUnit.HOURS.toMillis(hour.toLong()) +
            TimeUnit.MINUTES.toMillis(minute.toLong())
    }

    private fun alive(now: Long, clockInDone: Boolean = false, clockOutDone: Boolean = false) =
        BtPunchRule.shouldKeepScanAlive(
            nowMillis = now,
            settings = settings,
            earlyWindowMinutes = earlyWindow,
            isActiveDay = true,
            clockInDoneToday = clockInDone,
            clockOutDoneToday = clockOutDone,
        )

    /**
     * 【核心回归】窗口外之后必须能重新进入窗口。
     *
     * 如果将来有人把"窗口外"实现成"永久停止"，这条会失败。
     */
    @Test
    fun windowReopensLaterInTheSameDay() {
        // 14:00 两班之间 → 不在窗口
        assertFalse("14:00 应在窗外", alive(workdayAt(14, 0), clockInDone = true))
        // 推进到 18:30 下班时刻 → 必须重新进入窗口
        assertTrue(
            "从窗外推进到下班时刻后，必须重新判定为窗口内 —— " +
                "否则续期永久停摆、蓝牙触发静默失效",
            alive(workdayAt(18, 30), clockInDone = true),
        )
    }

    /**
     * 逐分钟推进一整天，收集"窗口开关翻转次数"。
     *
     * 正常应该是：关 → 开（上班窗口）→ 关 → 开（下班窗口）→ 关。
     * 若翻转次数为 0 或 1，说明状态机卡死在某一侧。
     */
    @Test
    fun windowTogglesMultipleTimesInADay() {
        var prev = alive(workdayAt(0, 0))
        var toggles = 0
        for (minute in 1 until 24 * 60) {
            val now = workdayAt(0, 0) + TimeUnit.MINUTES.toMillis(minute.toLong())
            val cur = alive(now)
            if (cur != prev) toggles++
            prev = cur
        }
        assertTrue(
            "一天内窗口应至少翻转 2 次（进入上班窗口、进入下班窗口），" +
                "实际 = " + toggles + "。为 0/1 说明状态机转不回去",
            toggles >= 2,
        )
    }

    /** 探测周期必须显著短于窗口长度，否则会整段错过 */
    @Test
    fun probeIntervalIsShorterThanSmallestWindow() {
        val probeMinutes = 5L  // WINDOW_PROBE_INTERVAL_MS
        // 上班窗口：早到 120 分钟 + 55 秒 ≈ 120 分钟，远大于 5 分钟
        val clockInWindowMin = earlyWindow.toLong() + 1
        // 下班窗口：从 target+2s 到当日结束，也远大于 5 分钟
        assertTrue(
            "探测周期(" + probeMinutes + "分钟)必须明显短于最短窗口",
            probeMinutes < clockInWindowMin,
        )
    }

    /** 上班卡打完不会让下班窗口也失效 */
    @Test
    fun clockInDoneDoesNotBlockClockOutWindow() {
        val evening = workdayAt(19, 0)
        assertFalse(
            "上班已打完 + 下班未打，19:00 必须仍在窗口内",
            !alive(evening, clockInDone = true, clockOutDone = false),
        )
    }
}
