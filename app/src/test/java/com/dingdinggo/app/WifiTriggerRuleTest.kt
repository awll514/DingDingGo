package com.dingdinggo.app

import com.dingdinggo.app.wifi.WifiSnapshot
import com.dingdinggo.app.wifi.WifiStatusParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WifiStatusParser] Wi-Fi 归一化、`cmd wifi status` 解析与目标匹配单元测试。
 */
class WifiTriggerRuleTest {

    @Test
    fun `normalizeSsid strips quotes and filters system placeholders`() {
        assertEquals("Corp-5G", WifiStatusParser.normalizeSsid("\"Corp-5G\""))
        assertEquals("Corp-Guest", WifiStatusParser.normalizeSsid("  Corp-Guest  "))
        assertEquals("", WifiStatusParser.normalizeSsid("\"<unknown ssid>\""))
        assertEquals("", WifiStatusParser.normalizeSsid("0x"))
        assertEquals("", WifiStatusParser.normalizeSsid("<none>"))
        assertEquals("", WifiStatusParser.normalizeSsid(null))
    }

    @Test
    fun `normalizeBssid uppercases valid mac and filters anonymized placeholders`() {
        assertEquals("AA:BB:CC:11:22:33", WifiStatusParser.normalizeBssid("aa:bb:cc:11:22:33"))
        assertEquals("", WifiStatusParser.normalizeBssid("02:00:00:00:00:00"))
        assertEquals("", WifiStatusParser.normalizeBssid("00:00:00:00:00:00"))
        assertEquals("", WifiStatusParser.normalizeBssid("not-a-mac"))
    }

    @Test
    fun `parseCmdWifiStatus parses connected ssid and bssid`() {
        val sample = """
            Wifi is enabled
            Wifi is connected to "DingTalk-Office"
            WifiInfo: SSID: "DingTalk-Office", BSSID: ac:85:3d:10:20:30, MAC: 02:00:00:00:00:00, RSSI: -54
        """.trimIndent()

        val parsed = WifiStatusParser.parseCmdWifiStatus(sample)
        assertNotNull(parsed)
        assertEquals("DingTalk-Office", parsed!!.ssid)
        assertEquals("AC:85:3D:10:20:30", parsed.bssid)
    }

    @Test
    fun `parseCmdWifiStatus returns null when disabled or disconnected`() {
        assertNull(WifiStatusParser.parseCmdWifiStatus("Wifi is disabled"))
        assertNull(WifiStatusParser.parseCmdWifiStatus("Wifi is enabled\nWifi is not connected"))
        assertNull(WifiStatusParser.parseCmdWifiStatus(""))
    }

    @Test
    fun `matchesTarget supports multi AP SSID roaming and hidden SSID BSSID fallback`() {
        // 多 AP 同 SSID 漫游（楼层路由器 BSSID 不同）
        assertTrue(
            WifiStatusParser.matchesTarget(
                actual = WifiSnapshot(ssid = "Corp-Office", bssid = "11:22:33:44:55:66"),
                targetSsid = "Corp-Office",
                targetBssid = "AA:BB:CC:DD:EE:FF",
            ),
        )

        // SSID 不一致即拒绝
        assertFalse(
            WifiStatusParser.matchesTarget(
                actual = WifiSnapshot(ssid = "Starbucks", bssid = "AA:BB:CC:DD:EE:FF"),
                targetSsid = "Corp-Office",
                targetBssid = "AA:BB:CC:DD:EE:FF",
            ),
        )

        // 后台系统隐藏 SSID 时回退到 BSSID 精确匹配
        assertTrue(
            WifiStatusParser.matchesTarget(
                actual = WifiSnapshot(ssid = "", bssid = "aa:bb:cc:dd:ee:ff"),
                targetSsid = "Corp-Office",
                targetBssid = "AA:BB:CC:DD:EE:FF",
            ),
        )

        assertFalse(
            WifiStatusParser.matchesTarget(
                actual = WifiSnapshot(ssid = "", bssid = "00:11:22:33:44:55"),
                targetSsid = "Corp-Office",
                targetBssid = "AA:BB:CC:DD:EE:FF",
            ),
        )

        // 未配置目标时不匹配
        assertFalse(
            WifiStatusParser.matchesTarget(
                actual = WifiSnapshot(ssid = "Corp-Office", bssid = "AA:BB:CC:DD:EE:FF"),
                targetSsid = "",
                targetBssid = "",
            ),
        )
    }
}
