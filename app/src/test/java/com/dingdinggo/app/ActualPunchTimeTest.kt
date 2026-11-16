package com.dingdinggo.app

import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.data.db.PunchLogEntity
import com.dingdinggo.app.data.db.PunchOutcome
import com.dingdinggo.app.exec.DingTalkUi
import com.dingdinggo.app.exec.PunchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ActualPunchTimeTest {

    private fun refMillis(hour: Int = 9, minute: Int = 30): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 16, hour, minute, 37)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun statusCardExtractsRealTimeInsteadOfScheduledHeader() {
        val blob = "上班09:30 09:29已打卡 下班18:30 未打卡 下班打卡 11月16日 周一 09:55:21"
        val res = PunchEngine.extractActualPunchTime(
            blob = blob,
            lines = listOf("上班09:30", "09:29已打卡", "下班18:30", "未打卡", "09:55:21"),
            kind = PunchKind.CLOCK_IN,
            referenceMillis = refMillis(9, 30),
        )
        assertNotNull(res)
        assertEquals("09:29", res?.displayText)
        val cal = Calendar.getInstance().apply { timeInMillis = res!!.millis }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(29, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun twoColumnCardExtractsTargetShiftTimeWithoutCrossContamination() {
        val blob = "上班 09:30 下班 18:30 09:29已打卡 18:30已打卡"
        val clockIn = PunchEngine.extractActualPunchTime(blob, emptyList(), PunchKind.CLOCK_IN, refMillis(9, 30))
        val clockOut = PunchEngine.extractActualPunchTime(blob, emptyList(), PunchKind.CLOCK_OUT, refMillis(18, 30))
        assertEquals("09:29", clockIn?.displayText)
        assertEquals("18:30", clockOut?.displayText)
    }

    @Test
    fun clockOutNotYetDoneIgnoresClockInDoneTime() {
        val blob = "上班09:30 09:29已打卡 下班18:30 未打卡"
        val clockOut = PunchEngine.extractActualPunchTime(
            blob = blob,
            lines = listOf("上班09:30", "09:29已打卡", "下班18:30", "未打卡"),
            kind = PunchKind.CLOCK_OUT,
            referenceMillis = refMillis(18, 30),
        )
        assertNull(clockOut)
    }

    @Test
    fun twoSequentialTopBannersPreferLatestSuccessTime() {
        // 两条顶部横幅：前一条被后一条覆盖，分先后消失后保留在事件缓存中
        val lines = listOf(
            "考勤打卡 极速打卡准备中 09:28:59",
            "09:29:12 上班极速打卡·成功",
        )
        val blob = lines.joinToString(" ")
        val verdict = PunchEngine.judgeResult(blob, alreadyDoneBefore = false, doneNow = false, kind = PunchKind.CLOCK_IN)
        assertNotNull(verdict)
        val extracted = PunchEngine.extractActualPunchTime(blob, lines, PunchKind.CLOCK_IN, refMillis(9, 30))
        assertEquals("09:29:12", extracted?.displayText)
    }

    @Test
    fun fastPunchBannerSuccessVariantsAreRecognized() {
        val variants = listOf(
            "09:29 上班极速打卡成功",
            "已为您极速打卡 09:29:05",
            "极速打卡·成功 18:30",
            "已为您成功打卡 09:30",
            "18:30 下班打卡·成功",
        )
        for (text in variants) {
            assertNotNull("应识别成功文案: $text", DingTalkUi.containsSuccess(text))
            val res = PunchEngine.extractActualPunchTime(text, listOf(text), PunchKind.MANUAL, refMillis())
            assertNotNull("应解析出时间: $text", res)
        }
    }

    @Test
    fun statisticsCardSummaryExtractsTime() {
        val blob = "上班 09:29 极速打卡 下班 18:30 蓝牙打卡"
        val inTime = PunchEngine.extractActualPunchTime(blob, emptyList(), PunchKind.CLOCK_IN, refMillis(9, 30))
        assertEquals("09:29", inTime?.displayText)
    }

    @Test
    fun logDisplayTimePrefersActualTimeThenFinishedAt() {
        val start = refMillis(18, 28)
        val finish = start + 82_000L // 18:29:59
        val withParsed = PunchLogEntity(
            scheduledAt = start,
            startedAt = start,
            finishedAt = finish,
            kind = PunchKind.CLOCK_OUT,
            outcome = PunchOutcome.SUCCESS,
            channel = "accessibility",
            actualAt = refMillis(18, 30),
            actualTimeText = "18:30",
        )
        assertEquals("18:30", withParsed.displayTimeText())

        val withoutParsedSuccess = withParsed.copy(actualAt = 0L, actualTimeText = null)
        assertEquals(PunchSchedule.formatTime(finish), withoutParsedSuccess.displayTimeText())
    }
}
