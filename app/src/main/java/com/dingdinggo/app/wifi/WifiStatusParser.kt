package com.dingdinggo.app.wifi

/**
 * 当前连接的 Wi-Fi 信息快照与纯解析规则（无 Android 依赖，可 JVM 单测）。
 */
data class WifiSnapshot(
    val ssid: String,
    val bssid: String,
)

object WifiStatusParser {

    private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val CONNECTED_TO_QUOTED_REGEX = Regex("Wifi\\s+is\\s+connected\\s+to\\s+\"([^\"]+)\"")
    private val SSID_QUOTED_REGEX = Regex("\\bSSID:\\s*\"([^\"]+)\"")
    private val SSID_BARE_REGEX = Regex("\\bSSID:\\s*([^,\\r\\n]+)")
    private val BSSID_REGEX = Regex("\\bBSSID:\\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")

    /** 清洗 SSID，去除首尾双引号并剔除系统占位符 */
    fun normalizeSsid(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val unquoted = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        if (unquoted.isEmpty()) return ""
        val lower = unquoted.lowercase()
        if (lower == "<unknown ssid>" || lower == "0x" || lower == "<none>" || lower == "null") {
            return ""
        }
        return unquoted
    }

    /** 清洗 BSSID，统一转成大写 MAC 并剔除系统脱敏占位符 */
    fun normalizeBssid(raw: String?): String {
        val upper = raw?.trim()?.uppercase().orEmpty()
        if (upper.isEmpty()) return ""
        if (upper == "02:00:00:00:00:00" || upper == "00:00:00:00:00:00") return ""
        if (!MAC_REGEX.matches(upper)) return ""
        return upper
    }

    /**
     * 解析 `cmd wifi status` 的标准输出（Android 12–16 / HyperOS 3）。
     */
    fun parseCmdWifiStatus(output: String): WifiSnapshot? {
        if (output.isBlank()) return null
        if (output.contains("Wifi is disabled", ignoreCase = true)) return null
        if (output.contains("Wifi is not connected", ignoreCase = true) &&
            !output.contains("Wifi is connected to", ignoreCase = true)
        ) {
            return null
        }

        val rawSsid = SSID_QUOTED_REGEX.find(output)?.groupValues?.getOrNull(1)
            ?: CONNECTED_TO_QUOTED_REGEX.find(output)?.groupValues?.getOrNull(1)
            ?: SSID_BARE_REGEX.find(output)?.groupValues?.getOrNull(1)
        val ssid = normalizeSsid(rawSsid)

        val rawBssid = BSSID_REGEX.find(output)?.groupValues?.getOrNull(1)
        val bssid = normalizeBssid(rawBssid)

        if (ssid.isEmpty() && bssid.isEmpty()) return null
        return WifiSnapshot(ssid = ssid, bssid = bssid)
    }

    /**
     * 判断当前 Wi-Fi 是否命中已记录的公司 Wi-Fi。
     *
     * 优先按精确 SSID 匹配（兼容公司多 AP / 不同楼层路由器漫游）；
     * 若系统后台隐藏了当前 SSID，才回退到精确 BSSID 匹配。
     */
    fun matchesTarget(
        actual: WifiSnapshot?,
        targetSsid: String,
        targetBssid: String,
    ): Boolean {
        if (actual == null) return false
        val cleanTargetSsid = normalizeSsid(targetSsid)
        val cleanTargetBssid = normalizeBssid(targetBssid)
        if (cleanTargetSsid.isEmpty() && cleanTargetBssid.isEmpty()) return false

        val actualSsid = normalizeSsid(actual.ssid)
        val actualBssid = normalizeBssid(actual.bssid)

        if (cleanTargetSsid.isNotEmpty()) {
            if (actualSsid.isNotEmpty()) {
                return actualSsid == cleanTargetSsid
            }
            return cleanTargetBssid.isNotEmpty() &&
                actualBssid.isNotEmpty() &&
                actualBssid.equals(cleanTargetBssid, ignoreCase = true)
        }

        return actualBssid.isNotEmpty() && actualBssid.equals(cleanTargetBssid, ignoreCase = true)
    }
}
