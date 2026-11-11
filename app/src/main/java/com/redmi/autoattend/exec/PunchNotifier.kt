package com.redmi.autoattend.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.redmi.autoattend.MainActivity
import com.redmi.autoattend.R
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.PunchKind

/**
 * 打卡结果通知。
 *
 * 视觉规格（需求 F4）：
 * - 成功：普通优先级，标题"✅ 上班打卡成功"，正文含实际时间；
 * - 失败：高优先级，标题"⚠️ 打卡失败"，正文简述失败环节，带"立即重试"按钮；
 * - 使用自定义 RemoteViews，深色半透明圆角，与 App 视觉一致。
 *
 * 注意：RemoteViews 在 HyperOS 上可能被通知样式覆盖（系统会重绘），
 * 所以这里同时设置了标准的 title/text，保证即使自定义布局被降级，信息也不丢。
 */
object PunchNotifier {

    private const val TAG = "PunchNotifier"

    const val CHANNEL_SUCCESS = "punch_result_success"
    const val CHANNEL_FAILURE = "punch_result_failure"

    private const val ID_SUCCESS = 3001
    private const val ID_FAILURE = 3002

    private const val EXTRA_KIND = "notify_kind"

    /** 确保两个结果通知渠道已创建 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CHANNEL_SUCCESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SUCCESS,
                    "打卡成功提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "打卡成功后的结果提醒"
                    enableVibration(false)
                }
            )
        }

        if (nm.getNotificationChannel(CHANNEL_FAILURE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FAILURE,
                    "打卡失败提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "打卡失败后的高优先级提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                }
            )
        }
    }

    /** 发出结果通知 */
    /**
     * 打卡完成时的触觉 + 声音反馈。
     *
     * 为什么不用通知自带的声音/震动：
     * - 成功用 CHANNEL_SUCCESS（IMPORTANCE_HIGH），系统会自己响；
     *   但用户可能把渠道静音了，那样就没任何反馈。
     * - 这里额外用 Vibrator + ToneGenerator 做一层**代码级**反馈，
     *   不依赖渠道设置，保证"打卡完成"这件事一定能被感知到。
     *
     * 声音用 ToneGenerator 合成，不引入音频文件 ——
     * 少一个资源、少一次解码，而且音色可控。
     */
    private fun playCompletionFeedback(context: Context, success: Boolean) {
        // ---- 震动 ----
        runCatching {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (!vibrator.hasVibrator()) return@runCatching

            val effect = if (success) {
                // 成功：两下轻快的"哒-哒"，像确认感
                android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 45, 70, 45),
                    -1,
                )
            } else {
                // 失败：一下长而低的"嗡"，提示需要处理
                android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 180, 90, 60),
                    -1,
                )
            }
            vibrator.vibrate(effect)
        }

        // ---- 声音 ----
        runCatching {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION,
                70,
            )
            // 成功：两个上行的短音（C6 → E6），听觉上"向上"
            // 失败：两个同高的短音，平直、克制
            val type = if (success) {
                android.media.ToneGenerator.TONE_PROP_ACK
            } else {
                android.media.ToneGenerator.TONE_PROP_NACK
            }
            tone.startTone(type, 160)
            // 200ms 后释放，避免占用音频焦点
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                260,
            )
        }
    }

    fun notifyResult(
        context: Context,
        result: PunchResult,
        kind: PunchKind,
        finishedAt: Long,
    ) {
        ensureChannels(context)

        // 先给触觉和声音反馈，再发通知 ——
        // 通知可能被渠道静音，但震动和音效不会。
        playCompletionFeedback(context, result.success)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val kindLabel = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            PunchKind.MANUAL -> "手动"
        }
        val timeText = PunchSchedule.formatTime(finishedAt)

        if (result.success) {
            // 成功通知的信息层级重排：
            // 标题说"什么事成了"，正文第一行给时间（最重要的信息），
            // 第二行才是通道这类技术细节（大多数人不需要）。
            val title = kindLabel + "打卡成功"

            val notification = buildNotification(
                context = context,
                channelId = CHANNEL_SUCCESS,
                title = title,
                timeText = timeText,
                subtitle = "通道 " + result.channelUsed,
                isSuccess = true,
                kind = kind,
                showRetry = false,
            )
            runCatching { nm.notify(ID_SUCCESS, notification) }
            return
        }

        val title = kindLabel + "打卡失败"

        val notification = buildNotification(
            context = context,
            channelId = CHANNEL_FAILURE,
            title = title,
            timeText = timeText,
            subtitle = result.stage.label + " · " + result.message,
            isSuccess = false,
            kind = kind,
            showRetry = true,
        )
        runCatching { nm.notify(ID_FAILURE, notification) }
    }

    /**
     * 构造结果通知。
     *
     * 用结构化字段（title / timeText / subtitle）而不是单段 body，
     * 因为自定义布局需要分别渲染三行，字号和颜色都不一样。
     */
    private fun buildNotification(
        context: Context,
        channelId: String,
        title: String,
        timeText: String,
        subtitle: String,
        isSuccess: Boolean,
        kind: PunchKind,
        showRetry: Boolean,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        builder
            .setSmallIcon(
                if (isSuccess) {
                    android.R.drawable.checkbox_on_background
                } else {
                    android.R.drawable.stat_notify_error
                }
            )
            .setContentTitle(title)
            .setContentText(timeText + if (subtitle.isNotEmpty()) " · " + subtitle else "")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    timeText + if (subtitle.isNotEmpty()) "\n" + subtitle else ""
                )
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(
                if (isSuccess) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_HIGH
            )
            .setColor(if (isSuccess) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())

        // 失败时附加"立即重试"动作
        if (showRetry) {
            val retryIntent = Intent(context, PunchForegroundService::class.java).apply {
                action = PunchForegroundService.ACTION_EXECUTE
                putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
                putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, System.currentTimeMillis())
                putExtra(EXTRA_KIND, kind.name)
            }
            val retryPi = PendingIntent.getForegroundService(
                context,
                200,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_rotate,
                    "立即重试",
                    retryPi,
                ).build()
            )
        }

        // 尝试挂自定义玻璃样式布局；系统不支持时自动回退到标准样式
        runCatching {
            val custom = RemoteViews(context.packageName, R.layout.notification_punch)
            custom.setTextViewText(R.id.title, title)
            custom.setTextViewText(R.id.time, timeText)

            if (subtitle.isNotEmpty()) {
                custom.setTextViewText(R.id.subtitle, subtitle)
                custom.setViewVisibility(R.id.subtitle, android.view.View.VISIBLE)
            } else {
                custom.setViewVisibility(R.id.subtitle, android.view.View.GONE)
            }

            // 左侧状态条颜色：成功绿 / 失败红
            custom.setInt(
                R.id.status_bar,
                "setBackgroundColor",
                if (isSuccess) 0xFF4ADE80.toInt() else 0xFFF87171.toInt(),
            )

            builder.setCustomContentView(custom)
            builder.setCustomBigContentView(custom)
        }

        return builder.build()
    }

    /**
     * 「服务被系统拒绝启动」的告警。
     *
     * 这条通知的意义：HyperOS 把 FGS 拦掉时，打卡根本不会发生，
     * 而且 App 进程也没起来，没有任何 UI 机会告知用户。
     * 只有通知这一条路径能让用户知道"保活链路断了"。
     */
    fun notifyStartBlocked(context: Context, kind: PunchKind) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val kindLabel = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            PunchKind.MANUAL -> "手动"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_FAILURE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            300,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        builder
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ " + kindLabel + "打卡未能启动")
            .setContentText("系统拒绝了后台启动。请检查 HyperOS 的自启动与省电设置。")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "系统拒绝了后台启动执行服务，本次打卡没有执行。\n" +
                        "请到设置页检查：自启动、电池无限制、后台弹出界面。"
                )
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .setColor(0xFFF87171.toInt())

        runCatching { nm.notify(3003, builder.build()) }
    }

    /** 手动触发一次通知（设置页里的"测试通知"用） */
    fun notifyTest(context: Context, success: Boolean) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, if (success) CHANNEL_SUCCESS else CHANNEL_FAILURE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (success) "✅ 上班打卡成功" else "⚠️ 打卡失败")
            .setContentText("这是一条测试通知")
            .setAutoCancel(true)
        runCatching { nm.notify(if (success) 9001 else 9002, builder.build()) }
    }
}