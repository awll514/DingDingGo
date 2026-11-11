package com.redmi.autoattend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡安全底线的边界测试。
 *
 * 这三条是用户明确要求的红线，而且全是**边界数值**问题，
 * 靠肉眼看代码很容易差一秒。所以把边界钉死在测试里：
 *
 * 1. 下班卡：目标时刻之前一律不打；
 * 2. 上班卡：满 1 分钟才算迟到，且要留安全余量，不能卡 58/59 秒；
 * 3. 外勤：检测到外勤就不能点。
 */
class SafetyGateTest {

    private companion object {
        const val LATE_GRACE_MS = 60_000L
        const val LATE_SAFETY_MARGIN_MS = 5_000L
        const val CLOCK_OUT_SAFETY_MARGIN_MS = 2_000L
        /** 上班目标时刻 */
        const val CLOCK_IN_TARGET = 1_700_000_000_000L
        /** 下班目标时刻 */
        const val CLOCK_OUT_TARGET = 1_700_032_400_000L
    }

    /** 复刻上班卡判定：true = 允许打 */
    private fun canClockIn(now: Long): Boolean {
        val mustStopAt = CLOCK_IN_TARGET + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS
        return now <= mustStopAt
    }

    /** 复刻下班卡判定 */
    private fun canClockOut(now: Long): Boolean {
        val allowedAt = CLOCK_OUT_TARGET + CLOCK_OUT_SAFETY_MARGIN_MS
        return now >= allowedAt
    }

    // ==================== 上班卡：迟到边界 ====================

    @Test
    fun clockIn_allowedExactlyAtTarget() {
        assertTrue(canClockIn(CLOCK_IN_TARGET))
    }

    @Test
    fun clockIn_allowedWithinFirstMinute() {
        assertTrue(canClockIn(CLOCK_IN_TARGET + 30_000L))
    }

    @Test
    fun clockIn_allowedAt50Seconds() {
        assertTrue(canClockIn(CLOCK_IN_TARGET + 50_000L))
    }

    @Test
    fun clockIn_allowedAtWallClock55Seconds() {
        assertTrue(canClockIn(CLOCK_IN_TARGET + 55_000L))
    }

    @Test
    fun clockIn_blockedAt56Seconds() {
        assertFalse(canClockIn(CLOCK_IN_TARGET + 56_000L))
    }

    @Test
    fun clockIn_blockedAt58Seconds() {
        assertFalse(canClockIn(CLOCK_IN_TARGET + 58_000L))
    }

    @Test
    fun clockIn_blockedAt59Seconds() {
        assertFalse(canClockIn(CLOCK_IN_TARGET + 59_000L))
    }

    @Test
    fun clockIn_blockedAt61Seconds() {
        assertFalse(canClockIn(CLOCK_IN_TARGET + 61_000L))
    }

    @Test
    fun clockIn_blockedMuchLater() {
        assertFalse(canClockIn(CLOCK_IN_TARGET + 15 * 60_000L))
    }

    @Test
    fun clockIn_allowedBeforeTarget() {
        assertTrue(canClockIn(CLOCK_IN_TARGET - 5 * 60_000L))
    }

    // ==================== 下班卡：绝不提前 ====================

    @Test
    fun clockOut_blockedOneMinuteEarly() {
        assertFalse(canClockOut(CLOCK_OUT_TARGET - 60_000L))
    }

    @Test
    fun clockOut_blockedOneSecondEarly() {
        assertFalse(canClockOut(CLOCK_OUT_TARGET - 1_000L))
    }

    @Test
    fun clockOut_blockedExactlyAtTarget() {
        assertFalse(canClockOut(CLOCK_OUT_TARGET))
    }

    @Test
    fun clockOut_blockedAtOneSecond() {
        assertFalse(canClockOut(CLOCK_OUT_TARGET + 1_000L))
    }

    @Test
    fun clockOut_allowedAfterMargin() {
        assertTrue(canClockOut(CLOCK_OUT_TARGET + 2_000L))
    }

    @Test
    fun clockOut_allowedLater() {
        assertTrue(canClockOut(CLOCK_OUT_TARGET + 5 * 60_000L))
    }

    // ==================== 外勤检测 ====================

    @Test
    fun outdoor_detectsNotInRange() {
        val page = "外勤 下班打卡 未进入考勤范围，可外勤打卡 如需内勤打卡，请检查"
        assertTrue(isOutdoor(page))
    }

    @Test
    fun outdoor_notDetectedOnNormalPage() {
        val page = "上班09:30 09:30已打卡 下班18:30 下班打卡"
        assertFalse(isOutdoor(page))
    }

    @Test
    fun outdoor_emptyPageIsNotOutdoor() {
        assertFalse(isOutdoor(""))
    }

    private fun isOutdoor(blob: String): Boolean {
        if (blob.isEmpty()) return false
        val signals = listOf("未进入考勤范围", "可外勤打卡", "如需内勤打卡", "外勤打卡")
        return signals.any { blob.contains(it) }
    }

    // ==================== 目标时段已打卡 ====================

    @Test
    fun alreadyDone_clockOutDoesNotMatchClockIn() {
        val page = "上班09:30 09:30已打卡 下班18:30 下班打卡"
        assertTrue(segmentDone(page, "上班"))
        assertFalse(segmentDone(page, "下班"))
    }

    @Test
    fun alreadyDone_bothDone() {
        val page = "上班09:30 09:30已打卡 下班18:30 18:30已打卡 更新打卡"
        assertTrue(segmentDone(page, "上班"))
        assertTrue(segmentDone(page, "下班"))
    }

    private fun segmentDone(blob: String, key: String): Boolean {
        val idx = blob.indexOf(key)
        if (idx < 0) return false
        val window = blob.substring(idx, minOf(blob.length, idx + 60))
        return window.contains("已打卡")
    }
}
