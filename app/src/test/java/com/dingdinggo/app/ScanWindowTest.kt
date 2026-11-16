package com.dingdinggo.app

import com.dingdinggo.app.core.BtPunchRule
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.core.ScheduleSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 扫描续期时间窗判定。
 *
 * ## 这个文件在保护什么
 *
 * 真机实测发现：蓝牙栈会在数十秒内停掉 PendingIntent 扫描，必须持续续期。
 * 续期周期压到 25 秒后，如果 7×24 全天候运行，就是**每天约 3456 次唤醒** ——
 * 直接违反 AGENTS.md 的「后台零消耗」红线。
 *
 * 解法是只在"可能真的要用"的时间窗内续期：
 *   · 上班卡窗口：[早到窗口下界, target+55s]
 *   · 下班卡窗口：[target+2s, 当日结束]
 *
 * 窗口之外扫到了也没用（闸门必然拒绝），所以可以完全停掉。
 *
 * ## 这个测试的价值
 *
 * 窗口判定错一边的后果是对称的、都很严重：
 *   · 判太宽 → 白耗电，废掉「后台零消耗」红线；
 *   · 判太窄 → **真正需要时扫描是停的**，蓝牙触发静默失效，漏卡。
 *
 * 后者尤其危险，因为它和"一切正常"在界面上看不出区别。
 */
class ScanWindowTest {

    /** 上班 09:30，下班 18:30，周一至周五 */
    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,
        clockOutMinute = 18 * 60 + 30,
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    /** 早到窗口 120 分钟（默认值） */
    private val earlyWindow = BtPunchRule.DEFAULT_EARLY_WINDOW_MINUTES

    /** 取一个确定的工作日内的时刻 */
    private fun workdayAt(hour: Int, minute: Int): Long {
        var millis = PunchSchedule.startOfDay(System.currentTimeMillis())
        while (!PunchSchedule.isActiveDay(millis, settings)) {
            millis += TimeUnit.DAYS.toMillis(1)
        }
        return millis + TimeUnit.HOURS.toMillis(hour.toLong()) +
            TimeUnit.MINUTES.toMillis(minute.toLong())
    }

    private fun keepAlive(
        now: Long,
        isActiveDay: Boolean = true,
        clockInDone: Boolean = false,
        clockOutDone: Boolean = false,
    ): Boolean = BtPunchRule.shouldKeepScanAlive(
        nowMillis = now,
        settings = settings,
        earlyWindowMinutes = earlyWindow,
        isActiveDay = isActiveDay,
        clockInDoneToday = clockInDone,
        clockOutDoneToday = clockOutDone,
    )

    // ============ 上班卡窗口 ============

    /** 早到窗口内（09:30 前 120 分钟 = 07:30 起）必须保持扫描 */
    @Test
    fun clockInEarlyWindowKeepsScanAlive() {
        assertTrue("07:30 早到窗口起点应保持扫描", keepAlive(workdayAt(7, 30)))
        assertTrue("08:00 在早到窗口内", keepAlive(workdayAt(8, 0)))
        assertTrue("09:00 在早到窗口内", keepAlive(workdayAt(9, 0)))
        assertTrue("09:30 正好是上班时刻", keepAlive(workdayAt(9, 30)))
    }

    /** 上班卡上界（09:30 + 55s）之后必须停 */
    @Test
    fun afterClockInStopPointScanStops() {
        val justAfter = workdayAt(9, 30) + TimeUnit.SECONDS.toMillis(55) +
            TimeUnit.MINUTES.toMillis(6) // 超过 PAD
        assertFalse("迟到停手点 + PAD 之后应停止扫描", keepAlive(justAfter))
    }

    /** 早到窗口之前（07:30 之前）不扫 */
    @Test
    fun beforeEarlyWindowScanStops() {
        assertFalse("凌晨 03:00 应在窗外", keepAlive(workdayAt(3, 0)))
        assertFalse("06:00 仍早于 07:30 窗口", keepAlive(workdayAt(6, 0)))
    }

    /** 上班卡已完成 → 上班窗口不该再让它保持扫描 */
    @Test
    fun clockInDoneShutsItsWindow() {
        // 09:00 本在早到窗口内，但上班卡已完成 → 这个窗口没意义了
        assertFalse(
            "上班已打完后，09:00 不应因上班窗口而保持扫描",
            keepAlive(workdayAt(9, 0), clockInDone = true),
        )
    }

    // ============ 下班卡窗口 ============

    /** 下班卡：target+2s 之后必须保持扫描（晚走是允许的） */
    @Test
    fun clockOutWindowKeepsScanAlive() {
        assertTrue("18:30 正好下班时刻", keepAlive(workdayAt(18, 30)))
        assertTrue("19:00 晚走仍要能打", keepAlive(workdayAt(19, 0)))
        assertTrue("22:00 深夜晚走依然允许", keepAlive(workdayAt(22, 0)))
    }

    /** 下班卡窗口下界之前不扫（防早退） */
    @Test
    fun beforeClockOutEarliestScanStops() {
        // 14:00：上班已打完、下班还没到 -> 应停
        assertFalse(
            "14:00 两班之间（上班已完成、下班未到）应停止扫描",
            keepAlive(workdayAt(14, 0), clockInDone = true),
        )
    }

    /** 两班都完成 → 全天不再需要扫描 */
    @Test
    fun bothDoneStopsScanForTheDay() {
        assertFalse(
            "两班都打完，18:30 也不该再扫",
            keepAlive(workdayAt(18, 30), clockInDone = true, clockOutDone = true),
        )
        assertFalse(
            "夜间更不该扫",
            keepAlive(workdayAt(23, 0), clockInDone = true, clockOutDone = true),
        )
    }

    // ============ 非生效日 ============

    @Test
    fun inactiveDayStopsScan() {
        assertFalse(
            "非生效日（周末）不应保持扫描",
            keepAlive(workdayAt(9, 0), isActiveDay = false),
        )
    }

    // ============ 省电效果估算 ============

    /**
     * 量化省电收益：一天中需要扫描的时长占比。
     *
     * 这是「后台零消耗」红线的可验证判据 —— 不是"我觉得省了"，
     * 而是算出具体比例。
     */
    @Test
    fun scanningWindowIsSmallFractionOfDay() {
        var aliveMinutes = 0
        // 按分钟遍历一整天
        for (minute in 0 until 24 * 60) {
            val now = workdayAt(0, 0) + TimeUnit.MINUTES.toMillis(minute.toLong())
            if (keepAlive(now)) aliveMinutes++
        }
        val ratio = aliveMinutes.toDouble() / (24 * 60)
        println("[省电测算] 扫描活跃时长 = " + aliveMinutes + " 分钟 / 1440 = " +
            String.format("%.1f%%", ratio * 100))

        assertTrue(
            "扫描活跃时长应远小于全天（当前 = " + aliveMinutes + " 分钟）。" +
                "若这个值接近 1440，说明窗口判定失效，「后台零消耗」红线被破坏",
            ratio < 0.60,
        )
        assertTrue(
            "但也不能过窄 —— 必须覆盖早到窗口与整个下班后时段（当前 = " +
                aliveMinutes + " 分钟）",
            ratio > 0.10,
        )
    }
}
