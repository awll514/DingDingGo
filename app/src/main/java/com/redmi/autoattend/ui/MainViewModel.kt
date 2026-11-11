package com.redmi.autoattend.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.ChannelPreference
import com.redmi.autoattend.core.HealthCheck
import com.redmi.autoattend.core.NextPunchSlot
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.AppDatabase
import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.data.db.PunchLogEntity
import com.redmi.autoattend.data.db.PunchOutcome
import com.redmi.autoattend.exec.PunchNotifier
import com.redmi.autoattend.exec.RootShell
import com.redmi.autoattend.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 首页当前状态的四态机。
 *
 * 对应需求 F2.3：未到时间 / 等待执行 / 执行中 / 成功 / 失败
 */
sealed interface HomeStatus {
    /** 还没到时间 */
    data class Idle(val nextAt: Long) : HomeStatus

    /** 时间临近，已进入预热窗口 */
    data class Waiting(val nextAt: Long) : HomeStatus

    /** 正在执行 */
    data object Running : HomeStatus

    /** 最近一次成功 */
    data class Success(val at: Long, val evidence: String?) : HomeStatus

    /** 最近一次失败 */
    data class Failed(val at: Long, val stage: String, val message: String) : HomeStatus
}

/**
 * 首页一次性展示所需的全部状态。
 */
data class HomeUiState(
    val now: Long = System.currentTimeMillis(),
    val nextSlot: NextPunchSlot? = null,
    val status: HomeStatus = HomeStatus.Idle(0L),

    /** 今天是否被用户跳过 */
    val skipToday: Boolean = false,

    /**
     * 下一次打卡的"人类可读日期"。
     *
     * 取值："今天" / "明天" / "周五" / "11月14日 周五"
     * 或者休假文案（当接下来没有生效日时）。
     */
    val nextDayLabel: String = "",

    /** 接下来是否处于休假状态（没有任何生效日） */
    val onVacation: Boolean = false,

    /**
     * 被跳过的日期的友好文案（"今天"/"明天"）。
     *
     * 单独给一个字段，因为 nextDayLabel 反映的是**顺延后**的下一次打卡，
     * 已经跳过了被跳过的那天 —— 直接拿它写"恢复X打卡"会指错日期。
     */
    val skippedDayLabel: String = "",
    val todayClockIn: PunchLogEntity? = null,
    val todayClockOut: PunchLogEntity? = null,
    val health: HealthCheck.Report? = null,
    val enabled: Boolean = true,
    val channel: ChannelPreference = ChannelPreference.AUTO,
    val countdownText: String = "--:--:--",
    val isActiveDay: Boolean = true,
    val hasPassword: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    /** 最近日志（给设置页用） */
    val logs: StateFlow<List<PunchLogEntity>> = db.punchLogDao()
        .observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        AppConfig.init(app)
        AppConfig.reload()
        _ui.value = _ui.value.copy(themeMode = AppConfig.themeMode.value)
        PunchNotifier.ensureChannels(app)
        // 启动时确保闹钟已注册（进程可能被杀过）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AlarmScheduler.scheduleNext(getApplication()) }
        }
        startTicker()
        refreshHealth()
    }

    /**
     * 秒级刷新循环——需求 F2.1 要求倒计时精确到秒。
     *
     * 用 200ms 的间隔而不是 1000ms，是为了让"秒"跳变更贴近真实秒边界，
     * 避免固定 1s 采样造成的视觉抖动。
     */
    /**
     * 上一次查库的时间与结果缓存。
     *
     * 为什么需要缓存：ticker 每 200ms 跑一次，如果每次 tick 都查两次数据库，
     * 就是 10 次/秒的 IO —— 纯属浪费，而且会让倒计时有轻微卡顿。
     *
     * 打卡记录一天最多变化几次，所以这里每 3 秒才真正查一次库；
     * 中间的 tick 只用缓存值算倒计时。
     */
    @Volatile
    private var lastDbQueryAt: Long = 0L

    @Volatile
    private var cachedClockIn: PunchLogEntity? = null

    @Volatile
    private var cachedClockOut: PunchLogEntity? = null

    @Volatile
    private var cachedDayKey: Int = -1

    private companion object {
        /** 查库节流间隔 */
        const val DB_REFRESH_INTERVAL_MS = 3_000L

        /** tick 间隔：200ms 让秒位跳变更贴近真实秒边界 */
        const val TICK_INTERVAL_MS = 200L
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                refreshTick()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** 外部触发的强制刷新（打卡完成后、切换 tab 时调用） */
    fun invalidateTodayCache() {
        lastDbQueryAt = 0L
    }

    /**
     * 判断打卡服务是否真的还在跑。
     *
     * 【为什么需要它】
     * `_busy` 原本只由 manualPunch 的 finally 复位。但用户在悬浮窗里
     * 点「取消」时，服务是直接 stopSelf 的 —— manualPunch 的协程
     * 可能还停在 delay 里，或永远等不到结果，
     * 于是按钮会永久卡在「正在执行」、再也点不动。
     *
     * 用 ActivityManager 的真实状态做兜底：服务不在了就复位 busy。
     * 这是「以系统事实为准」而不是「相信乐观状态」。
     */
    private fun isPunchServiceRunning(): Boolean {
        return runCatching {
            val am = getApplication<android.app.Application>()
                .getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            val name = com.redmi.autoattend.exec.PunchForegroundService::class.java.name
            services.any { it.service.className == name }
        }.getOrDefault(false)
    }

    private suspend fun refreshTick() {
        val now = System.currentTimeMillis()
        val settings = AppConfig.settings.value
        // 【跳过】把被跳过的日期传进去，nextSlot 会整日略过，
        // 于是"下一次打卡"自动顺延到下一个生效日，界面日期同步更新。
        val skippedDate = AppConfig.skippedDateKey
        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = if (skippedDate != 0) setOf(skippedDate) else emptySet(),
        )

        val startOfToday = PunchSchedule.startOfDay(now)
        val todayKey = PunchSchedule.dateKey(now)

        // 只有在「距上次查库超过阈值」或「跨天了」时才真正查库
        val needQuery = now - lastDbQueryAt > DB_REFRESH_INTERVAL_MS || todayKey != cachedDayKey
        if (needQuery) {
            cachedClockIn = runCatching {
                db.punchLogDao().lastSuccessSince(PunchKind.CLOCK_IN, startOfToday)
            }.getOrNull()
            cachedClockOut = runCatching {
                db.punchLogDao().lastSuccessSince(PunchKind.CLOCK_OUT, startOfToday)
            }.getOrNull()
            cachedDayKey = todayKey
            lastDbQueryAt = now
        }

        // 服务已经退出但 busy 还是 true → 说明被取消了，复位
        if (_busy.value && !isPunchServiceRunning()) {
            _busy.value = false
        }

        val inLog = cachedClockIn
        val outLog = cachedClockOut

        // 判定当前状态
        val status = deriveStatus(slot, now, inLog, outLog)

        val countdown = slot?.let { formatCountdown(it.targetAtMillis - now) } ?: "--:--:--"

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val isActive = cal.get(Calendar.DAY_OF_WEEK) in settings.activeDays

        _ui.value = _ui.value.copy(
            now = now,
            nextSlot = slot,
            status = status,
            // 跳过标记的语义：**当前是否存在一个仍然有效的跳过**。
            //
            // 注意不能写成 "被跳过日期 == 下次打卡日期"：
            // nextSlot 已经把被跳过的日期排除了，两者永远不可能相等 ——
            // 那样按钮会永远显示"跳过X"，永远无法取消。
            //
            // 正确判断：被跳过的日期 >= 今天（还没过期）就算生效中。
            skipToday = skippedDate != 0 && skippedDate >= todayKey,
            // 日期标签：今天 / 明天 / 周几 / M月d日
            // 没有任何生效日时给休假文案，而不是留空
            nextDayLabel = slot?.let {
                com.redmi.autoattend.core.PunchSchedule.dayLabel(
                    nowMillis = now,
                    targetAtMillis = it.targetAtMillis,
                    activeDays = settings.activeDays,
                )
            } ?: com.redmi.autoattend.core.PunchSchedule.vacationLabel(settings.activeDays),
            onVacation = slot == null,
            skippedDayLabel = PunchSchedule.dayLabelForKey(now, skippedDate),
            todayClockIn = inLog,
            todayClockOut = outLog,
            enabled = AppConfig.enabled.value,
            channel = AppConfig.channel.value,
            countdownText = countdown,
            isActiveDay = isActive,
            hasPassword = AppConfig.hasPassword.value,
            themeMode = AppConfig.themeMode.value,
        )
    }

    private fun deriveStatus(
        slot: NextPunchSlot?,
        now: Long,
        clockIn: PunchLogEntity?,
        clockOut: PunchLogEntity?,
    ): HomeStatus {
        if (_busy.value) return HomeStatus.Running

        // 今天两个都打完了
        if (clockIn != null && clockOut != null) {
            val latest = maxOf(clockIn.startedAt, clockOut.startedAt)
            return HomeStatus.Success(latest, null)
        }

        if (slot == null) {
            // 没有下一个点了：看最近一次结果的成败
            val latest = listOfNotNull(clockIn, clockOut).maxByOrNull { it.startedAt }
            return when {
                latest == null -> HomeStatus.Idle(0L)
                latest.outcome == PunchOutcome.SUCCESS ->
                    HomeStatus.Success(latest.startedAt, latest.evidence)
                else -> HomeStatus.Failed(
                    latest.startedAt,
                    latest.failedStage ?: "未知",
                    latest.message ?: "打卡失败",
                )
            }
        }

        // 距离目标时间 75 秒内算"等待执行"
        val delta = slot.targetAtMillis - now
        return if (delta <= PunchSchedule.PRE_WARM_SECONDS * 1000L && delta >= -PunchSchedule.RETRY_WINDOW_SECONDS * 1000L) {
            HomeStatus.Waiting(slot.targetAtMillis)
        } else {
            HomeStatus.Idle(slot.targetAtMillis)
        }
    }

    /** 倒计时格式：HH:mm:ss */
    private fun formatCountdown(deltaMs: Long): String {
        val d = deltaMs.coerceAtLeast(0)
        val totalSec = d / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ---------- 对外操作 ----------

    fun refreshHealth() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val slot = PunchSchedule.nextSlot(
                    System.currentTimeMillis(),
                    AppConfig.settings.value,
                )
                HealthCheck.check(getApplication(), slot)
            }
            _ui.value = _ui.value.copy(health = report)
        }
    }

    fun setEnabled(value: Boolean) {
        AppConfig.setEnabled(value)
        viewModelScope.launch(Dispatchers.IO) {
            if (value) {
                AlarmScheduler.scheduleNext(getApplication())
            } else {
                AlarmScheduler.cancelAll(getApplication(), AlarmScheduler.lastScheduled)
            }
            refreshHealth()
        }
        _ui.value = _ui.value.copy(enabled = value)
    }

    fun setThemeMode(mode: ThemeMode) {
        AppConfig.setThemeMode(mode)
        _ui.value = _ui.value.copy(themeMode = mode)
    }

    fun setChannel(value: ChannelPreference) {
        AppConfig.setChannel(value)
        _ui.value = _ui.value.copy(channel = value)
    }

    fun updateSchedule(clockInMinute: Int, clockOutMinute: Int, days: Set<Int>) {
        AppConfig.updateSchedule(
            com.redmi.autoattend.core.ScheduleSettings(
                clockInMinute = clockInMinute,
                clockOutMinute = clockOutMinute,
                activeDays = days,
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AlarmScheduler.scheduleNext(getApplication()) }
            refreshHealth()
        }
    }

    /**
     * 设置是否跳过今天。
     *
     * 跳过只影响**自动触发**的打卡，手动打卡永远生效
     * （用户手动点按钮时，意图很明确）。
     */
    /**
     * 跳过 / 取消跳过"下一次打卡所在的那一天"。
     *
     * 语义跟着**下一次打卡**走，而不是固定"今天"：
     * - 如果下一次是今天 → 跳过今天；
     * - 如果下一次是明天（今天的下班卡已打完）→ 跳过明天。
     *
     * 只影响自动触发，手动打卡永远生效。
     */
    fun setSkipToday(skip: Boolean) {
        if (skip) {
            val target = _ui.value.nextSlot?.targetAtMillis
            if (target != null) {
                AppConfig.skipDate(PunchSchedule.dateKey(target))
            }
        } else {
            AppConfig.cancelSkip()
        }
        invalidateTodayCache()
    }

    /** 手动触发一次打卡（兜底按钮） */
    fun manualPunch() {
        android.util.Log.i("MainViewModel", "manualPunch called, busy=" + _busy.value)
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val intent = android.content.Intent(
                    getApplication(),
                    com.redmi.autoattend.exec.PunchForegroundService::class.java,
                ).apply {
                    action = com.redmi.autoattend.exec.PunchForegroundService.ACTION_EXECUTE
                    // 手动打卡要落到"当前真正该打的那一次"上，
                    // 否则首页的今日上班/下班状态永远不会更新
                    // （首页只查 CLOCK_IN / CLOCK_OUT，MANUAL 不在统计里）。
                    putExtra(
                        com.redmi.autoattend.exec.PunchAlarmReceiver.EXTRA_KIND,
                        inferManualKind().name,
                    )
                    putExtra(
                        com.redmi.autoattend.exec.PunchAlarmReceiver.EXTRA_TARGET_AT,
                        System.currentTimeMillis(),
                    )
                }
                androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
            } catch (t: Throwable) {
                // 启动失败时用通知告知
                PunchNotifier.notifyTest(getApplication(), false)
            } finally {
                // 打卡完成后立刻让首页重新查库，不用等 3 秒节流窗口
                delay(1_500)
                invalidateTodayCache()
                _busy.value = false
            }
        }
    }

    /**
     * 推断手动打卡应该记成上班还是下班。
     *
     * 规则：上班还没打 → 记上班；上班已打、下班没打 → 记下班；
     * 都打过 → 按当前时间（中午前记上班，下午记下班）。
     */
    private fun inferManualKind(): PunchKind {
        val s = _ui.value
        return when {
            s.todayClockIn == null -> PunchKind.CLOCK_IN
            s.todayClockOut == null -> PunchKind.CLOCK_OUT
            else -> {
                val cal = java.util.Calendar.getInstance()
                if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 12) {
                    PunchKind.CLOCK_IN
                } else {
                    PunchKind.CLOCK_OUT
                }
            }
        }
    }

    fun savePassword(pwd: String): Result<Unit> {
        val result = AppConfig.saveLockPassword(pwd)
        refreshHealth()
        return result
    }

    fun clearPassword() {
        AppConfig.clearLockPassword()
        refreshHealth()
    }

    fun testNotification(success: Boolean) {
        PunchNotifier.notifyTest(getApplication(), success)
    }

    fun recheckRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            RootShell.invalidateCache()
            RootShell.isAvailable(force = true)
            refreshHealth()
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.punchLogDao().clearAll() }
        }
    }

    fun pruneOldLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() -
                AppConfig.logRetentionDays().toLong() * 24 * 60 * 60 * 1000L
            runCatching { db.punchLogDao().deleteOlderThan(cutoff) }
        }
    }
}