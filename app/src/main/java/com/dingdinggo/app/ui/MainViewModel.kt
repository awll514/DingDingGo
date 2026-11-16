package com.dingdinggo.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dingdinggo.app.core.AlarmScheduler
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.ChannelPreference
import com.dingdinggo.app.core.HealthCheck
import com.dingdinggo.app.core.NextPunchSlot
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.AppDatabase
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.data.db.PunchLogEntity
import com.dingdinggo.app.data.db.PunchOutcome
import com.dingdinggo.app.exec.PunchNotifier
import com.dingdinggo.app.exec.RootShell
import com.dingdinggo.app.ui.theme.ThemeMode
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
import com.dingdinggo.app.ble.BtDeviceLearner
import com.dingdinggo.app.ble.BtScanner
import com.dingdinggo.app.core.BtTriggerStore
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

    /** 蓝牙触发源的实时状态（首页状态卡与设置页共用） */
    val bt: BtUiState = BtUiState(),
)

/**
 * 蓝牙触发源在 UI 上的状态快照。
 *
 * 这些值全部来自 [com.dingdinggo.app.core.BtTriggerStore] 的 StateFlow，
 * 不额外查库、不额外扫蓝牙 —— ticker 只是把它们同步进同一个 UiState，
 * 保证一次重组就能拿到完整画面。
 */
data class BtUiState(
    /** 功能开关是否打开 */
    val enabled: Boolean = false,

    /** 当前是否"在场"（最近 90 秒内扫到过考勤机） */
    val present: Boolean = false,

    /** 已学到的考勤机名称（没学过为空） */
    val deviceName: String = "",

    /** 已学到的考勤机 MAC（没学过为空） */
    val deviceMac: String = "",

    /** 扫描是否已成功注册到系统 */
    val scanRegistered: Boolean = false,

    /** 当前是否处于蓝牙扫描活跃时间窗内 */
    val scanInWindow: Boolean = true,

    /** 最近一次触发结论文案（"为什么没打"就在这里） */
    val lastTriggerText: String = "",

    /** 最近一次触发时刻 */
    val lastTriggerAt: Long = 0L,

    /** 早到窗口（分钟） */
    val earlyWindowMinutes: Int = 120,

    /**
     * 缺失的、会导致**实际扫不到设备**的权限说明（null = 权限齐全）。
     *
     * 真机踩坑（2026-11-13）：只给 BLUETOOTH_SCAN、不给定位权限时，
     * 扫描注册成功、dumpsys 显示在跑，但结果永远是 0。
     * 这种"看起来正常、实际失效"的状态必须显式暴露给用户，
     * 否则他会对着绿色的"已注册"干等。
     */
    val missingPermission: String? = null,
) {
    /** 副标题文案：一句话说清当前蓝牙功能的状态 */
    val summary: String
        get() = when {
            !enabled -> "未开启"
            present -> "已连接考勤机 · 在场"
            deviceName.isNotBlank() -> "已学习「" + deviceName + "」，当前不在场"
            else -> "已开启，尚未学习考勤机"
        }
}

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

        // 【审计 D6】日志保留策略此前是空话：
        // pruneOldLogs() 与 setLogRetentionDays() **全项目零调用**，
        // 而 PunchLogEntity / 设置页都写着"保留 30 天"。
        //
        // 放在这里（首帧之后、IO 线程）执行一次：清理是低频操作，
        // 每次冷启动做一次足够；失败也不影响任何功能。
        viewModelScope.launch {
            kotlinx.coroutines.delay(STARTUP_DEFER_MS * 3)
            withContext(Dispatchers.IO) {
                runCatching {
                    val cutoff = System.currentTimeMillis() -
                        AppConfig.logRetentionDays().toLong() * 24 * 60 * 60 * 1000L
                    db.punchLogDao().deleteOlderThan(cutoff)
                }
            }
        }

        // 蓝牙触发源初始化：读配置 + 按开关状态同步扫描注册。
        //
        // 放在 init 里是必须的 —— 进程被超管杀掉后重新打开 App 时，
        // 只有走到这里扫描才会被重新注册。KeepAliveService 的自检
        // 也会兜一次（见其 selfCheck）。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    BtTriggerStore.init(app)
                    BtScanner.sync(app)
                }
            }
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
    private var cachedLatestFailure: PunchLogEntity? = null

    @Volatile
    private var cachedDayKey: Int = -1

    @Volatile
    private var cachedScanInWindow: Boolean = true

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
            val name = com.dingdinggo.app.exec.PunchForegroundService::class.java.name
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
            cachedLatestFailure = runCatching {
                val recent = db.punchLogDao().recent(1).firstOrNull()
                if (recent != null &&
                    recent.startedAt >= startOfToday &&
                    recent.outcome == PunchOutcome.FAILURE
                ) {
                    recent
                } else {
                    null
                }
            }.getOrNull()
            cachedScanInWindow = runCatching {
                BtScanner.shouldKeepScanAliveNow(getApplication())
            }.getOrDefault(true)
            cachedDayKey = todayKey
            lastDbQueryAt = now
        }

        // 服务已经退出但 busy 还是 true → 说明被取消了，复位
        if (_busy.value && !isPunchServiceRunning()) {
            _busy.value = false
        }

        val inLog = cachedClockIn
        val outLog = cachedClockOut
        val latestFailure = cachedLatestFailure

        // 判定当前状态
        val status = deriveStatus(slot, now, inLog, outLog, latestFailure)

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
                com.dingdinggo.app.core.PunchSchedule.dayLabel(
                    nowMillis = now,
                    targetAtMillis = it.targetAtMillis,
                    activeDays = settings.activeDays,
                )
            } ?: com.dingdinggo.app.core.PunchSchedule.vacationLabel(settings.activeDays),
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
            // 蓝牙状态：从 store 的 StateFlow 读实时快照。
            // 放在 tick 里同步而不是另起一个 collect，是为了让
            // 首页所有信息在同一次重组里更新，不会出现"倒计时变了、
            // 蓝牙状态还停在上一秒"的撕裂感。
            bt = readBtState(),
        )
    }

    private fun deriveStatus(
        slot: NextPunchSlot?,
        now: Long,
        clockIn: PunchLogEntity?,
        clockOut: PunchLogEntity?,
        latestFailure: PunchLogEntity? = null,
    ): HomeStatus {
        if (_busy.value) return HomeStatus.Running

        // 今天两个都打完了
        if (clockIn != null && clockOut != null) {
            val latest = maxOf(clockIn.startedAt, clockOut.startedAt)
            return HomeStatus.Success(latest, null)
        }

        if (slot == null) {
            // 没有下一个点了：看最近一次结果的成败
            val latest = listOfNotNull(clockIn, clockOut, latestFailure).maxByOrNull { it.startedAt }
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
        if (delta <= PunchSchedule.PRE_WARM_SECONDS * 1000L && delta >= -PunchSchedule.RETRY_WINDOW_SECONDS * 1000L) {
            return HomeStatus.Waiting(slot.targetAtMillis)
        }

        // 今日最新一次尝试若以失败告终（且之后尚未成功），在等待下一班期间也要在首页显式提示失败与重试入口
        val latestSuccessAt = maxOf(clockIn?.startedAt ?: 0L, clockOut?.startedAt ?: 0L)
        if (latestFailure != null && latestFailure.startedAt > latestSuccessAt) {
            return HomeStatus.Failed(
                latestFailure.startedAt,
                latestFailure.failedStage ?: "未知",
                latestFailure.message ?: "打卡失败",
            )
        }

        return HomeStatus.Idle(slot.targetAtMillis)
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
                cachedScanInWindow = runCatching {
                    BtScanner.shouldKeepScanAliveNow(getApplication())
                }.getOrDefault(true)
                val slot = PunchSchedule.nextSlot(
                    System.currentTimeMillis(),
                    AppConfig.settings.value,
                )
                HealthCheck.check(getApplication(), slot)
            }
            _ui.value = _ui.value.copy(health = report, bt = readBtState())
        }
    }

    /** 仅刷新内存中的蓝牙状态快照（返回前台等轻量场景用） */
    fun refreshBtState() {
        BtTriggerStore.init(getApplication())
        _ui.value = _ui.value.copy(bt = readBtState())
    }

    /** 一键重新注册 BLE 扫描（IO 线程执行，完成后刷新健康度与蓝牙卡片） */
    fun repairBluetoothScan(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val app = getApplication<android.app.Application>()
                val res = runCatching { BtScanner.reregister(app) }.getOrDefault(false)
                cachedScanInWindow = runCatching {
                    BtScanner.shouldKeepScanAliveNow(app)
                }.getOrDefault(true)
                res
            }
            _ui.value = _ui.value.copy(bt = readBtState())
            refreshHealth()
            onDone(ok)
        }
    }

    /** 一键通过 root 加入电池白名单（IO 线程执行）；若 root 不可用则回调打开系统设置 */
    fun repairBatteryWhitelist(onDone: (rootApplied: Boolean) -> Unit) {
        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    HealthCheck.addToBatteryWhitelistViaRoot(getApplication())
                }.getOrDefault(false)
            }
            refreshHealth()
            onDone(applied)
        }
    }

    /** 立即在 IO 线程重排下一次闹钟并刷新健康度 */
    fun repairAlarm(onDone: (scheduled: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val scheduled = withContext(Dispatchers.IO) {
                runCatching {
                    AlarmScheduler.scheduleNext(getApplication()) != null
                }.getOrDefault(false)
            }
            refreshHealth()
            onDone(scheduled)
        }
    }

    fun setEnabled(value: Boolean) {
        AppConfig.setEnabled(value)
        viewModelScope.launch(Dispatchers.IO) {
            if (value) {
                // 重新开启：补回心跳/Job 复活链（关闭时被 uninstall 掉了）
                runCatching { com.dingdinggo.app.exec.ResurrectionService.install(getApplication()) }
                AlarmScheduler.scheduleNext(getApplication())
            } else {
                AlarmScheduler.cancelAll(getApplication(), AlarmScheduler.lastScheduled)
                // 【审计 §3.6 修复】关闭总开关时必须停掉心跳闹钟与 JobScheduler 任务。
                //
                // 原来 ResurrectionService.uninstall() **全项目零调用** ——
                // 用户关掉"自动打卡"后，心跳闹钟仍然每 10 分钟把进程唤醒一次、
                // JobScheduler 每 15 分钟跑一次，纯属空转。
                // 这直接违反 AGENTS.md 里唯一不可妥协的那条红线：
                // 「没运行时在后台的时候不要消耗资源」。
                runCatching { com.dingdinggo.app.exec.ResurrectionService.uninstall(getApplication()) }
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
            com.dingdinggo.app.core.ScheduleSettings(
                clockInMinute = clockInMinute,
                clockOutMinute = clockOutMinute,
                activeDays = days,
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            AlarmScheduler.clearSlotFired(getApplication())
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
            val kind = inferManualKind()
            try {
                val intent = android.content.Intent(
                    getApplication(),
                    com.dingdinggo.app.exec.PunchForegroundService::class.java,
                ).apply {
                    action = com.dingdinggo.app.exec.PunchForegroundService.ACTION_EXECUTE
                    // 手动打卡要落到"当前真正该打的那一次"上，
                    // 否则首页的今日上班/下班状态永远不会更新
                    // （首页只查 CLOCK_IN / CLOCK_OUT，MANUAL 不在统计里）。
                    putExtra(
                        com.dingdinggo.app.exec.PunchAlarmReceiver.EXTRA_KIND,
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
                    val startOfDay = com.dingdinggo.app.core.PunchSchedule
                        .startOfDay(System.currentTimeMillis())
                    val minute = when (kind) {
                        com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> settings.clockOutMinute
                        else -> settings.clockInMinute
                    }
                    putExtra(
                        com.dingdinggo.app.exec.PunchAlarmReceiver.EXTRA_TARGET_AT,
                        startOfDay + minute * 60_000L,
                    )
                }
                androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
            } catch (t: Throwable) {
                // 启动失败时用通知告知
                PunchNotifier.notifyStartBlocked(getApplication(), kind)
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
        val startOfDay = com.dingdinggo.app.core.PunchSchedule.startOfDay(now)
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

    // ---------- 蓝牙触发 ----------

    /** 写入蓝牙触发开关，并立即同步扫描注册状态 */
    fun setBtEnabled(enabled: Boolean) {
        BtTriggerStore.setEnabled(enabled)
        _ui.value = _ui.value.copy(bt = readBtState())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { BtScanner.sync(getApplication()) }
                cachedScanInWindow = runCatching {
                    BtScanner.shouldKeepScanAliveNow(getApplication())
                }.getOrDefault(true)
            }
            _ui.value = _ui.value.copy(bt = readBtState())
            refreshHealth()
        }
    }

    /** 设置早到窗口（分钟） */
    fun setBtEarlyWindow(minutes: Int) {
        BtTriggerStore.setEarlyWindowMinutes(minutes)
        _ui.value = _ui.value.copy(bt = readBtState())
    }

    /**
     * 学习当前考勤机。
     *
     * 用一次性回调扫描（ScanCallback 版本）找 fe3c 设备，
     * 找到最近的一台就记下来。这是一次**短时**扫描（最多 12 秒），
     * 与常驻的 PendingIntent 扫描互不干扰。
     *
     * @param onResult 回调 (成功?, 文案)
     */
    fun learnBtDevice(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BtDeviceLearner.learn(getApplication(), timeoutMs = 12_000L) }
                    .getOrElse { e -> BtDeviceLearner.Result(false, "学习失败：" + e.javaClass.simpleName) }
            }
            _ui.value = _ui.value.copy(bt = readBtState())
            onResult(result.ok, result.message)
            // 学到新设备后，扫描过滤器可能要按新 MAC 生效，重注册一次
            withContext(Dispatchers.IO) {
                runCatching { BtScanner.sync(getApplication()) }
            }
        }
    }

    /** 清除已学到的考勤机 */
    fun clearBtDevice() {
        BtTriggerStore.clearDevice()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { BtScanner.sync(getApplication()) }
            }
        }
        _ui.value = _ui.value.copy(bt = readBtState())
    }

    /**
     * 蓝牙权限申请结果回调。
     *
     * 授权后必须**立刻重注册扫描** —— 权限刚拿到时系统并不会自动
     * 恢复之前失败的注册请求。不重建的话用户会看到"权限已给、
     * 扫描仍未注册"，非常困惑。
     */
    fun onBtPermissionResult(granted: Boolean) {
        // 权限刚变化时，系统侧可能仍持有旧的失败状态（例如刚撤销时
        // 我们 stop 过、或注册因缺权限被拒）。这里用 reregister
        // 而非 sync：先干净地停一次，再按新权限重建，
        // 避免"授予了权限却仍不扫描、必须重启 App 才行"的体验。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (BtTriggerStore.enabled.value) {
                        BtScanner.reregister(getApplication())
                    } else {
                        BtScanner.stop(getApplication())
                    }
                }
                cachedScanInWindow = runCatching {
                    BtScanner.shouldKeepScanAliveNow(getApplication())
                }.getOrDefault(true)
            }
            _ui.value = _ui.value.copy(bt = readBtState())
            refreshHealth()
        }
    }

    /**
     * 设置页「授权扫描」点击后调用：强制申请权限并立刻重建扫描。
     *
     * 与冷启动的自动申请分开，因为用户主动点击时应无条件弹窗。
     */
    fun requestBtPermissionNow(onNeedActivityRequest: () -> Unit) {
        onNeedActivityRequest()
    }

    /** 清除今日蓝牙同步标记（调试 / 误触发后重置用） */
    fun resetBtToday() {
        BtTriggerStore.clearTodaySync()
        invalidateTodayCache()
    }

    /** 从 store 读一份当前蓝牙状态快照（纯内存读，无 I/O） */
    private fun readBtState(): BtUiState {
        // refreshPresence 只是比较两个 Long，无 I/O，可以安全地 200ms 调一次
        BtTriggerStore.refreshPresence()
        return BtUiState(
            enabled = BtTriggerStore.enabled.value,
            present = BtTriggerStore.present.value,
            deviceName = BtTriggerStore.deviceName.value,
            deviceMac = BtTriggerStore.deviceMac.value,
            scanRegistered = BtTriggerStore.scanRegistered.value,
            scanInWindow = cachedScanInWindow,
            lastTriggerText = BtTriggerStore.lastTriggerText.value,
            lastTriggerAt = BtTriggerStore.lastTriggerAt.value,
            earlyWindowMinutes = BtTriggerStore.earlyWindowMinutes.value,
            // 只有"功能开着"时才值得报告权限缺失（关着的时候不重要）
            missingPermission = if (BtTriggerStore.enabled.value) {
                val app = getApplication<android.app.Application>()
                when {
                    !BtScanner.hasScanPermission(app) -> "缺少「附近的设备」权限"
                    !BtScanner.hasLocationPermission(app) -> "缺少定位权限（本机会因此扫不到设备）"
                    else -> null
                }
            } else {
                null
            },
        )
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
            cachedClockIn = null
            cachedClockOut = null
            cachedLatestFailure = null
            invalidateTodayCache()
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