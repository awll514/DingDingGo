package com.dingdinggo.app

import com.dingdinggo.app.exec.ControlHudCopyFormatter
import com.dingdinggo.app.exec.ControlHudTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlHudCopyTest {

    @Test
    fun interactiveCopy_usesAnimeFlavorForRunningPausedAndPreview() {
        val running = ControlHudCopyFormatter.formatInteractiveCopy(
            paused = false,
            rawTitle = "正在接管屏幕",
            rawSubtitle = "钉钉GO 正在操作界面",
        )
        assertTrue(running.title.contains("钉钉娘"))
        assertTrue(running.subtitle.contains("考勤"))

        val paused = ControlHudCopyFormatter.formatInteractiveCopy(
            paused = true,
            rawTitle = "已暂停",
            rawSubtitle = "点 ▶ 继续执行，或点 X 取消",
        )
        assertEquals("☕ 摸鱼暂停中~", paused.title)
        assertTrue(paused.subtitle.contains("继续") && paused.subtitle.contains("取消"))

        val preview = ControlHudCopyFormatter.formatInteractiveCopy(
            paused = false,
            rawTitle = "屏幕接管提示 · 预览",
            rawSubtitle = "可左右拖拽贴边 · 点击钉钉娘收起/展开",
        )
        assertTrue(preview.subtitle.contains("收起/展开"))
    }

    @Test
    fun statusCopy_mapsSuccessLateAndFailureWithRealDetails() {
        val successMorning930 = ControlHudCopyFormatter.formatStatusCopy(
            tone = ControlHudTone.SUCCESS,
            rawTitle = "✅ 上班打卡成功",
            rawSubtitle = "09:30:12",
            clockInMinute = 9 * 60 + 30,
        )
        assertEquals("✌️ 09:30 上班元气打卡搞定~", successMorning930.title)
        assertEquals("真实考勤时间：09:30:12", successMorning930.subtitle)

        val successMorning830 = ControlHudCopyFormatter.formatStatusCopy(
            tone = ControlHudTone.SUCCESS,
            rawTitle = "✅ 上班打卡成功",
            rawSubtitle = "08:29:40",
            clockInMinute = 8 * 60 + 30,
        )
        assertEquals("✌️ 08:30 上班元气打卡搞定~", successMorning830.title)

        val successNoSchedule = ControlHudCopyFormatter.formatStatusCopy(
            tone = ControlHudTone.SUCCESS,
            rawTitle = "✅ 上班打卡成功",
            rawSubtitle = "09:00:05",
            clockInMinute = null,
        )
        assertEquals("✌️ 上班元气打卡搞定~", successNoSchedule.title)

        val failure = ControlHudCopyFormatter.formatStatusCopy(
            tone = ControlHudTone.FAILURE,
            rawTitle = "⚠️ 打卡失败",
            rawSubtitle = "点击 · 未找到打卡按钮",
        )
        assertTrue(failure.title.contains("卡住了"))
        assertEquals("点击 · 未找到打卡按钮", failure.subtitle)
    }
}
