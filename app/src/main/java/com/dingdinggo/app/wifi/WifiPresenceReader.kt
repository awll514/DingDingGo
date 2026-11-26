package com.dingdinggo.app.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.dingdinggo.app.core.WifiTriggerStore
import com.dingdinggo.app.exec.RootShell

/**
 * 读取当前连接的公司 Wi-Fi 信息。
 *
 * 先通过 ConnectivityManager 判断确实连着 Wi-Fi（未连 Wi-Fi 时直接返回 null，
 * 绝不 fork `su` 消耗电量）；若在后台拿不到 SSID，再通过 `cmd wifi status` 兜底。
 */
object WifiPresenceReader {

    private const val TAG = "WifiReader"

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return findWifiCapabilities(cm) != null
    }

    fun readCurrentWifi(
        context: Context,
        allowRootFallback: Boolean = true,
        knownCapabilities: NetworkCapabilities? = null,
    ): WifiSnapshot? {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val wifiCaps = knownCapabilities?.takeIf {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } ?: findWifiCapabilities(cm)

        if (wifiCaps == null) {
            return null
        }

        // Tier 1：系统公开 API（NetworkCapabilities.transportInfo / WifiManager.connectionInfo）
        var ssid = ""
        var bssid = ""

        val transportWifi = runCatching { wifiCaps.transportInfo as? WifiInfo }.getOrNull()
        if (transportWifi != null) {
            ssid = WifiStatusParser.normalizeSsid(transportWifi.ssid)
            bssid = WifiStatusParser.normalizeBssid(transportWifi.bssid)
        }

        if (ssid.isEmpty() || bssid.isEmpty()) {
            @Suppress("DEPRECATION")
            val legacyInfo = runCatching {
                (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            }.getOrNull()
            if (legacyInfo != null) {
                if (ssid.isEmpty()) ssid = WifiStatusParser.normalizeSsid(legacyInfo.ssid)
                if (bssid.isEmpty()) bssid = WifiStatusParser.normalizeBssid(legacyInfo.bssid)
            }
        }

        if (ssid.isNotEmpty()) {
            return WifiSnapshot(ssid = ssid, bssid = bssid)
        }

        // Tier 2：后台无定位权限时，系统 API 可能返回 <unknown ssid>，仅在确实连着 Wi-Fi 时用 Root 读一次
        if (allowRootFallback && RootShell.isAvailable()) {
            val out = runCatching { RootShell.exec("cmd wifi status", 5_000) }.getOrDefault("")
            val parsed = WifiStatusParser.parseCmdWifiStatus(out)
            if (parsed != null) {
                val mergedBssid = parsed.bssid.ifEmpty { bssid }
                return WifiSnapshot(ssid = parsed.ssid, bssid = mergedBssid)
            }
        }

        if (bssid.isNotEmpty()) {
            return WifiSnapshot(ssid = "", bssid = bssid)
        }
        return null
    }

    fun learnCurrentWifi(context: Context): Pair<Boolean, String> {
        val app = context.applicationContext
        val connected = isWifiConnected(app)
        if (!connected) {
            return false to "当前未连接 Wi-Fi，请先连接公司 Wi-Fi 后重试"
        }

        val snap = readCurrentWifi(app, allowRootFallback = true)
        if (snap == null || (snap.ssid.isEmpty() && snap.bssid.isEmpty())) {
            Log.w(TAG, "已连接 Wi-Fi 但拿不到 SSID/BSSID")
            return false to "无法读取当前 Wi-Fi 名称，请授予定位权限或确认 Root 授权后重试"
        }

        WifiTriggerStore.saveNetwork(snap.ssid, snap.bssid)
        val display = snap.ssid.ifBlank { snap.bssid }
        return true to ("已记录公司 Wi-Fi「" + display + "」")
    }

    private fun findWifiCapabilities(cm: ConnectivityManager): NetworkCapabilities? {
        val activeCaps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return activeCaps
        }
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks }.getOrNull() ?: return null
        for (net in all) {
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return caps
            }
        }
        return null
    }
}
