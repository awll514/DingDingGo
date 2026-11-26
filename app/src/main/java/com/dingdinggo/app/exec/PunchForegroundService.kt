package com.dingdinggo.app.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.dingdinggo.app.MainActivity
import com.dingdinggo.app.core.AlarmScheduler
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.NextPunchSlot
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.AppDatabase
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.data.db.PunchLogEntity
import com.dingdinggo.app.data.db.PunchOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 打卡执行的前台服务。
 *
 * 职责边界（有意收紧）：
 * - 只在打卡窗口临近时启动，执行完立刻 stopSelf，不做常驻；
 * - 通知渠道用 IMPORTANCE_LOW，避免打扰；
 * - 整个执行过程持有 PARTIAL_WAKE_LOCK，完成后立刻释放。
 *
 * 为什么不用 WorkManager 触发：Android 16 对后台作业有配额限制，
 * 准点性无法保证。硬调度走 AlarmManager，本服务只做执行编排。
 */
class PunchForegroundService : Service() {

    companion object {
        private const val TAG = "PunchService"

        const val ACTION_EXECUTE = "com.dingdinggo.app.action.EXECUTE"
        const val ACTION_CANCEL = "com.dingdinggo.app.action.CANCEL"

    /**
     * 暂停当前执行（保留服务，可继续）。
     *
     * 与 ACTION_CANCEL 的区别：
     * - CANCEL：彻底终止本次打卡，不再继续；
     * - PAUSE：先停下来，给用户"等一下"的机会，之后可以恢复。
     */
    const val ACTION_PAUSE = "com.dingdinggo.app.action.PAUSE"

    /** 从暂停恢复 */
    const val ACTION_RESUME = "com.dingdinggo.app.action.RESUME"
        const val EXTRA_PREWARM = "extra_prewarm"

        /**
         * 本次执行的触发来源。
         *
         * ## 为什么需要它
         *
         * 蓝牙触发的语义与闹钟触发**根本不同**：
         * - 闹钟：targetAt 是"用户配置的计划时刻"，同时充当红线判定基准；
         * - 蓝牙：检测时刻 = 用户想要的"今日实际打卡时间"，但它**不能**
         *   当红线基准 —— 一旦用它当基准，"迟到边界"就永远相对它计算，
         *   等于永远放行（这正是手动打卡踩过的坑）。
         *
         * 所以蓝牙触发需要两个时间：
         * - 红线基准 = 今天配置的上下班时间（固定，不漂移）；
         * - 记录时间 = 实际检测时刻（写库、显示、通知正文用）。
         *
         * 来源标记就是用来让服务知道"这次要按哪套语义走"。
         */
        const val EXTRA_SOURCE = "extra_source"

        /** 来源：系统闹钟（默认） */
        const val SOURCE_ALARM = "alarm"

        /** 来源：用户在首页点「立即手动打卡」 */
        const val SOURCE_MANUAL = "manual"

        /** 来源：检测到公司考勤机蓝牙 */
        const val SOURCE_BLUETOOTH = "bluetooth"

        /** 来源：连上公司 Wi-Fi */
        const val SOURCE_WIFI = "wifi"

        /** 来源：用户点击了早上首次迟到确认中的「确认打迟到卡」 */
        const val SOURCE_LATE_CONFIRMED = "late_confirmed"

        /** 是否允许本次上班打卡放行迟到边界（仅在用户显式确认后置 true） */
        const val EXTRA_ALLOW_LATE = "extra_allow_late"

        /**
         * 来源：蓝牙或 Wi-Fi 触发，但同时也带了"红线判定基准"。
         *
         * 到岗触发时用 EXTRA_BT_GATE_TARGET_AT 传**配置的计划时刻**，
         * 而 EXTRA_TARGET_AT 传**检测时刻**。
         */
        const val EXTRA_BT_GATE_TARGET_AT = "extra_bt_gate_target_at"

        private fun isArrivalSource(src: String): Boolean =
            src == SOURCE_BLUETOOTH || src == SOURCE_WIFI

        private const val CHANNEL_ID = "punch_execution"
        private const val NOTIFICATION_ID = 2001

        /**
         * 暂停状态的最长存活时间。
         *
         * ## 为什么暂停必须会过期
         *
         * 暂停曾是"永久"状态：一旦点过暂停，`running` 再也不会变回 false，
         * 之后每一次 FIRE / RETRY 闹钟都会走进"已有执行在进行"分支被直接忽略。
         * 用户忘了点"继续"，当天就再也打不上卡，而且界面上看不出原因。
         *
         * 这里给暂停加一个明确的有效期（与唤醒锁上限同量级）。
         * 超过之后状态自动失效，下一次闹钟可以正常接管。
         */
        private const val PAUSE_MAX_MS = 5 * 60 * 1000L

        /**
         * 唤醒锁最长持有时间，防止异常泄漏导致耗电。
         *
         * 【审计 B1 修复】必须**大于**打卡流程的合法上界，否则会中途失效。
         *
         * 旧值 5 分钟，而 PunchEngine 的外勤等待上限是 15 分钟
         * （OUTDOOR_WAIT_TOTAL_MS）。也就是说流程跑到第 5 分钟时
         * 唤醒锁就已经自动释放，之后：
         *   · 屏幕一旦熄灭 → 设备进入 suspend；
         *   · 外勤轮询用的 delay(5s) 会被拉长到十几分钟；
         *   · 引擎"最多等 15 分钟"的保护形同虚设 → 本班次漏卡。
         *
         * 现在取 20 分钟（15 分钟外勤等待 + 执行/解锁/点击余量），
         * 并保证与 PunchEngine 的上限是同向不等式。
         * 超时仍保留：它是"异常泄漏"的最后兜底，不是正常路径的节拍器。
         */
        private const val WAKELOCK_TIMEOUT_MS = 20 * 60 * 1000L

        /**
         * 请求暂停当前执行（悬浮提示的「暂停」按钮用）。
         *
         * 用 startService 而不是 bindService：服务已经在跑，
         * 这里只是投递一个指令。
         */
        fun requestPause(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PunchForegroundService::class.java)
                        .setAction(ACTION_PAUSE),
                )
            }
        }

        /**
         * 请求继续执行（悬浮提示的「▶」按钮用）。
         *
         * 暂停只是取消了当前协程，服务和悬浮窗都还在 ——
         * 用户点「▶」后由这里重新投递 ACTION_RESUME，
         * 服务会重走一次执行流程（跳过预热等待）。
         */
        fun requestResume(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PunchForegroundService::class.java)
                        .setAction(ACTION_RESUME),
                )
            }
        }

        /** 请求取消当前执行（悬浮提示的「取消」按钮用） */
        fun requestCancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PunchForegroundService::class.java)
                        .setAction(ACTION_CANCEL),
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    /** 主线程写、IO 协程读，必须 volatile 保证可见性 */
    @Volatile
    private var running = false

    /** 是否处于暂停状态（暂停后保留服务，可恢复） */
    @Volatile
    private var paused = false

    /** 暂停的失效时刻。到点后不再阻塞新的执行请求。 */
    @Volatile
    private var pausedUntil = 0L

    /** 暂停是否仍然有效（既在暂停态，又没超时） */
    private fun isPauseActive(): Boolean =
        paused && System.currentTimeMillis() < pausedUntil

    /** 当前执行的协程，暂停时要取消它 */
    private var job: kotlinx.coroutines.Job? = null

    /**
     * 执行代次。每次 startPunchFlow 自增，用于隔离"被取消的旧 job"的收尾动作。
     *
     * 【P0 竞态修复】背景（独立审计 audit/service.md）：
     *
     * 暂停超时后会有"新请求接管"分支（见 onStartCommand）：
     *   job?.cancel(); running = false   然后往下启动新 job
     *
     * 但 Job.cancel() 是**异步投递**，旧 job 的 finally 会稍后才执行。
     * 而引擎大量步骤阻塞在 RootShell.exec 的 Thread.sleep 轮询里，
     * 不受协程取消影响，所以旧 finally 必然晚于接管代码执行。
     *
     * 旧 finally 此时看到 paused 已被置 false，于是走 else 分支：
     *   cleanup()（灭屏 + 撤前台通知）+ running = false + stopSelf()
     *
     * 后果非常严重：
     *   ① stopSelf() 会把**正在跑的新请求**所在的服务实例停掉
     *      → onDestroy → scope.cancel() → 新流程在打卡中途被取消 → 漏卡；
     *   ② running 被旧 finally 复位成 false，而新 job 还在跑
     *      → 互斥失效 → 后续请求可再进来 → 两条流程并发点击 → 重复打卡。
     *
     * 修法：finally 里先校验代次，不是自己那一代就不碰全局状态。
     */
    @Volatile
    private var jobGeneration: Int = 0

    /** 本次流程开始时屏幕是否本来就是灭的（决定收尾要不要灭屏） */
    @Volatile
    private var screenWasOffAtStart: Boolean = false

    /** 记住本次任务参数，恢复时要用 */
    private var pendingKind: PunchKind = PunchKind.CLOCK_IN
    private var pendingTargetAt: Long = 0L

    /**
     * 本次执行的触发来源（见 [EXTRA_SOURCE]）。
     *
     * 只有蓝牙来源会走"两个时间"的语义：红线判定用配置时刻，
     * 记录/显示用检测时刻。否则整个链路与原来完全一致。
     */
    @Volatile
    private var punchSource: String = SOURCE_ALARM

    /**
     * 蓝牙或 Wi-Fi 触发时的**红线判定基准**（今天配置的上下班时刻）。
     *
     * 0 表示不是到岗来源，此时红线基准就是 [pendingTargetAt]（老语义）。
     */
    @Volatile
    private var btGateTargetAt: Long = 0L

    /** 本次是否获用户显式确认放行上班迟到边界 */
    @Volatile
    private var allowLateConfirmed: Boolean = false

    /** 刚弹出迟到确认窗时保留当前可见屏幕，跳过灭屏与切回 */
    @Volatile
    private var deferCleanupForLateConfirm: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AppConfig.init(this)
        AppConfig.reload()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // ==================== 【P0】null Intent 绝不能当成一次打卡 ====================
        //
        // 这是"半夜自己亮屏解锁点钉钉"的根因。
        //
        // Android 对 START_STICKY 的服务会返回 **null Intent** 来重启；
        // 而旧代码在 intent == null 时用默认值兜底：
        //     kindName ?: CLOCK_IN
        //     targetAt  ?: 0L
        //     prewarm   ?: false
        // 于是变成了"立刻执行一次上班卡"。更糟的是上班卡的时间闸门
        // 只有上界（now > target+55s 才拒绝）而没有下界 ——
        // 凌晨 00:10 也会通过，然后点亮屏幕、解锁、拉起钉钉、点打卡。
        //
        // 正确语义：没有真实指令就没有任务。直接收尾退出。
        if (action == null && intent == null) {
            Log.w(TAG, "收到 null Intent（系统重启服务），没有可执行指令，直接退出")
            cleanup()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_CANCEL) {
            Log.i(TAG, "收到取消指令")
            // 推进代次并取消运行中协程，防止 Dispatchers.IO 协程继续点钉钉或覆盖状态
            jobGeneration++
            job?.cancel()
            job = null
            running = false
            paused = false
            pausedUntil = 0L
            ControlHudOverlay.hide()
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_PAUSE) {
            if (!running) {
                Log.i(TAG, "当前无运行中流程，忽略暂停指令")
                return START_NOT_STICKY
            }
            // 暂停：取消当前协程但不停止服务，
            // 用户还能从悬浮窗点"继续"或者"取消"。
            Log.i(TAG, "收到暂停指令")
            paused = true
            pausedUntil = System.currentTimeMillis() + PAUSE_MAX_MS
            // 先更新悬浮条暂停标记并刷新 UI，再取消协程，防止 execute() 的 CancellationException 先把悬浮条藏掉
            ControlHudOverlay.setPaused(
                applicationContext,
                true,
                "已暂停",
                "点 ▶ 继续执行，或点 X 取消",
            )
            job?.cancel()

            // 【关键】暂停期间必须立刻释放唤醒锁。
            //
            // 暂停的语义就是"先别动"，再占着 PARTIAL_WAKE_LOCK 纯属耗电。
            // 恢复时会重新 acquire（见 ACTION_RESUME）。
            releaseWakeLock()

            updateNotification(buildPausedNotification())
            return START_STICKY
        }

        if (action == ACTION_RESUME) {
            if (!paused || !running || pendingTargetAt <= 0L) {
                Log.i(
                    TAG,
                    "当前未处于可继续状态 (paused=" + paused + ", running=" + running + ", target=" + pendingTargetAt + ")，忽略继续指令",
                )
                return START_NOT_STICKY
            }
            Log.i(TAG, "收到继续指令")
            paused = false
            pausedUntil = 0L
            // 暂停时释放了唤醒锁，继续时要拿回来
            acquireWakeLock()
            // 【关键】继续时**不要**再走 prewarm 分支，也不要重新等待目标时刻。
            //
            // 之前的实现是 startPunchFlow(pendingKind, pendingTargetAt)，
            // prewarm 默认 false 所以不会等待 —— 这部分是对的。
            // 但整个 runPunch 会从"点亮屏幕"开始**重新执行一遍**：
            // 已经在考勤页了又去重新导航、时间闸门重新判定（可能因超时直接拒绝），
            // 表现为"点了继续，打卡却没有发生"。
            //
            // 现在的语义：继续 = 重新走一次完整流程，但跳过预热等待。
            // 时间闸门会用当前真实时间重新判定，这是正确的 ——
            // 如果暂停期间已经错过窗口，就应该拒绝而不是硬打。
            // 恢复前核对一次参数有效性。
            //
            // pendingTargetAt 是暂停那一刻的目标时刻。如果用户隔了很久才点
            // "继续"，时间闸门大概率会拒绝 —— 那是正确行为，但更早一步
            // 判断可以避免白跑一趟解锁 + 拉钉钉的完整流程。
            // 【蓝牙触发的窗口判定必须用 gateTarget】
            //
            // 蓝牙来源下 pendingTargetAt 是"检测时刻"（例如 08:47），
            // withinRetryWindow 的窗口是 target-75s ~ target+120s ——
            // 拿 08:47 去算，窗口早就过了，一按"继续"就会被判超窗放弃。
            //
            // 而蓝牙来源真正该判的是"配置的计划时刻 + 早到窗口"。
            // 所以这里用 btGateTargetAt（配置时刻）做基准，并且把窗口
            // 放宽成早到窗口 —— 与 BtPunchRule 完全同口径。
            val stillInWindow = if (com.dingdinggo.app.core.LateConfirmRule.isLateAllowedForKind(pendingKind, allowLateConfirmed)) {
                true
            } else if (btGateTargetAt > 0L) {
                // 到岗来源（蓝牙/Wi-Fi）：按班次分别判定，直接复用 BtPunchRule 的唯一实现。
                val now = System.currentTimeMillis()
                when (pendingKind) {
                    PunchKind.CLOCK_OUT ->
                        now >= com.dingdinggo.app.core.BtPunchRule.clockOutEarliestAt(btGateTargetAt)

                    else -> {
                        val earlyMs = com.dingdinggo.app.core.BtPunchRule.earlyWindowMsOf(
                            com.dingdinggo.app.core.BtTriggerStore.earlyWindowMinutes.value,
                        )
                        now >= com.dingdinggo.app.core.BtPunchRule
                            .clockInEarliestAt(btGateTargetAt, earlyMs) &&
                            now <= com.dingdinggo.app.core.BtPunchRule
                                .clockInMustStopAt(btGateTargetAt)
                    }
                }
            } else {
                pendingTargetAt <= 0L ||
                    AlarmScheduler.withinRetryWindow(System.currentTimeMillis(), pendingTargetAt)
            }
            if (!stillInWindow) {
                Log.i(TAG, "恢复时已超出可打卡窗口，放弃并收尾")
                paused = false
                pausedUntil = 0L
                ControlHudOverlay.hide()
                cleanup()
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ControlHudOverlay.setPaused(
                applicationContext,
                false,
                "正在自动打卡",
                "正在恢复执行…",
            )
            startPunchFlow(pendingKind, pendingTargetAt, prewarm = false)
            return START_STICKY
        }

        // 必须在前 5 秒内 startForeground，否则系统会 ANR/杀进程
        startForeground(NOTIFICATION_ID, buildPreparingNotification())

        // 【P0】EXTRA_TARGET_AT 缺失同样不能默认成"现在就打"。
        // 目标时刻是安全闸门的唯一依据（迟到边界 / 下班最早时刻都相对它计算），
        // 没有它就等于让闸门拿 startOfDay + 设置分钟去硬算，
        // 单日临时覆盖（Override）会被完全忽略。
        // 走 null-Intent 守卫之后 intent 一定非空，Kotlin 智能转换已生效。
        val intentNonNull = requireNotNull(intent)
        val rawTargetAt = intentNonNull.getLongExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, 0L)
        if (rawTargetAt <= 0L) {
            Log.w(TAG, "缺少 EXTRA_TARGET_AT，无法确定目标时刻，放弃本次执行")
            cleanup()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }

        val kindName = intentNonNull.getStringExtra(PunchAlarmReceiver.EXTRA_KIND)
            ?: PunchKind.CLOCK_IN.name
        val kind = runCatching { PunchKind.valueOf(kindName) }.getOrDefault(PunchKind.CLOCK_IN)
        val targetAt = rawTargetAt
        val prewarm = intentNonNull.getBooleanExtra(EXTRA_PREWARM, false)
        val source = intentNonNull.getStringExtra(EXTRA_SOURCE) ?: SOURCE_ALARM
        val gateTargetFromIntent = intentNonNull.getLongExtra(EXTRA_BT_GATE_TARGET_AT, 0L)
        val allowLate = intentNonNull.getBooleanExtra(EXTRA_ALLOW_LATE, false)

        // 【fail-closed】蓝牙/Wi-Fi 到岗来源必须同时带"红线判定基准"。
        if (isArrivalSource(source) && gateTargetFromIntent <= 0L) {
            Log.e(TAG, "到岗来源缺少红线基准 (EXTRA_BT_GATE_TARGET_AT)，安全起见放弃本次执行 source=" + source)
            cleanup()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }

        // 暂停已超时但服务还吊着（用户没点继续也没点取消）：
        // 让本次新请求接管，否则这一整天都打不了卡。
        if (!isPauseActive() && paused) {
            Log.i(TAG, "暂停已超时，放弃暂停态交由本次请求接管")
            paused = false
            pausedUntil = 0L
            job?.cancel()
            // 递增代次：让被取消的旧 job 在 finally 里认出"我已经不是当前代"，
            // 从而不去 cleanup / 不复位 running / 不 stopSelf（见 jobGeneration 注释）。
            jobGeneration++
            running = false
            startForeground(NOTIFICATION_ID, buildPreparingNotification())
        }

        if (running) {
            // 【关键】不要 stopForeground！
            //
            // PREWARM 在 target-75s 启动本服务并一路跑到考勤页等 target，
            // 而 FIRE 恰好在 target 触发 —— 此时 running==true，必然走进这个分支。
            //
            // 如果在这里 stopForeground(STOP_FOREGROUND_REMOVE)，撤掉的正是
            // **那个正在等待执行的服务的常驻通知**：服务随即降级成后台服务，
            // HyperOS 下极易被回收，通知栏也看不到"正在执行"。
            // 这不是边缘情况，是每次打卡都会发生的设计冲突。
            //
            // 正确做法：只把自己这次多余的 startForeground 撤掉，
            // 保留正在跑的那一份通知。
            Log.i(TAG, "已有执行在进行，本次请求直接退出（保留运行中的通知）")
            return START_NOT_STICKY
        }
        running = true

        // 持有唤醒锁，保证屏幕关闭时 CPU 仍在跑
        acquireWakeLock()

        pendingKind = kind
        pendingTargetAt = targetAt
        punchSource = source
        btGateTargetAt = gateTargetFromIntent
        allowLateConfirmed = allowLate
        deferCleanupForLateConfirm = false

        // 记录"来之前屏幕是不是灭的"，收尾时决定要不要帮用户灭屏。
        screenWasOffAtStart = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive.not()
        }.getOrDefault(false)

        startPunchFlow(kind, targetAt, prewarm)

        return START_NOT_STICKY
    }

    /**
     * 启动打卡流程协程。
     *
     * 抽成独立方法是为了支持"暂停 → 继续"：
     * 暂停时 cancel 这个 job，继续时用相同参数再起一次。
     */
    private fun startPunchFlow(kind: PunchKind, targetAt: Long, prewarm: Boolean = false) {
        // 服务实例若在 stopSelf 完成前被新请求复用，必须重置已清理标记，否则收尾会被跳过
        cleanedUp = false
        deferCleanupForLateConfirm = false
        // 蓝牙来源：红线基准是"今天配置的时刻"，不是检测时刻。
        // 非蓝牙来源：两者相同（老语义，一字不改）。
        val gateTarget = if (btGateTargetAt > 0L) btGateTargetAt else targetAt
        // 本代次：后续并发/接管时以最新值为准，旧协程据此判断自己是否已被取代
        val myGeneration = ++jobGeneration
        job = scope.launch {
            try {
                // 【P0-4】预热不再"傻等 75 秒再全流程重跑"。
                //
                // 旧实现：prewarm 时 delayUntilTarget(targetAt) 干等，到点才
                // 从点亮屏幕开始跑整个流程 —— 8 秒硬等 + 20 秒轮询 + 导航，
                // 真机实测 27.7 秒才进考勤页，而上班卡合法窗口只有 55 秒。
                //
                // 现在：预热时直接把流程跑到"停在考勤页"，然后等目标时刻
                // 再点击（见 PunchEngine 的 holdUntilTarget）。
                runPunch(kind, targetAt, prewarm, gateTarget)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // 暂停/取消导致的中断不算异常，不要打错误日志
                log("流程被中断（暂停或取消）")
                throw c
            } finally {
                // 【P0 竞态修复】先判断自己是否已被新请求取代。
                //
                // 如果 myGeneration != jobGeneration，说明在我们执行期间
                // 有"暂停超时接管"或新的执行流程把代次推进了 —— 这次收尾
                // 属于一个**已经被取代**的旧流程，绝不能碰全局状态：
                //   · 不能 cleanup()：会灭屏、撤掉正在跑的新流程的通知；
                //   · 不能 running=false：会让互斥失效，放任并发重复打卡；
                //   · 不能 stopSelf()：会停掉新流程所在的服务实例 → 漏卡。
                //
                // 旧流程唯一该做的就是把自己的唤醒锁释放掉（新流程会自己 acquire）。
                if (myGeneration != jobGeneration) {
                    log("流程已被新请求取代（代次 " + myGeneration + " → " + jobGeneration + "），跳过收尾")
                    releaseWakeLock()
                    return@launch
                }

                // 暂停状态保持服务存活（用户还能点"继续"或"取消"），
                // 但必须同时检查是否已超时 —— 否则一个被遗忘的暂停会
                // 让 running 永远为 true，后续闹钟全部被忽略。
                if (isPauseActive()) {
                    // 保持暂停态：只释放唤醒锁，不清理通知
                    releaseWakeLock()
                } else {
                    paused = false
                    pausedUntil = 0L
                    cleanup()
                    running = false
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runPunch(
        kind: PunchKind,
        targetAt: Long,
        prewarm: Boolean = false,
        gateTargetAt: Long = targetAt,
    ) {
        val startedAt = System.currentTimeMillis()
        log(
            "开始执行 kind=" + kind.name +
                (if (prewarm) "（预热模式）" else "") +
                when {
                    punchSource == SOURCE_BLUETOOTH ->
                        "（蓝牙触发，红线基准=" + PunchSchedule.formatTime(gateTargetAt) + "）"
                    punchSource == SOURCE_WIFI ->
                        "（公司 Wi-Fi 触发，红线基准=" + PunchSchedule.formatTime(gateTargetAt) + "）"
                    allowLateConfirmed -> "（用户已确认打迟到卡）"
                    else -> ""
                },
        )

        updateNotification(buildRunningNotification(kind))
        val kindLabel = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            PunchKind.MANUAL -> "手动"
        }

        val engine = PunchEngine(applicationContext)

        val earlyWindowMs = if (isArrivalSource(punchSource)) {
            com.dingdinggo.app.core.BtPunchRule.earlyWindowMsOf(
                com.dingdinggo.app.core.BtTriggerStore.earlyWindowMinutes.value,
            )
        } else {
            0L
        }
        val result = engine.execute(
            kind = kind,
            wakeLock = wakeLock,
            targetAt = gateTargetAt,
            holdUntilTarget = prewarm,
            earlyWindowMs = earlyWindowMs,
            allowLate = allowLateConfirmed,
        )

        val finishedAt = System.currentTimeMillis()
        val displayAt = if (result.success && result.actualAt > 0L) result.actualAt else finishedAt
        log("执行结束 success=" + result.success + " stage=" + result.stage.label +
            (result.actualTimeText?.let { " actualTime=" + it } ?: ""))

        // 早上首次触发撞上迟到边界：发出高优先级通知与屏幕确认窗，不直接报警失败
        if (result.rejectedLate && !allowLateConfirmed && kind == PunchKind.CLOCK_IN) {
            val now = System.currentTimeMillis()
            val settings = AppConfig.settings.value
            val dayStart = PunchSchedule.startOfDay(now)
            val clockInTarget = if (gateTargetAt > 0L) {
                gateTargetAt
            } else {
                dayStart + settings.clockInMinute * 60_000L
            }
            val clockOutTarget = dayStart + settings.clockOutMinute * 60_000L
            com.dingdinggo.app.core.LateConfirmStore.init(applicationContext)
            val promptedDay = com.dingdinggo.app.core.LateConfirmStore
                .promptedDateKey(applicationContext, now)

            if (com.dingdinggo.app.core.LateConfirmRule.shouldPromptLateConfirm(
                    kind = kind,
                    nowMillis = now,
                    clockInTargetMillis = clockInTarget,
                    clockOutTargetMillis = clockOutTarget,
                    clockInDoneToday = false,
                    promptedDateKey = promptedDay,
                ) && com.dingdinggo.app.core.LateConfirmStore.tryClaimPromptToday(
                    context = applicationContext,
                    nowMillis = now,
                    plannedTargetAt = clockInTarget,
                    lateSeconds = result.lateSeconds,
                    source = punchSource,
                )
            ) {
                ControlHudOverlay.hide()
                val cancelEntity = PunchLogEntity(
                    scheduledAt = clockInTarget,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    kind = kind,
                    outcome = PunchOutcome.CANCELLED,
                    channel = result.channelUsed,
                    failedStage = null,
                    message = "首次触发上班打卡已过迟到边界，已弹窗/通知等待用户确认",
                    trace = result.trace.joinToString("\n"),
                )
                runCatching {
                    AppDatabase.get(applicationContext).punchLogDao().insert(cancelEntity)
                }.onFailure { Log.e(TAG, "写迟到确认日志失败: " + it.javaClass.simpleName) }

                val shownOnScreen = LateConfirmCoordinator.triggerLateConfirmPrompt(
                    context = applicationContext,
                    plannedTargetAt = clockInTarget,
                    lateSeconds = result.lateSeconds,
                    source = punchSource,
                )
                if (shownOnScreen) {
                    deferCleanupForLateConfirm = true
                }
                val slot = NextPunchSlot(
                    triggerAtMillis = clockInTarget - PunchSchedule.PRE_WARM_SECONDS * 1000L,
                    targetAtMillis = clockInTarget,
                    kind = kind,
                )
                AlarmScheduler.onPunchFinished(
                    context = applicationContext,
                    slot = slot,
                    markFired = true,
                )
                return
            }
        }

        // 落库
        val logMessage = if (allowLateConfirmed && result.success) {
            result.message + "（用户确认迟到补打）"
        } else {
            result.message
        }
        val logEntity = PunchLogEntity(
            scheduledAt = if (targetAt > 0) targetAt else startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            kind = kind,
            outcome = if (result.success) PunchOutcome.SUCCESS else PunchOutcome.FAILURE,
            channel = result.channelUsed,
            failedStage = if (result.success) null else result.stage.label,
            message = logMessage,
            evidence = result.evidence,
            trace = result.trace.joinToString("\n"),
            actualAt = result.actualAt,
            actualTimeText = result.actualTimeText,
        )
        runCatching {
            AppDatabase.get(applicationContext).punchLogDao().insert(logEntity)
        }.onFailure { Log.e(TAG, "写日志失败: " + it.javaClass.simpleName) }

        if (punchSource == SOURCE_BLUETOOTH && result.success) {
            runCatching {
                com.dingdinggo.app.core.BtTriggerStore.markSynced(kind)
                com.dingdinggo.app.core.BtTriggerStore.markTriggered(
                    System.currentTimeMillis(),
                    when (kind) {
                        PunchKind.CLOCK_IN -> "上班打卡成功"
                        PunchKind.CLOCK_OUT -> "下班打卡成功"
                        PunchKind.MANUAL -> "打卡成功"
                    },
                )
            }
        }
        if (punchSource == SOURCE_WIFI && result.success) {
            runCatching {
                com.dingdinggo.app.core.WifiTriggerStore.init(applicationContext)
                com.dingdinggo.app.core.WifiTriggerStore.markTriggered(
                    System.currentTimeMillis(),
                    when (kind) {
                        PunchKind.CLOCK_IN -> "公司 Wi-Fi 上班打卡成功"
                        PunchKind.CLOCK_OUT -> "公司 Wi-Fi 下班打卡成功"
                        PunchKind.MANUAL -> "打卡成功"
                    },
                )
            }
        }
        if (kind == PunchKind.CLOCK_IN && result.success) {
            runCatching {
                PunchNotifier.cancelLateConfirm(applicationContext)
                com.dingdinggo.app.core.LateConfirmStore.init(applicationContext)
                com.dingdinggo.app.core.LateConfirmStore.clearPendingIfDone(
                    applicationContext,
                    finishedAt,
                )
            }
        }

        // 发通知
        PunchNotifier.notifyResult(
            context = applicationContext,
            result = result,
            kind = kind,
            finishedAt = displayAt,
            actualTimeText = result.actualTimeText,
        )

        // 【关键】在**当前屏幕上**再显示一次结果，3 秒后自动淡出。
        //
        // 打卡时人是看着钉钉的，系统通知在状态栏里根本不会被注意到。
        // 结果必须在眼前出现一次，才算用户真的知情。
        // 迟到：上班打卡且实际打卡时刻已过「目标 + 1 分钟宽限」。
        // 这与 PunchEngine 的判定口径一致（LATE_GRACE_MS = 60s）。
        val isLate = result.success &&
            kind == PunchKind.CLOCK_IN &&
            gateTargetAt > 0 &&
            displayAt - gateTargetAt >= 60_000L

        val hudTitle = when {
            result.abandonedByGate && isLate -> "⏰ 上班打卡成功（迟到）"
            result.abandonedByGate -> "⏭️ 本次已跳过"
            isLate -> "⏰ 上班打卡成功（迟到）"
            result.success && kind == PunchKind.CLOCK_IN -> "✅ 上班打卡成功"
            result.success && kind == PunchKind.CLOCK_OUT -> "✅ 下班打卡成功"
            result.success -> "✅ 打卡成功"
            else -> "⚠️ 打卡失败"
        }
        val hudTone = when {
            result.abandonedByGate -> ControlHudOverlay.Tone.LATE
            isLate -> ControlHudOverlay.Tone.LATE
            result.success -> ControlHudOverlay.Tone.SUCCESS
            else -> ControlHudOverlay.Tone.FAILURE
        }
        val hudSubtitle = when {
            result.abandonedByGate -> result.message.take(26)
            result.success -> result.actualTimeText?.takeIf { it.isNotBlank() }
                ?: java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date(displayAt))
            else -> (result.stage.label + " · " + result.message).take(26)
        }
        // 与 PunchEngine 的 show() 同一个开关：用户关掉"屏幕接管提示"就都不弹。
        if (AppConfig.showControlHud.value) {
            ControlHudOverlay.showStatus(
                context = applicationContext,
                tone = hudTone,
                title = hudTitle,
                subtitle = hudSubtitle,
            )
        }

        // 【关键】成功后立刻重排闹钟。
        //
        // 用户原话：「手动打卡成功后必须替换掉自动打卡的时间节点」。
        //
        // scheduleNext 现在会读"今天已成功的班次"，所以重排后
        // 刚打完的这一班会被跳过，下一次自动打卡自动顺延到下一班。
        // 不重排的话，已经注册的旧闹钟仍会按原时间触发。
        // 【P0-1】重排统一在这里做，不再由闹钟接收器做。
        //
        // 成功 / 主动放弃（例如"已过迟到边界，为避免迟到记录跳过"）→ 标记本班已处理，
        // 下一次自动顺延到下一班；
        // 执行失败 → 不标记，让本班在补执行窗口内还能被 RETRY 再救一次。
        // 【关键】重排用的 slot 必须落在**配置的计划时刻**上。
        //
        // 蓝牙触发时 targetAt 是检测时刻（如 08:47），若拿它当 slot 的
        // targetAtMillis，onPunchFinished → markJustFired(08:47) 就标记了
        // 一个不存在的槽位；而今天真正的 09:30 槽位不受影响 → 闹钟到点
        // 仍会再打一次（重复打卡）。用 gateTargetAt 才是正确的那一班。
        val slot = NextPunchSlot(
            triggerAtMillis = gateTargetAt - PunchSchedule.PRE_WARM_SECONDS * 1000L,
            targetAtMillis = gateTargetAt,
            kind = kind,
        )
        // 【P0】太早被拒不能算「这一班已处理」。
        //
        // 真机复现（2026-11-12 12:48）：提前触发的下班卡被时间闸门正确拒绝，
        // 但这里把 rejectedTooEarly 当成了 abandonedByGate，于是
        // onPunchFinished(markFired=true) → scheduleNext 跳到下一班 →
        // cancelAll 把**今天 18:30** 的 PREWARM / FIRE / RETRY 全部取消，
        // 当天真正的下班卡静默漏打。
        //
        // 现在的口径：
        //   · 成功            → 标记已处理，顺延下一班
        //   · 主动放弃(迟到/外勤超时) → 标记已处理，顺延下一班
        //   · 太早被拒        → **不标记**，本班闹钟原样保留
        //   · 执行失败        → 不标记，让 RETRY 兜底
        AlarmScheduler.onPunchFinished(
            context = applicationContext,
            slot = slot,
            markFired = result.success ||
                (result.abandonedByGate && !result.rejectedTooEarly),
        )
    }

    /** 暂停状态的通知：告诉用户"已暂停，还能继续" */
    private fun buildPausedNotification(): Notification {
        val stopIntent = android.app.PendingIntent.getService(
            this,
            3,
            Intent(this, PunchForegroundService::class.java).setAction(ACTION_CANCEL),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = android.app.PendingIntent.getService(
            this,
            4,
            Intent(this, PunchForegroundService::class.java).setAction(ACTION_RESUME),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.dingdinggo.app.R.drawable.ic_stat_guard)
            .setContentTitle("打卡已暂停")
            .setContentText("可以继续，也可以取消这次执行")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, "继续", resumeIntent).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "取消", stopIntent).build(),
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "autoattend:punch",
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKELOCK_TIMEOUT_MS) }
        }
        log("已获取唤醒锁")
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    /**
     * 收尾：灭屏 + 释放唤醒锁 + 撤掉常驻通知。
     *
     * ## 为什么要主动灭屏（F3.7 明确要求，之前完全没做）
     *
     * 打卡流程会把屏幕点亮、解锁、把钉钉拉到前台。打完之后如果不灭屏：
     *   · 夜里打卡会把屏幕亮一整夜（耗电 + 隐私暴露）；
     *   · 用户第二天早上看到的是钉钉页面，而不是自己的锁屏。
     *
     * RootShell.screenOff() 早就写好了，但**全库没有一个调用点**。
     *
     * 只在"是这次流程点亮的屏幕"时才灭：如果用户本来就在用手机，
     * 我们无权把屏幕关掉。
     */
    private fun finishAndMaybeScreenOff() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive && screenWasOffAtStart) {
                RootShell.screenOff()
                log("已灭屏收尾")
            }
        }
    }

    /**
     * 打卡结束后把本应用切回前台（用户明确诉求：无论成功失败都跳回钉钉GO）。
     *
     * ## 为什么必须用 root
     *
     * Android 15+ 的后台 Activity 启动（BAL）限制：本服务是后台 FGS，
     * 直接 startActivity() 会被系统静默拦掉（日志里只有
     * "Background activity launch blocked!"，不抛异常）。
     *
     * 而 `su -c am start` 的调用者 uid 是 0，AOSP 把 uid 0 视为 BAL 白名单，
     * 所以这条路能过 —— 与本项目拉起钉钉用的是同一个原理（见 RootShell）。
     *
     * ## 无 root 时
     *
     * 退化为一次常规 startActivity（可能被拦，静默无副作用），
     * 并在日志里写明，不阻塞打卡收尾。
     *
     * ## 与灭屏的关系（重要）
     *
     * 跳回和灭屏是**互斥**的两件事，不能同时做：
     *
     *   · 跳回 → 用户要看到结果 → **不能灭屏**；
     *   · 不跳回 → 流程把屏幕点亮又用完 → 应该灭屏复原。
     *
     * 之前把两者都做了（先跳回再灭屏），实测结果就是
     * 「ResumedActivity 确实是 MainActivity，但屏幕已经黑了」——
     * 功能等于没生效。所以灭屏由本函数的返回值决定，见 cleanup()。
     *
     * @return true 表示已经把 App 切到前台，调用方不应再灭屏
     */
    private fun returnToAppIfRequested(): Boolean {
        if (!AppConfig.returnAfterPunch.value) {
            log("用户关闭了『打卡后跳回』，保持当前界面")
            return false
        }
        val pkg = applicationContext.packageName
        val ok = if (RootShell.isAvailable()) {
            val out = RootShell.exec(
                "am start -n " + pkg + "/com.dingdinggo.app.MainActivity",
                timeoutMs = 8_000,
            )
            !out.contains("Error") && !out.contains("Exception")
        } else {
            runCatching {
                val i = android.content.Intent(
                    this,
                    com.dingdinggo.app.MainActivity::class.java,
                ).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(i)
                true
            }.getOrDefault(false)
        }
        log(if (ok) "已切回钉钉GO" else "切回钉钉GO失败（后台启动可能被系统拦截）")
        return ok
    }

    /**
     * 收尾是否已执行过（幂等保护）。
     *
     * 【审计 P3】旧实现里 ACTION_CANCEL 分支先调一次 cleanup()，随后
     * stopSelf() 触发 onDestroy 又调一次 —— 而 cleanup() 内部会 fork su
     * 做"跳回"(am start, 8s 超时) 或"灭屏"(input keyevent, 8s 超时)。
     * 于是用户点一次「取消」，主线程上要串行吃两轮 su，最坏十几秒 → ANR。
     *
     * 现在 cleanup 只真正执行一次，后续调用直接返回。
     */
    @Volatile
    private var cleanedUp = false

    private fun cleanup() {
        if (cleanedUp) {
            log("收尾已执行过，跳过重复清理")
            return
        }
        cleanedUp = true

        if (deferCleanupForLateConfirm) {
            deferCleanupForLateConfirm = false
            releaseWakeLock()
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            log("保留迟到确认弹窗在屏幕前景，跳过切回与灭屏收尾")
            return
        }

        // 跳回 App 时就**不灭屏** —— 用户要能看到结果页。
        // 不跳回时保持原有语义：流程点亮的屏幕要负责熄灭收尾。
        val returned = returnToAppIfRequested()
        if (returned) {
            log("已跳回本应用，跳过灭屏（让用户看到结果）")
        } else {
            finishAndMaybeScreenOff()
        }
        releaseWakeLock()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        log("清理完成")
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "打卡执行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示打卡执行进度，不会发出提示音"
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildPreparingNotification(): Notification =
        baseNotification()
            .setContentTitle("准备打卡")
            .setContentText("正在准备执行环境…")
            .build()

    private fun buildRunningNotification(kind: PunchKind): Notification {
        val label = when (kind) {
            PunchKind.CLOCK_IN -> "上班打卡"
            PunchKind.CLOCK_OUT -> "下班打卡"
            PunchKind.MANUAL -> "手动打卡"
        }
        return baseNotification()
            .setContentTitle(label)
            .setContentText("正在执行打卡流程…")
            .build()
    }

    private fun baseNotification(): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_LOW)
    }

    private fun updateNotification(n: Notification) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }
}
