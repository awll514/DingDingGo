package com.redmi.autoattend.exec

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
import com.redmi.autoattend.MainActivity
import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.AppDatabase
import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.data.db.PunchLogEntity
import com.redmi.autoattend.data.db.PunchOutcome
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

        const val ACTION_EXECUTE = "com.redmi.autoattend.action.EXECUTE"
        const val ACTION_CANCEL = "com.redmi.autoattend.action.CANCEL"

    /**
     * 暂停当前执行（保留服务，可继续）。
     *
     * 与 ACTION_CANCEL 的区别：
     * - CANCEL：彻底终止本次打卡，不再继续；
     * - PAUSE：先停下来，给用户"等一下"的机会，之后可以恢复。
     */
    const val ACTION_PAUSE = "com.redmi.autoattend.action.PAUSE"

    /** 从暂停恢复 */
    const val ACTION_RESUME = "com.redmi.autoattend.action.RESUME"
        const val EXTRA_PREWARM = "extra_prewarm"

        private const val CHANNEL_ID = "punch_execution"
        private const val NOTIFICATION_ID = 2001

        /** 唤醒锁最长持有时间，防止异常泄漏导致耗电 */
        private const val WAKELOCK_TIMEOUT_MS = 5 * 60 * 1000L

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

    /** 当前执行的协程，暂停时要取消它 */
    private var job: kotlinx.coroutines.Job? = null

    /** 记住本次任务参数，恢复时要用 */
    private var pendingKind: PunchKind = PunchKind.CLOCK_IN
    private var pendingTargetAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AppConfig.init(this)
        AppConfig.reload()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_CANCEL) {
            Log.i(TAG, "收到取消指令")
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_PAUSE) {
            // 暂停：取消当前协程但不停止服务，
            // 用户还能从悬浮窗点"继续"或者"取消"。
            Log.i(TAG, "收到暂停指令")
            paused = true
            job?.cancel()
            updateNotification(buildPausedNotification())
            return START_STICKY
        }

        if (action == ACTION_RESUME) {
            Log.i(TAG, "收到继续指令")
            paused = false
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
            startPunchFlow(pendingKind, pendingTargetAt, prewarm = false)
            return START_STICKY
        }

        // 必须在前 5 秒内 startForeground，否则系统会 ANR/杀进程
        startForeground(NOTIFICATION_ID, buildPreparingNotification())

        val kindName = intent?.getStringExtra(PunchAlarmReceiver.EXTRA_KIND)
            ?: PunchKind.CLOCK_IN.name
        val kind = runCatching { PunchKind.valueOf(kindName) }.getOrDefault(PunchKind.CLOCK_IN)
        val targetAt = intent?.getLongExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, 0L) ?: 0L
        val prewarm = intent?.getBooleanExtra(EXTRA_PREWARM, false) ?: false

        if (running) {
            // 【关键】不要 stopForeground！
            //
            // PREWARM 在 target-75s 启动本服务并 delayUntilTarget 等到 target，
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
        job = scope.launch {
            try {
                if (prewarm) {
                    log("prewarm 阶段，等待目标时刻")
                    // 预热闹钟只需把服务唤起来并持有唤醒锁；
                    // 真正的执行交给 FIRE 闹钟，避免提前 75 秒就开始点界面。
                    delayUntilTarget(targetAt)
                }
                runPunch(kind, targetAt)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // 暂停/取消导致的中断不算异常，不要打错误日志
                log("流程被中断（暂停或取消）")
                throw c
            } finally {
                if (!paused) {
                    cleanup()
                    running = false
                    stopSelf()
                }
            }
        }
    }

    /**
     * 等到目标时刻再执行。
     *
     * 用 kotlinx.coroutines.delay 而不是 Thread.sleep：
     * 1. 它是挂起函数，不会占住 IO 线程池里的一个线程长达 75 秒；
     * 2. 它可以被取消——如果用户在预热期间关掉开关，能立刻退出。
     *
     * 唤醒锁仍然由服务持有，所以进 Doze 也不会睡死。
     */
    private suspend fun delayUntilTarget(targetAt: Long) {
        if (targetAt <= 0) return
        val wait = targetAt - System.currentTimeMillis()
        if (wait <= 0) return
        // 分段等待，每段都检查一次是否还需要继续（可被取消）
        var remaining = wait
        while (remaining > 0) {
            val slice = minOf(remaining, 1_000L)
            kotlinx.coroutines.delay(slice)
            remaining -= slice
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun runPunch(kind: PunchKind, targetAt: Long) {
        val startedAt = System.currentTimeMillis()
        log("开始执行 kind=" + kind.name)

        updateNotification(buildRunningNotification(kind))
        val kindLabel = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            PunchKind.MANUAL -> "手动"
        }

        val engine = PunchEngine(applicationContext)
        val result = engine.execute(kind, wakeLock)

        val finishedAt = System.currentTimeMillis()
        log("执行结束 success=" + result.success + " stage=" + result.stage.label)

        // 落库
        val logEntity = PunchLogEntity(
            scheduledAt = if (targetAt > 0) targetAt else startedAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            kind = kind,
            outcome = if (result.success) PunchOutcome.SUCCESS else PunchOutcome.FAILURE,
            channel = result.channelUsed,
            failedStage = if (result.success) null else result.stage.label,
            message = result.message,
            evidence = result.evidence,
            trace = result.trace.joinToString("\n"),
        )
        runCatching {
            AppDatabase.get(applicationContext).punchLogDao().insert(logEntity)
        }.onFailure { Log.e(TAG, "写日志失败: " + it.javaClass.simpleName) }

        // 发通知
        PunchNotifier.notifyResult(applicationContext, result, kind, finishedAt)

        // 【关键】成功后立刻重排闹钟。
        //
        // 用户原话：「手动打卡成功后必须替换掉自动打卡的时间节点」。
        //
        // scheduleNext 现在会读"今天已成功的班次"，所以重排后
        // 刚打完的这一班会被跳过，下一次自动打卡自动顺延到下一班。
        // 不重排的话，已经注册的旧闹钟仍会按原时间触发。
        if (result.success) {
            runCatching { AlarmScheduler.scheduleNext(applicationContext) }
                .onFailure { Log.w(TAG, "重排闹钟失败: " + it.javaClass.simpleName) }
        }
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
            .setSmallIcon(com.redmi.autoattend.R.drawable.ic_stat_guard)
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

    private fun cleanup() {
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