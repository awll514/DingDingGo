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
import androidx.core.app.NotificationCompat
import com.redmi.autoattend.MainActivity
import com.redmi.autoattend.R
import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.PunchSchedule
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

        const val ACTION_START = "com.redmi.autoattend.action.KEEPALIVE_START"
        const val ACTION_STOP = "com.redmi.autoattend.action.KEEPALIVE_STOP"
        const val ACTION_REFRESH = "com.redmi.autoattend.action.KEEPALIVE_REFRESH"

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

        // 启动时就把无障碍准备好。
        //
        // 【启动性能】onStartCommand 跑在主线程，而 ensureEnabled 内部会
        // fork su + 轮询等待系统重新 bind 无障碍，最长接近 2 秒。
        // 这里是**保活服务**，晚几百毫秒做这件事完全无影响，
        // 但直接在主线程做就会阻塞任何正在进行的界面绘制。
        // 所以放到 scope（Dispatchers.Default）里。
        scope.launch {
            runCatching { AccessibilityKeeper.ensureEnabled(this@KeepAliveService) }
        }

        startSelfCheckLoop()

        // START_STICKY：被系统杀掉后自动重启（配合 Manifest 的 specialUse 类型）
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    // ---------- 自检循环 ----------

    private fun startSelfCheckLoop() {
        scope.launch {
            while (isActive) {
                delay(SELF_CHECK_INTERVAL_MS)
                runCatching { selfCheck() }
                    .onFailure { Log.w(TAG, "自检异常: " + it.javaClass.simpleName) }
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

        // 【自愈 2】灵动岛白名单（幂等）

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
                    com.redmi.autoattend.data.db.PunchKind.CLOCK_IN -> "上班"
                    com.redmi.autoattend.data.db.PunchKind.CLOCK_OUT -> "下班"
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