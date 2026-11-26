package com.dingdinggo.app.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dingdinggo.app.MainActivity
import com.dingdinggo.app.R
import com.dingdinggo.app.core.AlarmScheduler
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.BtTriggerStore
import com.dingdinggo.app.core.LateConfirmStore
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.core.WifiTriggerStore
import com.dingdinggo.app.wifi.WifiTriggerMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻保活服务。
 *
 * ## 它到底做什么（以及不做什么）
 *
 * 这个服务**不负责打卡**——打卡靠 AlarmManager 的精确闹钟，那个才是准点保证。
 * 它只做三件事：
 *
 * 1. **挂一条常驻通知**：让你随时能看到"它还活着"。
 *    这是用户明确要求的可见性——后台服务最容易"悄悄死掉"，
 *    没有常驻通知的话你根本不知道哪天开始就不打卡了。
 * 2. **每 15 分钟自检一次闹钟是否还在**：
 *    HyperOS 可能在某些情况下清掉闹钟（比如省电策略激进时），
 *    一旦发现闹钟没了就立刻重新注册。这是最后一道保险。
 * 3. **保持进程优先级**：前台服务的进程优先级远高于普通后台进程，
 *    被 LMK（低内存杀手）回收的概率大幅降低。
 *
 * ## 诚实边界
 *
 * 前台服务**不能保证永生**。HyperOS 仍可能在某些极端情况下杀掉它
 * （比如用户手动"强行停止"、或系统级内存回收）。
 * 所以通知里会显示真实的运行时长，让你能自己判断它有没有断过。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"

        /** 常驻通知 id（和打卡结果通知区分开） */
        const val NOTIFICATION_ID = 2002

        const val CHANNEL_ID = "keep_alive"

        const val ACTION_START = "com.dingdinggo.app.action.KEEPALIVE_START"
        const val ACTION_STOP = "com.dingdinggo.app.action.KEEPALIVE_STOP"
        const val ACTION_REFRESH = "com.dingdinggo.app.action.KEEPALIVE_REFRESH"

        /** 自检间隔：15 分钟 */
        private const val SELF_CHECK_INTERVAL_MS = 15 * 60 * 1000L

        /** 服务启动时刻，用于在通知里显示"已运行多久" */
        @Volatile
        var startedAt: Long = 0L
            private set

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "启动保活服务失败: " + it.javaClass.simpleName) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /** 让通知内容刷新一次（例如打卡成功后更新"下次打卡"信息） */
        fun refresh(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java).apply {
                    action = ACTION_REFRESH
                }
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var screenReceiverRegistered = false

    @Volatile
    private var lastScreenArrivalCheckAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_USER_PRESENT && action != Intent.ACTION_SCREEN_ON) return

            val unlocked = action == Intent.ACTION_USER_PRESENT
            scope.launch(Dispatchers.IO) {
                runCatching {
                    AppConfig.init(this@KeepAliveService)
                    BtTriggerStore.init(this@KeepAliveService)
                    WifiTriggerStore.init(this@KeepAliveService)
                    LateConfirmStore.init(this@KeepAliveService)

                    if (unlocked) {
                        LateConfirmCoordinator.showOverlayOnUnlockIfPending(this@KeepAliveService)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastScreenArrivalCheckAt < 60_000L) return@runCatching
                    lastScreenArrivalCheckAt = now

                    if (!AppConfig.enabled.value) return@runCatching
                    val inWindow = com.dingdinggo.app.ble.BtScanner
                        .shouldKeepScanAliveNow(this@KeepAliveService)
                    if (!inWindow) return@runCatching

                    if (WifiTriggerStore.enabled.value) {
                        WifiTriggerMonitor.checkNow(this@KeepAliveService)
                    }
                    if (BtTriggerStore.enabled.value) {
                        com.dingdinggo.app.ble.BtScanner.activeBurstScanAndHandle(
                            context = this@KeepAliveService,
                            durationMs = 4_000L,
                            force = unlocked,
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "亮屏/解锁到岗检测异常: " + it.javaClass.simpleName)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须尽快 startForeground，否则系统会在 5 秒内判定 ANR
        startForeground(NOTIFICATION_ID, buildNotification())

        runCatching {
            AppConfig.init(this)
            BtTriggerStore.init(this)
            WifiTriggerStore.init(this)
            LateConfirmStore.init(this)
            WifiTriggerMonitor.sync(this)
            registerScreenReceiverIfNeeded()
        }

        // 启动时就把无障碍准备好。
        scope.launch {
            runCatching { AccessibilityKeeper.ensureEnabled(this@KeepAliveService) }
        }

        startSelfCheckLoop()

        // START_STICKY：被系统杀掉后自动重启（配合 Manifest 的 specialUse 类型）
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        runCatching { WifiTriggerMonitor.stop(this) }
        selfCheckLoopRunning = false
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    private fun registerScreenReceiverIfNeeded() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }.onFailure {
            Log.w(TAG, "注册亮屏/解锁监听失败: " + it.javaClass.simpleName)
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        runCatching { unregisterReceiver(screenReceiver) }
    }

    // ---------- 自检循环 ----------

    /**
     * 自检循环是否已经在跑。
     *
     * 【P1 修复·重复循环】没有这个守卫时，每次 [onStartCommand] 都会
     * 新起一个 `while (isActive) { delay(15min); selfCheck() }` 无限循环，
     * 而 onStartCommand 会被极其频繁地调用：
     *
     *   · ResurrectionService.resurrect() 每次复活都调 KeepAliveService.start()
     *     （心跳 10 分钟一次 + JobScheduler 15 分钟一次，都会走这里）；
     *   · START_STICKY 被系统杀掉后自动重启；
     *   · 权限变更、用户打开 App 等路径。
     *
     * 后果是 N 次启动 → N 个并行自检循环 → 自检频率变成 N × (每 15 分钟)。
     * 每个循环都会 fork su 检查无障碍、重注册 BLE 扫描、重排闹钟 ——
     * 这是纯粹的资源浪费，直接违背「后台零消耗」红线。
     *
     * 真机 batterystats 佐证：本应用 fgs 时间 3h58m、功耗 47 mAh，
     * 而 ResurrectionService 的 job 记录出现 158 次（按 15 分钟周期
     * 一天应该是 96 次）。
     *
     * 用一个 @Volatile 标志保证全进程只有一个循环。
     * 注意：不能用「job.isActive」判断 —— scope 是服务级的，
     * 服务销毁会 cancel，而这里要防的是同一服务实例内的重复启动。
     */
    @Volatile
    private var selfCheckLoopRunning = false

    /** 保护 [selfCheckLoopRunning] 的检查-置位，避免并发启动时两者都通过 */
    private val loopStartLock = Any()

    private fun startSelfCheckLoop() {
        // 检查与置位必须是原子的。虽然服务回调通常串行，
        // 但 ResurrectionService 的 job 与心跳可能在不同线程同时触发 start，
        // 用锁消除这个理论竞态，成本可以忽略。
        synchronized(loopStartLock) {
            if (selfCheckLoopRunning) {
                Log.i(TAG, "自检循环已在运行，跳过重复启动")
                return
            }
            selfCheckLoopRunning = true
        }
        scope.launch {
            try {
                while (isActive) {
                    delay(SELF_CHECK_INTERVAL_MS)
                    runCatching { selfCheck() }
                        .onFailure { Log.w(TAG, "自检异常: " + it.javaClass.simpleName) }
                }
            } finally {
                // 循环因服务销毁而退出时复位，允许下次重建
                selfCheckLoopRunning = false
            }
        }
    }

    /**
     * 自检：闹钟还在不在？不在就补注册。
     *
     * 这是防止"服务活着但闹钟没了"这种静默失效的最后一道防线。
     */
    private fun selfCheck() {
        AppConfig.init(this)
        AppConfig.reload()

        // 【自愈 1】无障碍被系统关掉就尝试用 root 拉起来。
        // 这是打卡成功率的关键：无障碍一掉，WebView 内容就读不到，
        // 打卡即使成功也会被判失败。
        runCatching {
            val ok = AccessibilityKeeper.ensureEnabled(this)
            if (!ok) {
                Log.w(TAG, "无障碍仍不可用，打卡将只能依赖 root 通道")
            }
        }

        // 【自愈 2】BLE 扫描注册检查（遵循窗外省电停扫规则，仅在打卡窗口内续期活跃）
        runCatching {
            BtTriggerStore.init(this)
            BtTriggerStore.refreshPresence()
            if (BtTriggerStore.enabled.value) {
                com.dingdinggo.app.ble.BtScanner.renewAndReschedule(this)
                Log.i(
                    TAG,
                    "BLE 扫描自检：inWindow=" + com.dingdinggo.app.ble.BtScanner.shouldKeepScanAliveNow(this),
                )
            } else {
                com.dingdinggo.app.ble.BtScanner.stop(this)
            }
        }.onFailure { Log.w(TAG, "BLE 扫描自检异常: " + it.javaClass.simpleName) }

        // 【自愈 3】公司 Wi-Fi 监听与在场复核
        runCatching {
            WifiTriggerStore.init(this)
            WifiTriggerMonitor.sync(this)
            if (WifiTriggerStore.enabled.value &&
                com.dingdinggo.app.ble.BtScanner.shouldKeepScanAliveNow(this)
            ) {
                WifiTriggerMonitor.checkNow(this)
            }
        }.onFailure { Log.w(TAG, "Wi-Fi 监听自检异常: " + it.javaClass.simpleName) }

        if (!AppConfig.enabled.value) {
            Log.i(TAG, "自动打卡已关闭，跳过自检")
            updateNotification()
            return
        }

        // 重新调度一次即可 —— scheduleNext 内部会先取消旧的再注册新的，
        // 所以"闹钟还在"的情况下这一步是幂等的，不会产生重复闹钟。
        val slot = AlarmScheduler.scheduleNext(this)
        Log.i(
            TAG,
            if (slot != null) {
                "自检完成，已确保闹钟存在：下次 " +
                    PunchSchedule.formatTime(slot.targetAtMillis)
            } else {
                "自检完成，但当前无可调度时段"
            },
        )

        updateNotification()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台保活",
            // LOW：不出声、不弹窗，只在通知栏占一行
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持自动打卡在后台存活，并显示运行状态"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 构造常驻通知。
     *
     * 设计要点：
     * - 标题直接说"正在守护"，而不是含糊的"服务运行中"；
     * - 正文显示**下一次打卡时间 + 已运行时长**，让你能自己判断它有没有断过；
     * - 右侧一个"停止"动作，方便你随时关掉；
     * - 点通知打开 App。
     */
    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            2101,
            Intent(this, KeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val showDetails = AppConfig.showPersistent.value

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF7CC4FF.toInt())

        if (showDetails) {
            // 用户开着常驻通知：给完整信息
            builder
                .setContentTitle("钉钉GO 正在守护")
                .setContentText(buildStatusText())
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(buildStatusText()),
                )
                .addAction(0, "停止守护", stopIntent)
        } else {
            // 用户关掉了常驻通知：仍然要有前台通知（服务存活的硬要求），
            // 但把它压到最小：只留一行"后台运行中"，无正文、无操作。
            // 这是"我不想看到它"和"系统要求必须有通知"之间的折中。
            builder
                .setContentTitle("钉钉GO")
                .setContentText("后台运行中")
                .setPriority(NotificationCompat.PRIORITY_MIN)
        }

        return builder.build()
    }

    /** 拼出状态文案，包含下次打卡时间与已运行时长 */
    private fun buildStatusText(): String {
        val now = System.currentTimeMillis()
        val uptimeMin = if (startedAt > 0) (now - startedAt) / 60_000L else 0L

        val nextText = runCatching {
            val slot = PunchSchedule.nextSlot(now, AppConfig.settings.value)
            if (slot == null) {
                "今日无可打卡时段"
            } else {
                val kind = when (slot.kind) {
                    com.dingdinggo.app.data.db.PunchKind.CLOCK_IN -> "上班"
                    com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> "下班"
                    else -> "手动"
                }
                "下次 " + kind + " " + PunchSchedule.formatTime(slot.targetAtMillis)
            }
        }.getOrDefault("状态读取中")

        val uptimeText = when {
            uptimeMin < 60 -> uptimeMin.toString() + " 分钟"
            else -> (uptimeMin / 60).toString() + " 小时 " + (uptimeMin % 60) + " 分"
        }

        return nextText + " · 已守护 " + uptimeText
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }
}