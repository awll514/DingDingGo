package com.dingdinggo.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.dingdinggo.app.data.db.PunchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * 蓝牙触发源的配置与状态存储。
 *
 * ## 为什么不塞进 AppConfig
 *
 * AppConfig 是 439 行的"什么都放"的仓库，而蓝牙这一块有它自己的
 * 完整生命周期（扫描注册 / 心跳 / 今日同步状态 / 已学到的设备指纹），
 * 混进去会让本来就难读的 AppConfig 更难读。
 *
 * 这里单独一个对象，但**复用 AppConfig 的同一个 prefs 文件**，
 * 不新增文件、不引入迁移风险。
 *
 * ## 敏感数据约定
 *
 * 蓝牙设备的 MAC / 名称**不属于**锁屏密码级别的敏感数据 ——
 * 它只会出现在本机设置页和打卡日志里。因此走普通 prefs，
 * 不进 EncryptedSharedPreferences（那里只放锁屏密码）。
 */
object BtTriggerStore {

    private const val TAG = "BtTriggerStore"

    /** 与 AppConfig.PREFS_NAME 保持一致 */
    private const val PREFS_NAME = "autoattend_prefs"

    // ---------- prefs key ----------

    private const val KEY_ENABLED = "bt_trigger_enabled"
    private const val KEY_EARLY_WINDOW_MIN = "bt_early_window_minutes"
    private const val KEY_MAC = "bt_device_mac"
    private const val KEY_NAME = "bt_device_name"
    private const val KEY_SCAN_REGISTERED_AT = "bt_scan_registered_at"
    private const val KEY_SYNCED_IN_DAY = "bt_synced_in_day"
    private const val KEY_SYNCED_OUT_DAY = "bt_synced_out_day"
    private const val KEY_LAST_TRIGGER_AT = "bt_last_trigger_at"
    private const val KEY_LAST_TRIGGER_TEXT = "bt_last_trigger_text"
    private const val KEY_LAST_SEEN_AT = "bt_last_seen_at"

    /** 默认早到窗口 2 小时 */
    const val DEFAULT_EARLY_WINDOW_MIN = 120

    /** 在场判定的有效期：90 秒内扫到过，就算还在场 */
    const val PRESENCE_TTL_MS = 90_000L

    /**
     * 扫描注册状态的"保鲜期"。
     *
     * ## 【真机实测修订，2026-11-13】
     *
     * 旧值是 32 分钟，依据是"KeepAliveService 每 15 分钟自检续期，
     * 2×15 分钟内没续期就说明自检挂了"。
     *
     * **这个依据被真机推翻了**：蓝牙栈会在约 60 秒后自行停掉
     * PendingIntent 扫描（见 BtScanner.startScanRenewal 的长注释）。
     * 也就是说"注册成功过一次"根本不能代表"现在还在扫" ——
     * 32 分钟的保鲜期让健康度卡片在扫描已经停掉 31 分钟的情况下
     * 仍然显示绿色，这正是本功能"看起来正常、实际失效"的直接原因。
     *
     * 现在扫描由 [BtScanner.SCAN_RENEW_INTERVAL_MS]（25 秒）的精确闹钟
     * 续期。保鲜期取 "3 个续期周期"：
     *   · 正常运行时 25 秒内必有续期，永远新鲜；
     *   · 连续 75 秒没有任何续期 → 说明续期闹钟也没了（被清 / 功能被关），
     *     此时如实显示"状态未知"，而不是继续绿着。
     *
     * 宁可偶尔误报"需要确认"，也不要长期假绿 —— 后者会让用户
     * 在完全不知情的情况下错过整个功能的失效。
     */
    const val SCAN_STALE_MS = 75_000L

    @Volatile
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _earlyWindowMinutes = MutableStateFlow(DEFAULT_EARLY_WINDOW_MIN)
    val earlyWindowMinutes: StateFlow<Int> = _earlyWindowMinutes.asStateFlow()

    /**
     * 已学到的考勤机 MAC。
     *
     * 为空时扫描只看 UUID（首次使用状态）；学到之后优先按 MAC 精确匹配，
     * 避免同楼其他考勤机误触发。
     */
    private val _deviceMac = MutableStateFlow("")
    val deviceMac: StateFlow<String> = _deviceMac.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    /** 蓝牙"在场"：最近 [PRESENCE_TTL_MS] 内扫到过考勤机 */
    private val _present = MutableStateFlow(false)
    val present: StateFlow<Boolean> = _present.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    private val _lastTriggerText = MutableStateFlow("")
    val lastTriggerText: StateFlow<String> = _lastTriggerText.asStateFlow()

    private val _lastTriggerAt = MutableStateFlow(0L)
    val lastTriggerAt: StateFlow<Long> = _lastTriggerAt.asStateFlow()

    private val _scanRegistered = MutableStateFlow(false)
    val scanRegistered: StateFlow<Boolean> = _scanRegistered.asStateFlow()

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
        _earlyWindowMinutes.value = p.getInt(KEY_EARLY_WINDOW_MIN, DEFAULT_EARLY_WINDOW_MIN)
            .coerceIn(BtPunchRule.EARLY_WINDOW_RANGE)
        _deviceMac.value = p.getString(KEY_MAC, "") ?: ""
        _deviceName.value = p.getString(KEY_NAME, "") ?: ""
        _lastSeenAt.value = p.getLong(KEY_LAST_SEEN_AT, 0L)
        _lastTriggerAt.value = p.getLong(KEY_LAST_TRIGGER_AT, 0L)
        _lastTriggerText.value = p.getString(KEY_LAST_TRIGGER_TEXT, "") ?: ""
        // 【扫描注册状态的诚实表述】
        //
        // 注册本身只活在蓝牙栈进程的内存里：蓝牙开关切一次、系统对长时扫描
        // 降级（约 30 分钟后转 opportunistic）、或手机重启，它都会消失，
        // 而且**没有任何回调会告诉我们**。
        //
        // 所以这里不能用"曾经注册成功过"就断言"现在还在注册"。
        // 超过 [SCAN_STALE_MS] 没有续期，就认为状态**未知**（false），
        // 由 UI 显示"等待下次自检确认"，而不是假装一切正常。
        //
        // 续期点（按重要性）：
        //   · BtScanner 的 25 秒精确闹钟续期（主力，见 SCAN_STALE_MS 注释）；
        //   · KeepAliveService 每 15 分钟自检（兜底）；
        //   · BootReceiver 开机；
        //   · 用户打开设置页时手动触发。
        val registeredAt = p.getLong(KEY_SCAN_REGISTERED_AT, 0L)
        _scanRegistered.value = registeredAt > 0L &&
            System.currentTimeMillis() - registeredAt <= SCAN_STALE_MS
        refreshPresence()
    }

    // ---------- 写入 ----------

    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setEarlyWindowMinutes(minutes: Int) {
        val v = minutes.coerceIn(BtPunchRule.EARLY_WINDOW_RANGE)
        prefs().edit().putInt(KEY_EARLY_WINDOW_MIN, v).apply()
        _earlyWindowMinutes.value = v
    }

    /** 记下考勤机指纹（学习动作）。空 MAC 表示清除 */
    fun saveDevice(mac: String, name: String) {
        prefs().edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
        _deviceMac.value = mac
        _deviceName.value = name
        Log.i(TAG, "已学习考勤机指纹 mac=" + mac + " name=" + name)
    }

    fun clearDevice() {
        prefs().edit().remove(KEY_MAC).remove(KEY_NAME).apply()
        _deviceMac.value = ""
        _deviceName.value = ""
    }

    fun markScanRegistered(registered: Boolean) {
        val at = if (registered) System.currentTimeMillis() else 0L
        prefs().edit().putLong(KEY_SCAN_REGISTERED_AT, at).apply()
        _scanRegistered.value = registered
    }

    fun scanRegisteredAt(): Long = prefs().getLong(KEY_SCAN_REGISTERED_AT, 0L)

    fun markSeen(nowMillis: Long) {
        prefs().edit().putLong(KEY_LAST_SEEN_AT, nowMillis).apply()
        _lastSeenAt.value = nowMillis
        refreshPresence()
    }

    /** 刷新在场状态：超过有效期即视为离开 */
    fun refreshPresence() {
        val last = _lastSeenAt.value
        _present.value = last > 0L &&
            System.currentTimeMillis() - last <= PRESENCE_TTL_MS
    }

    fun markTriggered(nowMillis: Long, text: String) {
        prefs().edit()
            .putLong(KEY_LAST_TRIGGER_AT, nowMillis)
            .putString(KEY_LAST_TRIGGER_TEXT, text)
            .apply()
        _lastTriggerAt.value = nowMillis
        _lastTriggerText.value = text
    }

    // ---------- 今日同步标记 ----------
    //
    // 语义：记录"今天这一班已经由蓝牙触发**成功**打完"。
    //
    // ⚠️ 它**不参与去重决策**。去重靠的是：
    //   ① Room 里的 SUCCESS 记录（decide() 直接查库）
    //   ② 服务层 running 互斥
    //   ③ BleScanReceiver 的 60 秒冷却
    //
    // 为什么不去重也要存：首页/设置页要显示"上次蓝牙触发的结论"，
    // 而失败时不该写（失败要允许用户走回考勤机旁重试）。
    // 仅由 PunchForegroundService 在 result.success 时写入。

    fun markSynced(kind: PunchKind) {
        val key = when (kind) {
            PunchKind.CLOCK_IN -> KEY_SYNCED_IN_DAY
            PunchKind.CLOCK_OUT -> KEY_SYNCED_OUT_DAY
            PunchKind.MANUAL -> return
        }
        prefs().edit().putInt(key, PunchSchedule.dateKey(System.currentTimeMillis())).apply()
        Log.i(TAG, "已标记今日 " + kind.name + " 由蓝牙触发处理过")
    }

    fun clearTodaySync() {
        prefs().edit().remove(KEY_SYNCED_IN_DAY).remove(KEY_SYNCED_OUT_DAY).apply()
    }

    /** 早到窗口的可选档位（分钟），给设置页的分段控件用 */
    val EARLY_WINDOW_OPTIONS: List<Int> = listOf(30, 60, 120, 240)

    fun earlyWindowLabel(minutes: Int): String = when {
        minutes < 60 -> minutes.toString() + " 分钟"
        minutes % 60 == 0 -> (minutes / 60).toString() + " 小时"
        else -> (minutes / 60).toString() + " 小时 " + (minutes % 60) + " 分"
    }
}
