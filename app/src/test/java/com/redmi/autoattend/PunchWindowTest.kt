package com.redmi.autoattend

import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.exec.DingTalkUi
import com.redmi.autoattend.exec.PunchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 打卡"可执行窗口"的测试。
 *
 * ## 为什么单开一个类
 *
 * 一次打卡能不能打，取决于**两扇门同时开着**：
 *
 * 1. **时间门**：目标时刻之前（下班卡）/ 迟到边界之后（上班卡）都不许动；
 * 2. **位置门**：页面处于「外勤」状态时绝不能点，否则留下一条外勤记录。
 *
 * 时间门的 if 分支写在 [com.redmi.autoattend.exec.PunchEngine.checkSafetyGate] 里，
 * 是 private suspend 且依赖 Context/AppConfig，JVM 单元测试不可达
 * （见 [SafetyGateTest] 的 @Ignore 说明与给 Lead 的抽取建议）。
 *
 * 本类**只断言能够真正调到的生产逻辑**，不复制任何判定代码：
 *  - 时间门的**窗口边界值**由 [AlarmScheduler.withinRetryWindow] 与
 *    [PunchSchedule] 的 PRE_WARM/RETRY 常量提供 —— 这两个是 public、纯算术、
 *    且正是生产代码里真正用来判断"这次闹钟还算不算数"的函数；
 *  - 位置门直接调 [DingTalkUi.isOutdoor]（生产实现）。
 *
 * ## 什么没有覆盖
 *
 * `checkSafetyGate` 的两个 if、`waitUntilInRange` 的 15 分钟等待循环，
 * 都因为 visibility + Context 依赖无法在此覆盖。已在 [SafetyGateTest]
 * 用 @Ignore 显式挂起，不做假绿。
 */
class PunchWindowTest {

    /** 固定到 2026-11-11（周三），避免测试随机器当前时间漂移 */
    private fun momentOfDay(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 11, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,
        clockOutMinute = 18 * 60,
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    // ==================== 时间门：预热闹钟的提前量 ====================

    /**
     * 闹钟必须在目标时刻**正好 75 秒前**触发（需求 F3.1）。
     *
     * 提前量决定"预热→点按钮"还剩多少准备时间：点屏幕、解锁、拉起钉钉、
     * 等 8 秒就绪 —— 少一秒都可能让点击落在迟到边界外面。
     */
    @Test
    fun prewarmAlarmFiresExactlySeventyFiveSecondsBeforeTarget() {
        val target = momentOfDay(9, 30)
        // 08:00 时上班卡还没到窗口内，nextSlot 会给出未来的触发点
        val slot = PunchSchedule.nextSlot(momentOfDay(8, 0), settings)

        assertNotNull("08:00 时应能算出今天的上班卡", slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        assertEquals("目标时刻应是 09:30", target, slot.targetAtMillis)
        assertEquals(
            "预热闹钟必须提前 75 秒",
            target - PunchSchedule.PRE_WARM_SECONDS * 1000L,
            slot.triggerAtMillis,
        )
        // 硬编码 75_000 而不是引用常量：这里要防的正是"常量被改小"。
        // 用户需求 F3.1 写死了 75 秒，改它必须是一个有意识的决定。
        assertEquals(
            "预热闹钟的提前量必须是需求规定的 75 秒",
            75_000L,
            target - slot.triggerAtMillis,
        )
    }

    /**
     * 三段闹钟（PREWARM / FIRE / RETRY）的时间点必须满足
     * prewarm < fire == target <= retry 的严格顺序。
     *
     * 顺序错乱的后果在生产里是真实的：若 RETRY 早于 FIRE，
     * 兜底那次会在主闹钟之前触发，两条链路同时拉起两个执行。
     */
    @Test
    fun threeStageAlarmsKeepPrewarmFireRetryOrdering() {
        val slot = PunchSchedule.nextSlot(momentOfDay(8, 0), settings)!!
        val prewarmAt = slot.triggerAtMillis
        val fireAt = slot.targetAtMillis
        val retryAt = fireAt + PunchSchedule.RETRY_WINDOW_SECONDS * 1000L

        assertTrue("预热必须早于主闹钟", prewarmAt < fireAt)
        assertEquals("FIRE 必须正好落在目标时刻", momentOfDay(9, 30), fireAt)
        assertTrue("RETRY 必须晚于主闹钟", retryAt > fireAt)
        // 同样用字面量钉住：120 秒是需求 F5 明文规定的补执行窗口。
        assertEquals(
            "RETRY 必须在目标后 120 秒",
            120_000L,
            retryAt - fireAt,
        )
    }

    // ==================== 时间门：补执行窗口的两端边界 ====================

    // withinRetryWindow 的两端边界由 SchedulerGuardTest 覆盖（那里才是它的归宿），
    // 这里只留一条**跨模块一致性**断言：两个模块对"窗口是否还开着"必须给出同一个答案。

    /**
     * 窗口关闭后，[PunchSchedule.nextSlot] 不能再把这个班次当作"下一次打卡"。
     *
     * 这条把两个模块的窗口定义钉在一起：`withinRetryWindow` 说"不算数了"时，
     * `nextSlot` 也必须已经跳到下一班。否则出现
     * "服务认为该班已过期、调度器却还在排该班"的对峙状态。
     */
    @Test
    fun slotIsDroppedOnceRetryWindowHasClosed() {
        val target = momentOfDay(9, 30)
        val justClosed = target + PunchSchedule.RETRY_WINDOW_SECONDS * 1000L + 1_000L

        assertFalse(
            "此时 withinRetryWindow 必须已判否",
            AlarmScheduler.withinRetryWindow(justClosed, target),
        )

        val slot = PunchSchedule.nextSlot(justClosed, settings)
        assertNotNull("应顺延到当天下班卡", slot)
        assertEquals(
            "上班卡的补执行窗口已关闭，下一次必须是下班卡",
            PunchKind.CLOCK_OUT,
            slot!!.kind,
        )
        assertEquals("下班卡目标时刻应是 18:00", momentOfDay(18, 0), slot.targetAtMillis)
    }

    /** 窗口内则相反：同一班次必须仍被返回，否则补执行逻辑永远触发不到 */
    @Test
    fun slotIsStillReturnedOneSecondBeforeRetryWindowCloses() {
        val target = momentOfDay(9, 30)
        val stillOpen = target + PunchSchedule.RETRY_WINDOW_SECONDS * 1000L - 1_000L

        assertTrue(
            "此时 withinRetryWindow 必须仍判是",
            AlarmScheduler.withinRetryWindow(stillOpen, target),
        )

        val slot = PunchSchedule.nextSlot(stillOpen, settings)
        assertNotNull(slot)
        assertEquals("仍应返回本班（上班卡）", PunchKind.CLOCK_IN, slot!!.kind)
        assertEquals("触发时刻应立即补偿为 now", stillOpen, slot.triggerAtMillis)
    }

    // ==================== 位置门：外勤 ====================

    /**
     * 实测的"外勤可打"页面形态。
     *
     * 来自 [DingTalkUi.OUTDOOR_SIGNALS] 的注释（Redmi K90 真机抓取）：
     * 大按钮顶部多一行"外勤"，下方出现"未进入考勤范围，可外勤打卡 /
     * 如需内勤打卡，请检查"。这种形态下点击会生成外勤记录。
     */
    private val outdoorPage =
        "外勤 下班打卡 未进入考勤范围，可外勤打卡 如需内勤打卡，请检查"

    /** 实测的"内勤可打"页面形态：大按钮"下班打卡"，无外勤提示 */
    private val inRangePage = "上班09:30 09:30已打卡 下班18:30 下班打卡"

    @Test
    fun isOutdoorFlagsRealDeviceOutdoorPageAsOutdoor() {
        assertTrue(
            "真机抓到的外勤页必须被生产判定函数识别为外勤",
            DingTalkUi.isOutdoor(outdoorPage),
        )
    }

    @Test
    fun isOutdoorLeavesRealDeviceInRangePageAlone() {
        assertFalse(
            "内勤可打页不能被误判成外勤，否则永远不会点击",
            DingTalkUi.isOutdoor(inRangePage),
        )
    }

    /**
     * 每一个外勤信号都必须能被 [DingTalkUi.isOutdoor] 单独命中。
     *
     * 这是在防"常量加了词、判定函数却没跟着改"的漂移：
     * 钉钉版本更新换一种措辞时，只要往 OUTDOOR_SIGNALS 里加词即可，
     * 该用例保证新词真的生效。
     */
    @Test
    fun everyDeclaredOutdoorSignalIsDetectedOnItsOwn() {
        assertTrue("外勤信号表不应为空", DingTalkUi.OUTDOOR_SIGNALS.isNotEmpty())
        for (signal in DingTalkUi.OUTDOOR_SIGNALS) {
            assertTrue(
                "单独出现的外勤信号\"" + signal + "\"未被 isOutdoor 命中",
                DingTalkUi.isOutdoor("打卡页 " + signal + " 其他文字"),
            )
        }
    }

    /**
     * 页面读不到任何文本时不能判成外勤。
     *
     * 生产里这条对应 `waitUntilInRange` 的 "读不到页面文本，按可打处理"
     * 分支：无障碍还没连上时 blob 为空，此时若误判外勤会白等 15 分钟。
     */
    @Test
    fun emptyPageIsNotTreatedAsOutdoor() {
        assertFalse(
            "空页面不能判成外勤（否则无障碍未就绪时会白等 15 分钟）",
            DingTalkUi.isOutdoor(""),
        )
    }

    /**
     * 纯外勤页面不能被 [PunchEngine.judgeResult] 判成成功。
     *
     * 外勤文案里没有 FAILURE_SIGNALS 的任何一个词，所以它不会走
     * "失败优先"分支；它同样不含成功信号。这条保证"没打成功"不会被
     * 外勤提示骗成成功。
     */
    @Test
    fun outdoorPageAloneIsNotJudgedAsSuccess() {
        assertNull(
            "外勤提示不是成功证据",
            PunchEngine.judgeResult(outdoorPage, alreadyDoneBefore = false, doneNow = false),
        )
    }

    /**
     * 记录一个**已知缺口**：judgeResult 本身拦不住"外勤页 + 状态翻转"。
     *
     * 外勤文案不含 FAILURE_SIGNALS 里的任何词，所以它不会走"失败优先"分支；
     * 此时只要 doneNow 为 true，judgeResult 就会返回状态翻转证据 ——
     * **即使页面明确写着"未进入考勤范围"**。
     *
     * 这意味着唯一的防线是点击前的 `waitUntilInRange`（isOutdoor）闸门。
     * 本用例把这条依赖显式写成断言，而不是假装 judgeResult 会兜住：
     * 一旦有人把闸门挪到 judgeResult 之后，或删掉 isOutdoor 检查，
     * 这里依然全绿，是**故意**的 —— 它描述现实，不描述理想。
     *
     * 建议 Lead 后续把 isOutdoor 判断下沉进 judgeResult 的失败分支
     * （或新增独立参数），届时把本用例改为 assertNull。
     */
    @Test
    fun judgeResultAloneCannotBlockOutdoorPageSoTheOutdoorGateMustRunFirst() {
        assertNull("外勤页不含成功信号", DingTalkUi.containsSuccess(outdoorPage))
        assertNull("外勤页不含失败信号，故不触发失败优先分支", DingTalkUi.containsFailure(outdoorPage))

        val r = PunchEngine.judgeResult(outdoorPage, alreadyDoneBefore = false, doneNow = true)
        assertEquals(
            "已知缺口：状态翻转会让外勤页也产出成功证据，必须靠 isOutdoor 闸门先行拦截",
            "目标时段由未打卡变为已打卡（极速打卡/卡片更新）",
            r,
        )
    }
}