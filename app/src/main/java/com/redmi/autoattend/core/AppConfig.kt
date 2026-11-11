package com.redmi.autoattend.core

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.redmi.autoattend.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 执行通道偏好。
 *
 * AUTO：先无障碍，连续失败后自动降级到 root
 */
enum class ChannelPreference {
    AUTO,
    ACCESSIBILITY_ONLY,
    ROOT_ONLY,
}

/**
 * 每日生效配置。
 *
 * @param clockInMinute 上班时间，从 00:00 起算的分钟数（0..1439）
 * @param clockOutMinute 下班时间，从 00:00 起算的分钟数（0..1439）
 * @param activeDays 生效星期，Calendar.DAY_OF_WEEK 风格的集合（周日=1 ... 周六=7）
 */
data class ScheduleSettings(
    val clockInMinute: Int = 9 * 60,
    val clockOutMinute: Int = 18 * 60,
    val activeDays: Set<Int> = setOf(2, 3, 4, 5, 6),
) {
    companion object {
        /** 默认周一至周五。Calendar 中周日=1、周一=2 ... 周六=7 */
        val WEEKDAYS: Set<Int> = setOf(2, 3, 4, 5, 6)
    }
}

/**
 * 应用配置仓库。
 *
 * 普通配置放 SharedPreferences；锁屏密码放 EncryptedSharedPreferences。
 *
 * 安全约定：锁屏密码绝不进入源码、绝不进日志、绝不进 Room。这里对外只暴露
 * hasLockPassword，不暴露明文。真正读取明文的方法需要显式调用 readLockPassword，
 * 且调用方必须是执行引擎。
 */
object AppConfig {

    private const val PREFS_NAME = "autoattend_prefs"
    private const val SECURE_PREFS_NAME = "autoattend_secure"
    private const val SECURE_KEY_PASSWORD = "lock_password"

    /** EncryptedSharedPreferences 默认使用的 master key 别名 */
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    private const val TAG_SECURE = "SecurePrefs"

    /** 加密存储实例缓存，避免每次读写都重建 key */
    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null

    private const val KEY_IN_MINUTE = "clock_in_minute"
    private const val KEY_OUT_MINUTE = "clock_out_minute"
    private const val KEY_ACTIVE_DAYS = "active_days"
    private const val KEY_CHANNEL = "channel_preference"
    private const val KEY_ENABLED = "punch_enabled"
    private const val KEY_DAYS_OF_LOG = "log_retention_days"
    private const val KEY_PWD_LEN = "lock_password_length"
    private const val KEY_THEME_MODE = "theme_mode"

    /** 是否显示常驻保活通知 */
    private const val KEY_SHOW_PERSISTENT = "show_persistent_notification"

    /** 是否启用打卡时的屏幕接管悬浮窗 */
    private const val KEY_SHOW_HUD = "show_control_hud"

    /**
     * 被跳过的日期（yyyyMMdd）。
     *
     * 原来只支持"跳过今天"，但自动打卡常常跨天：
     * 晚上下班卡打完后，下一次是**明天**早上 —— 此时按钮应该写"跳过明天"。
     * 所以这里存的是**具体日期**，而不是"今天"这个相对概念。
     */
    private const val KEY_SKIP_DATE = "skip_date"

    @Volatile
    private var appContext: Context? = null

    private val _settings = MutableStateFlow(ScheduleSettings())
    val settings: StateFlow<ScheduleSettings> = _settings.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _channel = MutableStateFlow(ChannelPreference.AUTO)
    val channel: StateFlow<ChannelPreference> = _channel.asStateFlow()

    private val _hasPassword = MutableStateFlow(false)
    val hasPassword: StateFlow<Boolean> = _hasPassword.asStateFlow()

    /** 密码位数（不暴露明文），用于 UI 提示与解锁流程判断 */
    private val _passwordLength = MutableStateFlow(6)
    val passwordLength: StateFlow<Int> = _passwordLength.asStateFlow()

    /**
     * 加密存储是否已降级为普通存储。
     *
     * true 表示 Keystore 不可用、自愈也失败，密码只能明文存。
     * 这不是正常状态，UI 必须显式告知用户，不能假装一切正常。
     */
    private val _secureStorageDegraded = MutableStateFlow(false)
    val secureStorageDegraded: StateFlow<Boolean> = _secureStorageDegraded.asStateFlow()

    /** 外观模式：浅色 / 深色 / 跟随系统 */
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * 是否显示常驻保活通知。
     *
     * 关掉后 KeepAliveService 仍然运行（保活不依赖通知），
     * 但通知栏看不到"正在守护"这条 —— 适合不想被占一行通知的用户。
     * 代价是失去"一眼看到还在不在跑"的能力，所以默认开启。
     */
    private val _showPersistent = MutableStateFlow(true)
    val showPersistent: StateFlow<Boolean> = _showPersistent.asStateFlow()

    /**
     * 是否在打卡时显示系统悬浮窗提示。
     *
     * 默认开启 —— 这是用户知情权的一部分：App 正在操作你的屏幕，
     * 你应该看得见、并且能随时叫停。
     *
     * 关掉后打卡流程照常执行，只是屏幕上不出现提示条。
     * 适合"我完全信任它，不想被打扰"的场景。
     */
    private val _showControlHud = MutableStateFlow(true)
    val showControlHud: StateFlow<Boolean> = _showControlHud.asStateFlow()

    /** 当前被跳过的日期（yyyyMMdd），0 表示没有跳过任何一天 */
    private val _skipDate = MutableStateFlow(0)

    /**
     * 今天是否被跳过。
     *
     * 兼容旧语义：只有当被跳过的日期**正好是今天**时才为 true。
     * 界面上的"跳过今天/跳过明天"按钮会用 [skippedDateKey] 做更细的判断。
     */
    private val _skipTodayFlow = MutableStateFlow(false)
    val skipToday: StateFlow<Boolean> = _skipTodayFlow.asStateFlow()

    /** 被跳过的日期键；0 = 没有跳过。供 UI 判断"跳过按钮该显示哪一天" */
    val skippedDateKey: Int get() = _skipDate.value

    /** 把日期压成 yyyyMMdd 整数，用于比较"是不是今天" */
    private fun todayKey(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 +
            c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    /**
     * 跳过某个日期（yyyyMMdd）的所有自动打卡。
     *
     * 只写 prefs，不动闹钟 —— 闹钟触发时先读这个标志决定是否执行。
     * 这样实现最简单，也不会因为"取消闹钟失败"导致漏打。
     */
    fun skipDate(dateKey: Int) {
        // 注意：调用方必须确保这是**用户明确确认**后的调用。
        // 历史教训：曾把确认弹窗的按钮做成 LiquidGlassButton，
        // 它在 Dialog 首次组合时幽灵触发过一次 onClick，
        // 导致"没点任何东西，跳过配置却被写入"。已在 ConfirmDialog 里修正。
        prefs().edit().putInt(KEY_SKIP_DATE, dateKey).apply()
        _skipDate.value = dateKey
        _skipTodayFlow.value = dateKey == todayKey()
    }

    /** 取消跳过 */
    fun cancelSkip() {
        prefs().edit().remove(KEY_SKIP_DATE).apply()
        _skipDate.value = 0
        _skipTodayFlow.value = false
    }

    /** 判断某个时间点是否落在被跳过的日期里 */
    fun isSkipped(dateKey: Int): Boolean = dateKey != 0 && _skipDate.value == dateKey

    /** 刷新跳过标志（跨天后旧日期自动失效） */
    private fun refreshSkipFlag() {
        val saved = prefs().getInt(KEY_SKIP_DATE, 0)
        _skipDate.value = saved
        // 只对"今天"这个相对概念维护 skipToday
        _skipTodayFlow.value = saved == todayKey()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    private fun prefs(): SharedPreferences =
        requireNotNull(appContext).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 加密存储实例。
     *
     * ## 自愈逻辑（真机踩过）
     *
     * 小米/HyperOS 上有一种确实会发生的故障：
     * ```
     * the master key android-keystore://_androidx_security_master_key_
     * exists but is unusable
     * ```
     * Keystore 里存在那个 master key 条目，但已经不可用（系统升级、
     * 数据迁移、备份恢复都可能造成）。此时 [EncryptedSharedPreferences.create]
     * 直接抛异常，密码永远存不进去 —— 用户看到的就是"配不了密码"。
     *
     * 处理方式：捕获后**清掉损坏的 key 与加密文件，重建一套**。
     * 这是安全的：损坏的 key 本来就解不开任何数据，重建只会丢失
     * 那一个本来就不可读的密码条目。
     *
     * 重建仍失败时降级到 [plainPrefs]，并在 UI 上明确告知用户
     * "已降级存储"（[secureStorageDegraded]），不隐瞒。
     */
    private fun securePrefs(): SharedPreferences {
        val ctx = requireNotNull(appContext)
        cachedSecurePrefs?.let { return it }

        // 第一次尝试
        runCatching { createSecurePrefs(ctx) }.getOrNull()?.let {
            cachedSecurePrefs = it
            return it
        }

        // 失败 → 清掉损坏状态后重试一次
        Log.w(TAG_SECURE, "加密存储不可用，尝试自愈")
        wipeBrokenSecureStorage(ctx)
        runCatching { createSecurePrefs(ctx) }.getOrNull()?.let {
            cachedSecurePrefs = it
            _secureStorageDegraded.value = false
            Log.i(TAG_SECURE, "加密存储已重建")
            return it
        }

        // 仍然失败 → 降级
        Log.e(TAG_SECURE, "加密存储重建失败，降级为普通存储")
        _secureStorageDegraded.value = true
        return plainPrefs()
    }

    private fun createSecurePrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 清掉损坏的 master key 与加密首选项文件。
     *
     * 只删这两个具体文件/别名，不动任何其它数据。
     */
    private fun wipeBrokenSecureStorage(ctx: Context) {
        runCatching {
            ctx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        }
        runCatching {
            ctx.deleteSharedPreferences(SECURE_PREFS_NAME)
        }
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
        }
        cachedSecurePrefs = null
    }

    /** 降级时的普通存储（与主配置分开，避免混在一起） */
    private fun plainPrefs(): SharedPreferences =
        requireNotNull(appContext).getSharedPreferences(SECURE_PREFS_NAME + "_plain", Context.MODE_PRIVATE)

    fun reload() {
        val p = prefs()
        _settings.value = ScheduleSettings(
            clockInMinute = p.getInt(KEY_IN_MINUTE, 9 * 60),
            clockOutMinute = p.getInt(KEY_OUT_MINUTE, 18 * 60),
            activeDays = decodeDays(p.getString(KEY_ACTIVE_DAYS, null))
                ?: ScheduleSettings.WEEKDAYS,
        )
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        _channel.value = runCatching {
            ChannelPreference.valueOf(p.getString(KEY_CHANNEL, null) ?: ChannelPreference.AUTO.name)
        }.getOrDefault(ChannelPreference.AUTO)

        _showPersistent.value = p.getBoolean(KEY_SHOW_PERSISTENT, true)
        _showControlHud.value = p.getBoolean(KEY_SHOW_HUD, true)

        // 跳过标志按日期自动失效，所以每次 reload 都重算
        refreshSkipFlag()

        val hasPwd = runCatching { securePrefs().contains(SECURE_KEY_PASSWORD) }.getOrDefault(false)
        _hasPassword.value = hasPwd
        _passwordLength.value = p.getInt(KEY_PWD_LEN, 6).coerceIn(4, 16)

        _themeMode.value = runCatching {
            ThemeMode.valueOf(p.getString(KEY_THEME_MODE, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    // ---------- 排班 ----------

    fun updateSchedule(schedule: ScheduleSettings) {
        prefs().edit()
            .putInt(KEY_IN_MINUTE, schedule.clockInMinute.coerceIn(0, 24 * 60 - 1))
            .putInt(KEY_OUT_MINUTE, schedule.clockOutMinute.coerceIn(0, 24 * 60 - 1))
            .putString(KEY_ACTIVE_DAYS, encodeDays(schedule.activeDays))
            .apply()
        _settings.value = schedule
    }

    /** 开关常驻通知，并立即生效（同步启停服务） */
    /** 开关打卡时的悬浮窗提示 */
    fun setShowControlHud(value: Boolean) {
        prefs().edit().putBoolean(KEY_SHOW_HUD, value).apply()
        _showControlHud.value = value
    }

    fun setShowPersistent(value: Boolean) {
        prefs().edit().putBoolean(KEY_SHOW_PERSISTENT, value).apply()
        _showPersistent.value = value
    }

    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setChannel(value: ChannelPreference) {
        prefs().edit().putString(KEY_CHANNEL, value.name).apply()
        _channel.value = value
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs().edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setLogRetentionDays(days: Int) {
        prefs().edit().putInt(KEY_DAYS_OF_LOG, days).apply()
    }

    fun logRetentionDays(): Int = prefs().getInt(KEY_DAYS_OF_LOG, 30)

    // ---------- 锁屏密码 ----------

    /**
     * 写入锁屏密码。
     *
     * 只允许纯数字，因为解锁流程走 shell 的数字输入，非数字字符需要额外转义；
     * 而且小米的数字锁屏本身只接受 0-9。
     */
    fun saveLockPassword(password: String): Result<Unit> = runCatching {
        require(password.isNotEmpty()) { "密码不能为空" }
        require(password.all { it.isDigit() }) { "锁屏密码只支持纯数字" }
        require(password.length in 4..16) { "密码长度需在 4~16 位之间" }

        val target = securePrefs()

        // 必须用 commit 而不是 apply：
        // apply 是异步落盘，失败不会反馈。密码这种"存了就必须真存上"的数据，
        // 需要同步确认写入成功，否则界面显示已保存、实际没写进去。
        val ok = target.edit().putString(SECURE_KEY_PASSWORD, password).commit()
        require(ok) { "密码写入失败（存储不可写）" }

        // 回读校验：确认真的能读出来
        val readBack = target.getString(SECURE_KEY_PASSWORD, null)
        require(readBack == password) { "密码写入后校验失败" }

        prefs().edit().putInt(KEY_PWD_LEN, password.length).apply()

        _hasPassword.value = true
        _passwordLength.value = password.length
    }

    fun clearLockPassword() {
        runCatching { securePrefs().edit().remove(SECURE_KEY_PASSWORD).commit() }
        _hasPassword.value = false
    }

    /**
     * 读取锁屏密码明文。
     *
     * 仅供执行引擎调用。调用方不得把返回值写日志、写入数据库或发送到任何地方。
     */
    fun readLockPassword(): String? =
        runCatching { securePrefs().getString(SECURE_KEY_PASSWORD, null) }.getOrNull()

    // ---------- 星期编解码 ----------

    private fun encodeDays(days: Set<Int>): String =
        days.filter { it in 1..7 }.sorted().joinToString(",")

    private fun decodeDays(raw: String?): Set<Int>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
        return parsed.toSet()
    }
}