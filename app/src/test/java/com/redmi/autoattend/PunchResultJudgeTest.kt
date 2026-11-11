package com.redmi.autoattend

import com.redmi.autoattend.exec.PunchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 打卡结果判定测试。
 *
 * ## 为什么这个测试比真机验证更有价值
 *
 * 真机验证一次打卡要在用户的考勤系统里留一条真实记录，
 * 失败重试就是重复记录 —— 代价太高，不能用来跑边界用例。
 *
 * 而判定逻辑（[PunchEngine.judgeResult]）是纯函数，
 * 可以覆盖真机上几乎不可能构造出来的场景：
 *   · 极速打卡（进页面时已经打好了）
 *   · 上班已打、下班未打的混淆态
 *   · 页面同时出现成功与失败文案的脏数据
 *   · 网络异常
 *
 * 这些用例直接决定了"打卡结果能不能被正确返回"。
 */
class PunchResultJudgeTest {

    // ---------- 成功信号 ----------

    @Test
    fun successCardIsRecognized() {
        val blob = "考勤打卡 09:30 打卡成功 查看详情"
        val r = PunchEngine.judgeResult(blob, alreadyDoneBefore = false, doneNow = false)
        assertEquals("打卡成功", r)
    }

    @Test
    fun allSuccessVariantsWork() {
        // 钉钉不同版本/不同班次的成功文案
        listOf("打卡成功", "考勤成功", "打卡完成", "更新成功").forEach { word ->
            val r = PunchEngine.judgeResult(
                "页面文本 $word 其他内容",
                alreadyDoneBefore = false,
                doneNow = false,
            )
            assertEquals(word, r)
        }
    }

    // ---------- 失败信号优先 ----------

    @Test
    fun failureWinsOverSuccess() {
        // 脏数据：同时出现成功与失败文案。
        // 必须判失败 —— 报成功而实际没打上是最坏的结果。
        val blob = "打卡成功 但是 不在打卡范围"
        val r = PunchEngine.judgeResult(blob, alreadyDoneBefore = false, doneNow = true)
        assertNull("失败信号必须优先于成功信号", r)
    }

    @Test
    fun networkErrorIsFailure() {
        val blob = "打卡失败 网络异常，请稍后重试"
        assertNull(PunchEngine.judgeResult(blob, false, false))
    }

    @Test
    fun outOfRangeIsFailure() {
        val blob = "不在打卡范围 请在公司范围内打卡"
        assertNull(PunchEngine.judgeResult(blob, false, false))
    }

    // ---------- 极速打卡 / 状态翻转 ----------

    @Test
    fun fastPunchStateFlipIsSuccess() {
        // 极速打卡：没有"打卡成功"卡片，但状态从"未打卡"变成"已打卡"
        val blob = "上班09:30 09:30已打卡"
        val r = PunchEngine.judgeResult(blob, alreadyDoneBefore = false, doneNow = true)
        assertNotNull("状态由未打卡变为已打卡应判成功", r)
        assertEquals("目标时段由未打卡变为已打卡（极速打卡/卡片更新）", r)
    }

    @Test
    fun alreadyDoneBeforeDoesNotCountAsSuccess() {
        // 【关键用例】上班早就打过了，现在点下班卡但没打上。
        // 页面顶部依然显示"上班 09:30已打卡"。
        // 如果无脑认"已打卡 = 成功"，就会把漏打的下班卡报成成功。
        val blob = "上班09:30 09:30已打卡 下班18:30 未打卡"
        val r = PunchEngine.judgeResult(blob, alreadyDoneBefore = true, doneNow = true)
        assertNull("点击前就已完成，不能算本次成功", r)
    }

    @Test
    fun doneButNoFlipIsNull() {
        // 点击前后都是未完成 → 没打上
        val blob = "上班09:30 未打卡 下班18:30 未打卡"
        assertNull(PunchEngine.judgeResult(blob, false, false))
    }

    @Test
    fun flipAfterClickWithNoSuccessText() {
        // 点了按钮，钉钉只静默更新状态卡，不弹成功提示
        val blob = "上班09:30 09:30已打卡"
        val r = PunchEngine.judgeResult(blob, alreadyDoneBefore = false, doneNow = true)
        assertNotNull(r)
    }

    // ---------- 空/异常输入 ----------

    @Test
    fun emptyBlobIsNotSuccess() {
        assertNull(PunchEngine.judgeResult("", false, false))
    }

    @Test
    fun irrelevantTextIsNotSuccess() {
        val blob = "消息 工作台 通讯录 更多"
        assertNull(PunchEngine.judgeResult(blob, false, false))
    }

    @Test
    fun successTextWithoutDoneFlagStillWorks() {
        // 有些版本成功卡片文案出现，但状态卡的 done 标记还没刷新
        val r = PunchEngine.judgeResult("打卡成功", alreadyDoneBefore = false, doneNow = false)
        assertEquals("打卡成功", r)
    }

    // ---------- 组合场景矩阵 ----------

    @Test
    fun fullMatrix() {
        data class Case(
            val name: String,
            val blob: String,
            val before: Boolean,
            val now: Boolean,
            val expectSuccess: Boolean,
        )

        val cases = listOf(
            Case("普通成功", "打卡成功", false, true, true),
            Case("普通成功但状态未刷新", "打卡成功", false, false, true),
            Case("极速打卡", "09:30已打卡", false, true, true),
            Case("之前就打过", "09:30已打卡", true, true, false),
            Case("完全没反应", "上班09:30 未打卡", false, false, false),
            Case("网络失败", "网络异常", false, false, false),
            Case("失败优先", "打卡成功 不在打卡范围", false, true, false),
            Case("空白页", "", false, false, false),
        )

        for (c in cases) {
            val r = PunchEngine.judgeResult(c.blob, c.before, c.now)
            if (c.expectSuccess) {
                assertNotNull("用例[" + c.name + "] 应判成功", r)
            } else {
                assertNull("用例[" + c.name + "] 应判失败", r)
            }
        }
    }
}
