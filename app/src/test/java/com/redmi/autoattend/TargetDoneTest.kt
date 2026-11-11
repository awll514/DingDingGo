package com.redmi.autoattend

import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.exec.PunchEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "目标时段是否已打卡"判定测试 —— [PunchEngine.isTargetDone]。
 *
 * ## 这个函数为什么值得单独测
 *
 * 打卡页顶部有两张状态卡（上班 / 下班），判定必须只看**目标那张**。
 * 真实文本长这样（uiautomator dump 相邻节点 text 用空格拼接）：
 *
 * ```text
 * 上班09:30 09:30已打卡 下班18:30 下班打卡
 * ```
 *
 * 上面这页里"上班"后面就跟着"已打卡"。如果无脑搜"已打卡"就判下班卡也完成，
 * 下班那张永远不会被打 —— 而用户只会看到一条"今日该时段已打过卡"的成功提示。
 *
 * 反过来也危险：若"上班"的判定窗口一路吃到"下班 …已打卡"，
 * 上班卡会被误判成已完成 → 该打的时候不打（漏卡）。
 *
 * ## 这些用例为什么**不是**同义反复
 *
 * 它们直接调用生产代码里的 `PunchEngine.isTargetDone`（Lead 已把它从
 * private 提升为 internal）。改坏生产实现（窗口宽度、对侧截断、词表）
 * 这里会真的变红。
 *
 * ## 给 Lead 的后续建议
 *
 * `DONE_MARKERS` 仍是 private companion。若一并提升为 `internal val`，
 * 可以再加一条"词表与判定函数同步"的自检用例；目前靠
 * [signInWordingCountsAsDoneJustLikePunchWording] 间接覆盖。
 */
class TargetDoneTest {

    // ==================== 真实页面样本 ====================

    /** 上班已打、下班未打。整页只出现一个"已打卡"，它属于上班卡 */
    private val clockInDoneClockOutPending = "上班09:30 09:30已打卡 下班18:30 下班打卡"

    /** 两班都已打（含"更新打卡"，它**不是**完成标记） */
    private val bothDone = "上班09:30 09:30已打卡 下班18:30 18:30已打卡 更新打卡"

    /** 两班都未打 */
    private val noneDone = "上班 09:00 未打卡 下班 18:30 未打卡"

    /** 只有下班完成：上班卡不能被下班卡的标记串味 */
    private val onlyClockOutDone = "上班 09:00 未打卡 下班 18:30 已打卡"

    /** uiautomator 拼接后的常见形态：只有按钮，没有状态卡 */
    private val buttonsOnly = "上班打卡 下班打卡 09:00"

    /** 空页面（无障碍未就绪） */
    private val emptyPage = ""

    /** 完全无关的页面 */
    private val noKeywordPage = "消息 工作台 通讯录 更多"

    // ==================== 核心语义：只看目标那张卡 ====================

    /**
     * 上班已打、下班没打时必须分别判定。
     *
     * 这条防的是"整页搜到'已打卡'就认为目标时段也完成了"——
     * 那会让下班卡永远打不上。
     */
    @Test
    fun clockInDoneButClockOutPendingIsDistinguishedPerKind() {
        assertTrue(
            "上班卡应判已完成",
            PunchEngine.isTargetDone(clockInDoneClockOutPending, PunchKind.CLOCK_IN),
        )
        assertFalse(
            "下班卡未完成，不能因为页面别处有'已打卡'就判完成",
            PunchEngine.isTargetDone(clockInDoneClockOutPending, PunchKind.CLOCK_OUT),
        )
    }

    /**
     * 反向：只有下班完成时，上班卡不得被下班卡的标记串味。
     *
     * 这是"窗口截断到对侧关键字"那条修正要防的漏卡场景。
     */
    @Test
    fun clockOutDoneDoesNotLeakIntoClockInJudgement() {
        assertFalse(
            "上班卡未完成",
            PunchEngine.isTargetDone(onlyClockOutDone, PunchKind.CLOCK_IN),
        )
        assertTrue(
            "下班卡已完成",
            PunchEngine.isTargetDone(onlyClockOutDone, PunchKind.CLOCK_OUT),
        )
    }

    @Test
    fun bothCardsShowingDoneMakesBothKindsDone() {
        assertTrue(PunchEngine.isTargetDone(bothDone, PunchKind.CLOCK_IN))
        assertTrue(PunchEngine.isTargetDone(bothDone, PunchKind.CLOCK_OUT))
    }

    @Test
    fun neitherCardShowingDoneMakesNeitherKindDone() {
        assertFalse(PunchEngine.isTargetDone(noneDone, PunchKind.CLOCK_IN))
        assertFalse(PunchEngine.isTargetDone(noneDone, PunchKind.CLOCK_OUT))
    }

    /** 只有按钮、没有状态卡时两个班次都不得判完成 */
    @Test
    fun pageWithOnlyButtonsIsNotDoneForEitherKind() {
        assertFalse(PunchEngine.isTargetDone(buttonsOnly, PunchKind.CLOCK_IN))
        assertFalse(PunchEngine.isTargetDone(buttonsOnly, PunchKind.CLOCK_OUT))
    }

    // ==================== 措辞覆盖 ====================

    /**
     * "已签到"必须和"已打卡"等价。
     *
     * 部分钉钉版本/考勤组用签到措辞。生产 `DONE_MARKERS` 有两项，
     * 这条保证第二项不会被漏掉（例如有人精简词表时）。
     */
    @Test
    fun signInWordingCountsAsDoneJustLikePunchWording() {
        val page = "上班09:30 09:30已签到 下班18:30 下班打卡"
        assertTrue(
            "'已签到'必须与'已打卡'等价",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_IN),
        )
        assertFalse(
            "下班卡仍为未完成",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_OUT),
        )
    }

    /**
     * "更新打卡"不是完成标记。
     *
     * 它是"再打一次"的入口。真实文案 "18:30已打卡  更新打卡" 里两者相邻，
     * 若有人把"更新打卡"误当完成信号，未打的下班卡会被判完成。
     */
    @Test
    fun updatePunchButtonAloneIsNotTreatedAsDone() {
        val page = "上班09:30 更新打卡 下班18:30 更新打卡"
        assertFalse(
            "只有'更新打卡'入口、没有完成措辞时不能判完成",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_IN),
        )
        assertFalse(
            "下班卡同理",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_OUT),
        )
    }

    /**
     * "未打卡"必须压过同一窗口里的完成标记。
     *
     * 生产实现显式先排除 NOT_DONE_SIGNALS 再找 DONE_MARKERS。
     * 这条钉住那个优先级。
     */
    @Test
    fun notDoneWordingTakesPrecedenceOverDoneMarkersInSameWindow() {
        val page = "上班 09:00 未打卡"
        assertFalse(
            "'未打卡'所在窗口不得判完成",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_IN),
        )
    }

    // ==================== 退化输入 ====================

    @Test
    fun emptyPageIsNotDoneForEitherKind() {
        assertFalse(PunchEngine.isTargetDone(emptyPage, PunchKind.CLOCK_IN))
        assertFalse(PunchEngine.isTargetDone(emptyPage, PunchKind.CLOCK_OUT))
    }

    @Test
    fun pageWithoutAnyTargetKeywordIsNotDone() {
        assertFalse(PunchEngine.isTargetDone(noKeywordPage, PunchKind.CLOCK_IN))
        assertFalse(PunchEngine.isTargetDone(noKeywordPage, PunchKind.CLOCK_OUT))
    }

    // ==================== MANUAL 的取舍 ====================

    /**
     * 手动打卡（MANUAL）恒为 false。
     *
     * 生产注释："手动打卡不按班次推断，调用方自己决定打哪一班"。
     * 语义后果：手动打卡永远走不到"极速打卡已完成"的捷径，必须真的点击。
     * 把这个取舍钉住，避免有人误以为 MANUAL 也会推断。
     */
    @Test
    fun manualKindIsNeverConsideredAlreadyDone() {
        assertFalse(
            "MANUAL 必须恒为 false（否则手动打卡会被跳过）",
            PunchEngine.isTargetDone(bothDone, PunchKind.MANUAL),
        )
        assertFalse(
            "即使两班都已打卡，MANUAL 仍为 false",
            PunchEngine.isTargetDone(clockInDoneClockOutPending, PunchKind.MANUAL),
        )
    }

    // ==================== 窗口宽度与多次出现 ====================

    /**
     * 状态卡排得很开时仍必须命中（窗口 90 字符，而不是 60）。
     *
     * 生产注释明确："钉钉不同版本会把'上班 09:30 已打卡 更新打卡 补卡'
     * 这组文本排得更开，60 字符有时刚好把'已打卡'截在窗外。"
     *
     * 这份夹具把"上班"与"已打卡"拉开 65 个字符：
     * 90 字符窗口命中，60 字符窗口会漏判（→ 重复打一次）。
     */
    @Test
    fun doneMarkerIsFoundWhenStatusCardIsWidelySpaced() {
        val page = buildString {
            append("上班")
            append("09:30")
            repeat(60) { append("补") }
            append("已打卡")
            append(" 下班18:30 下班打卡")
        }

        val gap = page.indexOf("已打卡") - page.indexOf("上班")
        assertTrue(
            "夹具本身的间隔必须落在 61~89 之间才有判别力，实际=" + gap,
            gap in 61..89,
        )
        assertTrue(
            "间隔 " + gap + " 字符时仍必须判完成（90 字符窗口）",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_IN),
        )
    }

    /**
     * 关键字出现多次时，只要任意一次的窗口内命中就算完成。
     *
     * 生产实现是 while 循环逐次推进，不是只看第一次出现。
     * 出现在"上班"字样的推送/标题在前、真正的状态卡在后的页面。
     */
    @Test
    fun doneIsFoundWhenAnEarlierKeywordOccurrenceHasNoMarkerNearby() {
        val page = "上班提醒 上班音乐 " + "推".repeat(100) + " 上班09:30 09:30已打卡 下班18:30 下班打卡"

        val firstIdx = page.indexOf("上班")
        val markerIdx = page.indexOf("已打卡")
        assertTrue(
            "第一个'上班'的 90 字符窗口必须错过标记才有判别力，实际距离=" + (markerIdx - firstIdx),
            markerIdx - firstIdx > 90,
        )
        assertTrue(
            "后面那次出现命中标记，必须判完成",
            PunchEngine.isTargetDone(page, PunchKind.CLOCK_IN),
        )
    }

    // ==================== 与 judgeResult 的联动 ====================

    /**
     * 点击前基线为"已完成"时，[PunchEngine.judgeResult] 不允许把状态翻转
     * 算成本次成功。
     *
     * 这解释了 `readTargetDoneState`（内部就是 isTargetDone）为什么存在：
     * 若基线读错成 false，"上班已打卡"那页上的下班卡会被判成功。
     */
    @Test
    fun alreadyDoneBeforeSuppressesFlipBasedSuccess() {
        org.junit.Assert.assertNull(
            "基线为已完成后，翻转不能算本次成功",
            PunchEngine.judgeResult(clockInDoneClockOutPending, alreadyDoneBefore = true, doneNow = true),
        )
        org.junit.Assert.assertNotNull(
            "基线为未完成时，翻转才可作为成功证据",
            PunchEngine.judgeResult(clockInDoneClockOutPending, alreadyDoneBefore = false, doneNow = true),
        )
    }
}
