package com.dingdinggo.app

import com.dingdinggo.app.exec.AccessibilityTextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H5 / UCWebView 打卡按钮文案标准化与匹配规则测试。
 */
class AccessibilityLabelMatchTest {

    @Test
    fun compactLabelRemovesSpacesTabsAndNewlines() {
        assertEquals("下班打卡", AccessibilityTextMatcher.compactLabel("下班 \n\t 打卡"))
        assertEquals("上班打卡", AccessibilityTextMatcher.compactLabel(" 上班\r\n打卡 "))
    }

    @Test
    fun exactMatchHandlesSplitWhitespaceInsideButton() {
        assertTrue(AccessibilityTextMatcher.matchesExactLabel("下班打卡", "下班打卡"))
        assertTrue(AccessibilityTextMatcher.matchesExactLabel("下班 打卡", "下班打卡"))
        assertTrue(AccessibilityTextMatcher.matchesExactLabel("下班\n 打卡", "下班打卡"))
        assertFalse(AccessibilityTextMatcher.matchesExactLabel("上班打卡", "下班打卡"))
    }

    @Test
    fun containsMatchHandlesButtonWithClockAndFieldSubtitle() {
        assertTrue(AccessibilityTextMatcher.matchesContainsLabel("外勤\n下班 打卡\n18:30:02", "下班打卡"))
        assertTrue(AccessibilityTextMatcher.matchesContainsLabel("下班打卡 18:30:05", "下班打卡"))
        assertFalse(AccessibilityTextMatcher.matchesContainsLabel("考勤记录", "下班打卡"))
    }
}
