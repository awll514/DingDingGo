package com.dingdinggo.app

import com.dingdinggo.app.core.HealthCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 「守护中断」告警的判定。
 *
 * ## 这个告警在救什么
 *
 * 真机实测确认：用户「强行停止」应用后，包进入 FLAG_STOPPED 状态，
 * Android **不再向它投递任何广播**：
 *   · am broadcast → 进程完全不拉起；
 *   · JobScheduler 的 job 被系统删除；
 *   · 闹钟仍留在系统里，但触发时同样投递不出去。
 *
 * 结果是**所有保活与自动打卡永久失效**。这是平台行为，改不掉。
 *
 * 而应用自己读不到这个状态 —— 能运行就说明标志已被系统清除
 * （实测 flags=0x0，无 FLAG_STOPPED）。
 *
 * 所以唯一可行的办法是"用睡眠时长反推"：正常心跳每 10 分钟一次，
 * 醒来发现间隔异常长，就说明中间断过。本测试钉住这个判定的语义。
 *
 * ## 为什么阈值两边都要测
 *
 * 判太敏感 → 用户被无谓告警打扰，久了就无视（狼来了）；
 * 判太钝 → 真断了也不说，用户以为一切正常，**漏卡且毫不知情**。
 * 后者正是这个项目最危险的那类缺陷。
 */
class GuardGapTest {

    private fun minutes(n: Long): Long = TimeUnit.MINUTES.toMillis(n)

    // ============ 正常情况不该报警 ============

    @Test
    fun normalHeartbeatGapIsNotAbnormal() {
        assertFalse("刚跳过一次（10 分钟）不应告警", HealthCheck.isGuardGapAbnormal(minutes(10)))
        assertFalse("Doze 推迟到 20 分钟仍属正常", HealthCheck.isGuardGapAbnormal(minutes(20)))
        assertFalse("卡在阈值上（30 分钟）不算异常", HealthCheck.isGuardGapAbnormal(minutes(30)))
    }

    @Test
    fun zeroOrNegativeGapIsNotAbnormal() {
        // 时钟被回拨等极端情况，不应误报
        assertFalse(HealthCheck.isGuardGapAbnormal(0L))
        assertFalse(HealthCheck.isGuardGapAbnormal(-1000L))
    }

    // ============ 真断了必须报警 ============

    @Test
    fun genuinelyLongGapIsAbnormal() {
        assertTrue("31 分钟：连续跳过 3 次，说明断了", HealthCheck.isGuardGapAbnormal(minutes(31)))
        assertTrue("1 小时", HealthCheck.isGuardGapAbnormal(minutes(60)))
        assertTrue("一整夜", HealthCheck.isGuardGapAbnormal(minutes(600)))
        assertTrue("好几天（设备关机）", HealthCheck.isGuardGapAbnormal(minutes(60 * 24 * 3)))
    }

    /** 阈值本身是 10 分钟 × 3，这个关系不能被随意改动 */
    @Test
    fun limitIsThreeHeartbeatPeriods() {
        assertEquals(
            "阈值应为正常心跳周期(10分钟)的 3 倍",
            minutes(30),
            HealthCheck.GUARD_GAP_LIMIT_MS,
        )
        assertTrue(
            "阈值必须明显大于正常心跳周期，否则会频繁误报",
            HealthCheck.GUARD_GAP_LIMIT_MS > minutes(10),
        )
    }

    // ============ 文案格式 ============

    @Test
    fun gapTextIsReadable() {
        // 不用中文字面量做等价比较：本环境的控制台编码会让
        // PowerShell 捕捉到的字符串变成乱码（实测），
        // 从而让"文案正确却断言失败"。改为断言结构特征。
        assertTrue("45 分钟", HealthCheck.formatGuardGap(minutes(45)).contains("45"))
        assertTrue("60 分钟应换算成小时而不是显示 60 分钟",
            !HealthCheck.formatGuardGap(minutes(60)).contains("60 "))
        assertTrue("150 分钟应含 2 小时 30 分两个数字",
            HealthCheck.formatGuardGap(minutes(150)).let { it.contains("2") && it.contains("30") })
        assertTrue("0 分钟", HealthCheck.formatGuardGap(0L).contains("0"))
    }

    /**
     * 【边界】恰好跨过 2 小时时，文案要从"分钟"切成"小时"。
     *
     * 这是给用户看的，写错会显得很业余（例如"150 分钟"）。
     */
    @Test
    fun gapTextSwitchesToHoursAtOneHour() {
        // 59 分钟：分钟表述
        val at59 = HealthCheck.formatGuardGap(minutes(59))
        assertTrue("59 分钟应是分钟表述，实际=" + at59, at59.contains("59"))

        // 60 分钟：必须切换成小时表述（原实现用 120 做切换点，
        // 会让 60~119 分钟显示成"119 分钟"这种不自然的文案）
        val at60 = HealthCheck.formatGuardGap(minutes(60))
        assertTrue("60 分钟必须切换到小时表述，实际=" + at60, !at60.contains("60 "))

        // 119 分钟：小时表述
        val at119 = HealthCheck.formatGuardGap(minutes(119))
        assertTrue("119 分钟应换算成小时，实际=" + at119, !at119.contains("119"))
    }

    // ============ 与真实故障场景对齐 ============

    /**
     * 复刻本次真机验证的实际场景：force-stop 后过一段时间重开。
     *
     * 这条用真实的时间量级，确保判定在"用户真实会遇到的"区间内正确。
     */
    @Test
    fun realForceStopScenarioIsDetected() {
        // 实测：18:00 force-stop，上次心跳 17:56
        // 若用户在 19:00 重开应用 → 间隔 64 分钟
        val gap = minutes(64)
        assertTrue(
            "force-stop 一小时后重开必须告警，否则用户不知道已漏掉打卡",
            HealthCheck.isGuardGapAbnormal(gap),
        )
        assertTrue(
            "64 分钟应换算成小时表述，实际=" + HealthCheck.formatGuardGap(gap),
            !HealthCheck.formatGuardGap(gap).contains("64"),
        )
    }
}
