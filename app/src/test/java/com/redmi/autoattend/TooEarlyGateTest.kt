package com.redmi.autoattend

import com.redmi.autoattend.exec.PunchResult
import com.redmi.autoattend.exec.PunchStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「太早被拒」不能吃掉当天闹钟。
 *
 * ## 这条用例挡的是一个真机上真实发生的漏卡
 *
 * 2026-11-12 12:48，真机（Redmi K90 / M332BF）上手动触发了一次下班卡：
 *
 *     安全闸门：kind=CLOCK_OUT 目标=18:30:00 偏差=-20477秒
 *     拒绝：下班时间未到，还差 341 分 19 秒
 *
 * 拒绝本身是正确的（下班之前绝不能打下班卡）。
 * 但收尾时 PunchForegroundService 用
 *
 *     markFired = result.success || result.abandonedByGate
 *
 * 判断“这一班是否已经交代完毕”。于是这次**提前拒绝**被当成处理完了：
 * AlarmScheduler.onPunchFinished(markFired = true) → scheduleNext 顺延到下一班
 * → render() 先 cancelAll(previous) ——
 * **当天 18:30 的 PREWARM / FIRE / RETRY 三段闹钟被一起取消**，直接排到了明天 09:30。
 *
 * 真机证据：拒绝之后 dumpsys alarm 里只剩
 * RTC_WAKEUP origWhen=1794533325000（明天 09:28:45），当天的 18:30 三段全部消失。
 *
 * ## 修法
 *
 * PunchResult 增加 rejectedTooEarly。语义：
 * “太早”不是“这一班处理完了”，而是“什么都没发生，闹钟必须原样留着”。
 *
 * markFired 相应改为：
 *
 *     result.success || (result.abandonedByGate && !result.rejectedTooEarly)
 *
 * ## 为什么这个测试有意义
 *
 * markFired 的表达式本身是服务里的内联逻辑（依赖 Android Service），
 * 但 PunchResult 的**默认值与字段语义**是纯数据，可以在 JVM 上钉死。
 * 这里锁住的是契约：太早被拒的实例必须带着 rejectedTooEarly，
 * 且不能只带 abandonedByGate 就算数。
 */
class TooEarlyGateTest {

    /** 模拟时间闸门拒绝“下班太早”时构造出来的结果 */
    private fun earlyClockOutRejection(): PunchResult = PunchResult(
        success = false,
        stage = PunchStage.CLICK,
        message = "距下班时间还有 341 分 19 秒，已阻止提前打下班卡",
        abandonedByGate = true,
        rejectedTooEarly = true,
    )

    /** 模拟“已过迟到边界，为避免迟到记录跳过”（这是真正的“本班处理完了”） */
    private fun lateGaveUp(): PunchResult = PunchResult(
        success = false,
        stage = PunchStage.CLICK,
        message = "已过上班时间 2 分 10 秒，为避免迟到记录已跳过",
        abandonedByGate = true,
        rejectedTooEarly = false,
    )

    private fun markFired(r: PunchResult): Boolean =
        r.success || (r.abandonedByGate && !r.rejectedTooEarly)

    // ---------- 默认值契约 ----------

    @Test
    fun `默认结果不是太早被拒`() {
        val plain = PunchResult(
            success = false,
            stage = PunchStage.CLICK,
            message = "x",
        )
        assertFalse(
            "rejectedTooEarly 默认必须是 false，否则所有失败都会变成保留闹钟",
            plain.rejectedTooEarly,
        )
    }

    @Test
    fun `普通失败不会标记本班已处理`() {
        val failed = PunchResult(
            success = false,
            stage = PunchStage.UNLOCK,
            message = "解锁失败",
        )
        assertFalse(
            "执行失败必须留出 RETRY 兜底，不能标记本班已处理",
            markFired(failed),
        )
    }

    // ---------- 核心回归 ----------

    @Test
    fun `太早被拒不能取消当天闹钟`() {
        val early = earlyClockOutRejection()
        assertTrue("前提：太早被拒仍属于 abandonedByGate", early.abandonedByGate)
        assertTrue("前提：必须带上 rejectedTooEarly", early.rejectedTooEarly)
        assertFalse(
            "【P0】太早被拒如果被标记为本班已处理，当天 18:30 的下班闹钟会被取消 —— 真机已复现漏卡",
            markFired(early),
        )
    }

    @Test
    fun `迟到放弃仍然顺延下一班`() {
        val late = lateGaveUp()
        assertTrue(
            "迟到边界之后主动放弃 = 这一班确实处理完了，应顺延下一班，否则会反复重试",
            markFired(late),
        )
    }

    @Test
    fun `成功必然标记已处理`() {
        val ok = PunchResult(
            success = true,
            stage = PunchStage.FINISH,
            message = "打卡成功",
        )
        assertTrue("成功后必须顺延，不能再打一次", markFired(ok))
    }

    @Test
    fun `两个班次的太早拒绝都不会吃掉闹钟`() {
        // 上班卡太早（还没到预热窗口）同样不能标记：
        // 否则当天 09:30 的上班卡会被取消。
        val earlyIn = PunchResult(
            success = false,
            stage = PunchStage.CLICK,
            message = "距上班时间还有 200 秒，已阻止提前打上班卡",
            abandonedByGate = true,
            rejectedTooEarly = true,
        )
        assertFalse("上班卡太早被拒同样必须保留闹钟", markFired(earlyIn))
    }
}
