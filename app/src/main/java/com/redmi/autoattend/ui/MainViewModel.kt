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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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

        // 启动时确保闹钟已注册（进程可能被杀过）。
        //
        // 【启动性能关键修正】这里原本和 refreshHealth() 一起在 init 里立刻触发，
        // 而 refreshHealth() 内部的 HealthCheck.check() 会调
        // RootShell.isAvailable() —— 那是一次 **fork su 进程**的同步探测，
        // 真机实测耗时 300~400ms，而且要连着跑好几次（每次 refreshHealth 一次），
        // 直接造成冷启动时 "Skipped 185 frames"（约 3 秒主线程被占满）。
        //
        // 现在把两件重活都推迟到首帧绘制之后：
        //   · 闹钟重排不着急 —— 系统里的闹钟本来就还在，这里只是修正 +
        //     兜底；晚 1 秒不影响任何功能；
        //   · 健康度检查更不着急 —— 它是诊断信息，晚一点出完全可接受。
        //
        // 用 viewModelScope（Main 调度器）+ delay 让出首帧，
        // 再切到 IO 执行真正的重活。
        viewModelScope.launch {
            kotlinx.coroutines.delay(STARTUP_DEFER_MS)
            withContext(Dispatchers.IO) {
                runCatching { AlarmScheduler.scheduleNext(getApplication()) }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(STARTUP_DEFER_MS * 2)
            refreshHealth()
        }

        // 【不再在这里无条件起 ticker】
        //
        // ticker 的唯一启停入口是 setVisible()，由 UI 层的
        // ON_START / ON_STOP 驱动。init 里直接起 ticker 的话，
        // Activity 一停到后台就再也没有东西能让它停下来 ——
        // 详见 setVisible() 的注释。
        //
        // Activity 紧接着就会 onStart，所以"首帧之后才有状态"这件事
        // 和原来没有区别（原来也是第一个 tick 才写入 _ui）。
    }

    /**
     * 上一次查库的时间与结果缓存。
     *
     * 为什么需要缓存：ticker 每 200ms 跑一次，如果每次 tick 都查两次数据库，
     * 就是 10 次/秒的 IO —— 纯属浪费，而且会让倒计时有轻微卡顿。
     *
     * 打卡记录一天最多变化几次，所以这里每 3 秒才真正查一次库；
     * 中间的 tick 只用缓存值算倒计时。
     */
    /**
     * 首帧之后再干重活。
     *
     * 冷启动时用户最先看到的是界面，而不是"闹钟是否已重排"。
     * 把 root 探测、数据库查询这类几百毫秒的同步操作推到首帧之后，
     * 直接决定了启动是"丝滑"还是"卡 2 秒"。
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
        /**
         * 首帧之后多久才做启动期的重活。
         *
         * 冷启动时用户最先看到的是界面，而不是"闹钟是否已重排"。
         * 把 root 探测（fork su，实测 300~400ms）、数据库查询
         * 这类同步操作推到首帧之后，直接决定了启动是"丝滑"还是"卡 2 秒"。
         */
        const val STARTUP_DEFER_MS = 400L

        /** 查库节流间隔 */
        const val DB_REFRESH_INTERVAL_MS = 3_000L

        /** tick 间隔：200ms 让秒位跳变更贴近真实秒边界 */
        const val TICK_INTERVAL_MS = 200L
    }

    /**
     * 秒级刷新循环的 Job。
     *
     * 【为什么必须能被停掉 —— "后台零消耗"红线】
     *
     * 原来是 viewModelScope.launch { while (true) { refreshTick(); delay(200) } }。
     * viewModelScope 的生命周期是**整个 ViewModel**，不是 Activity 的可见性：
     * 用户按 Home 键、切到钉钉之后，Activity 已经 onStop，这个循环照样在跑。
     *
     * 而 refreshTick() 每个 tick 要：
     *   1. PunchSchedule.nextSlot() 重算一遍下一次打卡（含 completedSlotsForToday）；
     *   2. 每 3 秒真正查一次 Room（lastSuccessSince × 2）；
     *   3. 状态为 busy 时还会走 isPunchServiceRunning() →
     *      ActivityManager.getRunningServices()，一次 **binder IPC**；
     *   4. 每 tick 都 copy 出一个新的 HomeUiState 写进 StateFlow
     *      （now 字段每 tick 都变，所以必然会 emit，去重优化也救不了）。
     *
     * 5Hz × 上面这一整套，在最该零消耗的后台场景持续跑，是完全不符合
     * 「没运行时在后台不要消耗资源」的。倒计时只对看得见的人有意义。
     *
     * 采样率保持不变：仍然 200ms 一跳（而不是 1000ms），
     * 这样"秒"的跳变更贴近真实秒边界，避免固定 1s 采样造成的视觉抖动
     * —— 需求 F2.1 要求倒计时精确到秒。改的只是"什么时候跑"，不是"跑多快"。
     */
    private var tickerJob: Job? = null

    /**
     * UI 可见性开关（由 Activity 的 ON_START / ON_STOP 调用）。
     *
     * 可见 → 起 ticker（如果已经在跑则幂等）；不可见 → 取消 ticker。
     * 同时把查库节流时间戳清零，保证重新可见时**立刻**查一次真实的打卡记录，
     * 而不是先拿一份切后台之前的旧缓存糊上去。
     *
     * 这样"秒级精度"的行为完全不变：可见期间依旧 200ms 一跳；
     * 只是不可见期间不再有任何轮询。
     */
    fun setVisible(visible: Boolean) {
        if (visible) {
            if (tickerJob?.isActive == true) return
            // 回到前台的第一帧就要看到真实状态，不能被 3 秒节流窗口挡住
            invalidateTodayCache()
            tickerJob = viewModelScope.launch {
                while (isActive) {
                    refreshTick()
                    delay(TICK_INTERVAL_MS)
                }
            }
        } else {
            // 取消后 Job 引用留着也没意义，置空便于下次重建
            tickerJob?.cancel()
            tickerJob = null
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

        // 【已完成】把今天已经打完的班带上，界面上的"下一次"
        // 才会跳过已完成的班次 —— 和 AlarmScheduler 保持一致，
        // 否则界面显示下次是下班、而闹钟实际已排到明天。
        val completed = runCatching {
            val startOfToday = PunchSchedule.startOfDay(now)
            val done = listOfNotNull(
                db.punchLogDao().lastSuccessSince(PunchKind.CLOCK_IN, startOfToday),
                db.punchLogDao().lastSuccessSince(PunchKind.CLOCK_OUT, startOfToday),
            ).map { it.kind to it.startedAt }
            PunchSchedule.completedSlotsForToday(now, done)
        }.getOrDefault(emptySet())

        val slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            skippedDateKeys = if (skippedDate != 0) setOf(skippedDate) else emptySet(),
            completedSlots = completed,
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
                    val kind = inferManualKind()
                    putExtra(
                        com.redmi.autoattend.exec.PunchAlarmReceiver.EXTRA_KIND,
                        kind.name,
                    )

                    // 【关键】targetAt 必须是**该班次的计划时刻**，不能是「此刻」。
                    //
                    // 真机踩到的坑：晚上的手动打卡传了 now 当 targetAt，
                    // 而上班卡闸门窗口是 [target-75s, target+55s] —— target=now
                    // 时永远放行，于是 21:38 打出了一张「上班卡」。
                    //
                    // 传入今天的计划时刻后：
                    //   · 晚上点「手动打卡」→ kind=CLOCK_OUT、target=18:30
                    //   · 闸门按 18:30 判定 → 正确；超出补卡窗口会如实拒绝。
                    //
                    // 这也让首页的"今日上班/下班"能落到正确的一格。
                    val settings = AppConfig.settings.value
                    val startOfDay = com.redmi.autoattend.core.PunchSchedule
                        .startOfDay(System.currentTimeMillis())
                    val minute = when (kind) {
                        com.redmi.autoattend.data.db.PunchKind.CLOCK_OUT -> settings.clockOutMinute
                        else -> settings.clockInMinute
                    }
                    putExtra(
                        com.redmi.autoattend.exec.PunchAlarmReceiver.EXTRA_TARGET_AT,
                        startOfDay + minute * 60_000L,
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

        // 【必须按 DB 判断，不能只看首页状态】
        //
        // 旧实现在今日两班都为 null 时直接返回 CLOCK_IN —— 这在真机上造成
        // 「晚上 21:38 手动打卡被判成上班卡」的错误：
        //   · DB 里今天没有记录 → todayClockIn == null → 返回 CLOCK_IN
        //   · 而手动打卡的 targetAt 取的是「此刻」
        //   · 上班卡闸门窗口是 [target-75s, target+55s]，target=此刻 → 永远放行
        //   · 于是深夜也能打出一张「上班卡」
        //
        // 正确做法是先看**系统时钟**落在哪个班次附近：
        //   距上班时间更近 → CLOCK_IN；距下班时间更近 → CLOCK_OUT。
        // 只有两者都无法判断时才退回 DB 状态。
        val now = System.currentTimeMillis()
        val settings = AppConfig.settings.value
        val startOfDay = com.redmi.autoattend.core.PunchSchedule.startOfDay(now)
        val inAt = startOfDay + settings.clockInMinute * 60_000L
        val outAt = startOfDay + settings.clockOutMinute * 60_000L

        // 两班都已打 → 按时间就近归属（此时多半是补卡，就近最不容易错）
        if (s.todayClockIn != null && s.todayClockOut != null) {
            return if (kotlin.math.abs(now - outAt) <= kotlin.math.abs(now - inAt)) {
                PunchKind.CLOCK_OUT
            } else {
                PunchKind.CLOCK_IN
            }
        }

        // 只有一班已打 → 打另一班，这是明确无歧义的
        if (s.todayClockIn != null && s.todayClockOut == null) return PunchKind.CLOCK_OUT
        if (s.todayClockIn == null && s.todayClockOut != null) return PunchKind.CLOCK_IN

        // 两班都没打 → 按当前时刻就近归属（关键修复：不再无条件 CLOCK_IN）
        return if (kotlin.math.abs(now - outAt) <= kotlin.math.abs(now - inAt)) {
            PunchKind.CLOCK_OUT
        } else {
            PunchKind.CLOCK_IN
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