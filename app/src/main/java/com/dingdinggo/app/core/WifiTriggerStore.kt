package com.dingdinggo.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 公司 Wi-Fi 自动打卡的配置与连接状态存储。
 *
 * 复用 `autoattend_prefs`，与蓝牙触发源共同遵循 [BtPunchRule] 早到窗口与红线。
 */
object WifiTriggerStore {

    private const val TAG = "WifiTriggerStore"
    private const val PREFS_NAME = "autoattend_prefs"

    private const val KEY_ENABLED = "wifi_trigger_enabled"
    private const val KEY_TARGET_SSID = "wifi_target_ssid"
    private const val KEY_TARGET_BSSID = "wifi_target_bssid"
    private const val KEY_LAST_SEEN_AT = "wifi_last_seen_at"
    private const val KEY_LAST_TRIGGER_AT = "wifi_last_trigger_at"
    private const val KEY_LAST_TRIGGER_TEXT = "wifi_last_trigger_text"

    @Volatile
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _targetSsid = MutableStateFlow("")
    val targetSsid: StateFlow<String> = _targetSsid.asStateFlow()

    private val _targetBssid = MutableStateFlow("")
    val targetBssid: StateFlow<String> = _targetBssid.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    private val _lastTriggerAt = MutableStateFlow(0L)
    val lastTriggerAt: StateFlow<Long> = _lastTriggerAt.asStateFlow()

    private val _lastTriggerText = MutableStateFlow("")
    val lastTriggerText: StateFlow<String> = _lastTriggerText.asStateFlow()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        reload()
    }

    private fun prefs(): SharedPreferences =
        requireNotNull(appContext).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun reload() {
        val p = prefs()
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _targetSsid.value = p.getString(KEY_TARGET_SSID, "") ?: ""
        _targetBssid.value = p.getString(KEY_TARGET_BSSID, "") ?: ""
        _lastSeenAt.value = p.getLong(KEY_LAST_SEEN_AT, 0L)
        _lastTriggerAt.value = p.getLong(KEY_LAST_TRIGGER_AT, 0L)
        _lastTriggerText.value = p.getString(KEY_LAST_TRIGGER_TEXT, "") ?: ""
    }

    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (!value) {
            _connected.value = false
        }
    }

    fun saveNetwork(ssid: String, bssid: String) {
        prefs().edit()
            .putString(KEY_TARGET_SSID, ssid)
            .putString(KEY_TARGET_BSSID, bssid)
            .apply()
        _targetSsid.value = ssid
        _targetBssid.value = bssid
        Log.i(TAG, "已记录公司 Wi-Fi ssid=" + ssid + " bssid=" + bssid)
    }

    fun clearNetwork() {
        prefs().edit()
            .remove(KEY_TARGET_SSID)
            .remove(KEY_TARGET_BSSID)
            .apply()
        _targetSsid.value = ""
        _targetBssid.value = ""
        _connected.value = false
    }

    fun markConnected(connected: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        _connected.value = connected
        if (connected) {
            prefs().edit().putLong(KEY_LAST_SEEN_AT, nowMillis).apply()
            _lastSeenAt.value = nowMillis
        }
    }

    fun markTriggered(nowMillis: Long, text: String) {
        prefs().edit()
            .putLong(KEY_LAST_TRIGGER_AT, nowMillis)
            .putString(KEY_LAST_TRIGGER_TEXT, text)
            .apply()
        _lastTriggerAt.value = nowMillis
        _lastTriggerText.value = text
    }
}
