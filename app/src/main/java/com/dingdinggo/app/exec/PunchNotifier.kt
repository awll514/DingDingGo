package com.dingdinggo.app.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dingdinggo.app.MainActivity
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchKind

/**
 * 打卡结果通知。
 *
 * 视觉规格：
 * - 统一走系统原生通知样式（Notification.BigTextStyle），跟随 HyperOS 3 浅色/深色系统外观；
 * - 成功：普通优先级，标题"✅ 上班打卡成功"，正文含实际时间；
 * - 失败：高优先级，标题"⚠️ 上班/下班打卡失败"，正文简述失败环节，带原生"立即重试"按钮。
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
        actualTimeText: String? = null,
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
        val timeText = actualTimeText?.takeIf { it.isNotBlank() }
            ?: PunchSchedule.formatTime(finishedAt)

        if (result.success) {
            val title = "✅ " + kindLabel + "打卡成功"

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
                .onSuccess { android.util.Log.i(TAG, "已发送成功通知 id=" + ID_SUCCESS + " title=" + title) }
                .onFailure { android.util.Log.e(TAG, "发送成功通知失败", it) }
            return
        }

        val title = "⚠️ " + kindLabel + "打卡失败"

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
            .onSuccess { android.util.Log.i(TAG, "已发送失败通知 id=" + ID_FAILURE + " title=" + title) }
            .onFailure { android.util.Log.e(TAG, "发送失败通知失败", it) }
    }

    /** 蓝牙触发相关的通知 id（与打卡结果区分开，互不覆盖） */
    private const val ID_BT_SKIPPED = 3004

    /** 早上首次迟到确认通知 id */
    private const val ID_LATE_CONFIRM = 3005

    /** 迟到确认提示音与震动反馈（独立于通知渠道，避免渠道静音导致漏提醒） */
    private fun playLateConfirmFeedback(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 120, 80, 120, 80, 160),
                        -1,
                    ),
                )
            }
        }
        runCatching {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION,
                75,
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 220)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                320,
            )
        }
    }

    /** 发出早上首次触发迟到卡的高优先级确认通知 */
    fun notifyLateConfirm(
        context: Context,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
    ) {
        ensureChannels(context)
        playLateConfirmFeedback(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sourceLabel = when (source) {
            PunchForegroundService.SOURCE_WIFI -> "公司 Wi-Fi"
            PunchForegroundService.SOURCE_BLUETOOTH -> "蓝牙考勤机"
            else -> "定时上班闹钟"
        }
        val lateText = PunchSchedule.formatRemainSeconds(lateSeconds)
        val body = "已超过计划上班时间 $lateText（$sourceLabel）。默认不会自动打迟到卡，请确认。"

        val contentIntent = PendingIntent.getActivity(
            context,
            400,
            Intent(context, MainActivity::class.java).apply {
                putExtra(LateConfirmCoordinator.EXTRA_OPEN_LATE_CONFIRM, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val confirmPi = PendingIntent.getBroadcast(
            context,
            401,
            Intent(context, LateConfirmReceiver::class.java).apply {
                action = LateConfirmReceiver.ACTION_CONFIRM_LATE
                putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, plannedTargetAt)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declinePi = PendingIntent.getBroadcast(
            context,
            402,
            Intent(context, LateConfirmReceiver::class.java).apply {
                action = LateConfirmReceiver.ACTION_DECLINE_LATE
                putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, plannedTargetAt)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_FAILURE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⏰ 上班已迟到，是否继续打卡？")
            .setContentText(body)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    body + "\n\n点击「确认打迟到卡」将立即为您打卡；点「今天不打」则跳过今日上班打卡。",
                ),
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .setColor(0xFFFF9F0A.toInt())
            .addAction(
                Notification.Action.Builder(null, "确认打迟到卡", confirmPi).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "今天不打", declinePi).build(),
            )

        runCatching { nm.notify(ID_LATE_CONFIRM, builder.build()) }
            .onSuccess { android.util.Log.i(TAG, "已发送早上首次迟到确认通知 late=" + lateText) }
            .onFailure { android.util.Log.e(TAG, "发送迟到确认通知失败", it) }
    }

    /** 取消早上迟到确认通知 */
    fun cancelLateConfirm(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ID_LATE_CONFIRM)
        }
    }

    /**
     * 蓝牙触发"因安全阀主动放弃"时的通知。
     *
     * ## 为什么这一条必须发
     *
     * 用户在设置里明确要求「不打迟到卡不打早退卡」。于是一个已经在
     * 迟到边界之后到公司的人，蓝牙检测到了、但我们**不会**替他打卡。
     * 如果这时保持沉默，他会以为"自动打卡成功了"，直到发薪日才发现
     * 那天缺卡 —— 这比打一张迟到卡还糟。
     *
     * 所以这条通知的定位是**兜底提醒**：明确告诉他"今天没自动打，
     * 请手动处理"，而不是"打卡失败"（那会让人以为系统坏了）。
     *
     * @param reason 具体原因（来自 BtPunchRule.Decision.message）
     */
    /**
     * 同一天只提醒一次的版本。
     *
     * ## 为什么必须去重
     *
     * `SKIP_TOO_LATE` 会在"已过迟到边界 + 人还站在考勤机旁"期间
     * **每 60 秒复现一次**。如果每次都发一条 IMPORTANCE_HIGH
     * （带震动）的通知，用户一整天在工位旁边会被持续打扰 ——
     * 而这条通知的信息量始终是同一句"今天没自动打上班卡"。
     *
     * 所以按天去重：今天已经提醒过就不再提醒。
     * 第二天（新的日期键）自然重新生效。
     */
    fun notifyBtSkippedOnceADay(context: Context, reason: String, kind: PunchKind) {
        val today = PunchSchedule.dateKey(System.currentTimeMillis())
        val prefs = context.getSharedPreferences("autoattend_prefs", Context.MODE_PRIVATE)
        val key = "bt_late_notified_day_" + kind.name
        if (prefs.getInt(key, 0) == today) {
            return
        }
        prefs.edit().putInt(key, today).apply()
        notifyBtSkipped(context, reason, kind)
    }

    fun notifyBtSkipped(context: Context, reason: String, kind: PunchKind) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val kindLabel = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            PunchKind.MANUAL -> "打卡"
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 重试按钮：走正常执行链路，由引擎自己的闸门再判一次。
        // 注意这里**刻意**不用 now 当 targetAt —— 与失败通知的同款坑，
        // 传今天配置的计划时刻，让闸门按真实规则判定。
        val settings = com.dingdinggo.app.core.AppConfig.settings.value
        val retryIntent = Intent(context, PunchForegroundService::class.java).apply {
            action = PunchForegroundService.ACTION_EXECUTE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
            putExtra(
                PunchAlarmReceiver.EXTRA_TARGET_AT,
                // 与失败通知共用同一实现 —— 这个坑出现过一次，不能再有第二份
                plannedTargetAt(System.currentTimeMillis(), kind, settings),
            )
            putExtra(PunchForegroundService.EXTRA_SOURCE, PunchForegroundService.SOURCE_MANUAL)
        }
        // 【P1】这个按钮用 getService 启动一个**必须 startForeground** 的服务。
        // 与失败通知处（getForegroundService）保持一致，避免后台服务启动被拦
        // 导致"点了按钮没有任何反应"。
        val retryPi = PendingIntent.getForegroundService(
            context,
            102,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_FAILURE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⏰ 检测到考勤机，但今天「" + kindLabel + "」未自动打卡")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason + "\n\n如仍需打卡，请手动处理。"))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .setColor(0xFFFF9F0A.toInt())
            .addAction(
                Notification.Action.Builder(null, "手动打卡", retryPi).build(),
            )

        runCatching { nm.notify(ID_BT_SKIPPED, builder.build()) }
    }

    /**
     * 【P0-2 的唯一实现，纯函数】某个班次在「今天」的计划时刻。
     *
     * ## 为什么必须是个独立函数
     *
     * 失败通知的「立即重试」按钮曾把 targetAt 写成 `System.currentTimeMillis()`，
     * 直接打穿安全阀（详见调用处的长注释）。而安全闸门的判定基准就是 targetAt。
     *
     * 这个 bug 的可怕之处在于**修好之后也一样脆**：只要有人图省事再写一次
     * `System.currentTimeMillis()`，红线立刻失效，而没有任何测试会发现。
     * 所以把它固化成唯一实现，并用
     * [com.dingdinggo.app.RetryTargetTest] 钉住"结果必须是计划时刻、
     * 且与传入的 now 无关"。
     *
     * @param nowMillis 当前时刻（注入，仅用于确定"今天"，不参与目标时刻计算）
     * @param kind 班次
     * @param settings 排班设置
     * @return 今天该班次的计划时刻（毫秒时间戳）
     */
    internal fun plannedTargetAt(
        nowMillis: Long,
        kind: PunchKind,
        settings: com.dingdinggo.app.core.ScheduleSettings,
    ): Long {
        val dayStart = PunchSchedule.startOfDay(nowMillis)
        val minute = when (kind) {
            PunchKind.CLOCK_OUT -> settings.clockOutMinute
            else -> settings.clockInMinute
        }
        return dayStart + minute * 60_000L
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

        val compactText = when {
            isSuccess && subtitle.isNotEmpty() -> "实际时间：$timeText · $subtitle"
            isSuccess -> "实际时间：$timeText"
            subtitle.isNotEmpty() -> subtitle
            else -> "时间：$timeText"
        }
        val expandedText = when {
            isSuccess && subtitle.isNotEmpty() -> "实际时间：$timeText\n$subtitle"
            isSuccess -> "实际时间：$timeText"
            subtitle.isNotEmpty() -> "时间：$timeText\n$subtitle"
            else -> "时间：$timeText"
        }

        builder
            .setSmallIcon(
                if (isSuccess) {
                    android.R.drawable.checkbox_on_background
                } else {
                    android.R.drawable.stat_notify_error
                }
            )
            .setContentTitle(title)
            .setContentText(compactText)
            .setStyle(Notification.BigTextStyle().bigText(expandedText))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(
                if (isSuccess) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_HIGH
            )
            .setColor(if (isSuccess) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())

        // 失败时附加"立即重试"动作
        if (showRetry) {
            // targetAt 必须是**该班次的计划时刻**，绝不能是 now。
            //
            // 安全闸门的判定基准就是 targetAt：
            //   · 上班卡上界 clockInMustStopAt = target + 55s
            //   · 下班卡下界 clockOutEarliestAt = target + 2s（且**没有上界**）
            //
            // 一旦把 targetAt 写成 now，两处闸门都退化成"相对当前时刻"：
            //   · 刚被正确拒绝的迟到卡 → 点一次重试就必然放行 → 打出一张
            //     真实的迟到记录；
            //   · 下班卡更严重：下界变成 now+2s 秒过，而上界不存在，
            //     于是**任何时刻**点重试都能打出一张当日下班卡 → 早退/错卡。
            //
            // 同一文件的 notifyBtSkipped() 里已经用正确写法并写了注释
            // "刻意不用 now"，这里当时漏改了。现在与它保持完全一致。
            val retrySettings = com.dingdinggo.app.core.AppConfig.settings.value
            val retryIntent = Intent(context, PunchForegroundService::class.java).apply {
                action = PunchForegroundService.ACTION_EXECUTE
                putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
                putExtra(
                    PunchAlarmReceiver.EXTRA_TARGET_AT,
                    plannedTargetAt(System.currentTimeMillis(), kind, retrySettings),
                )
                putExtra(EXTRA_KIND, kind.name)
                // 明确标记为手动来源，与其它手动入口语义一致
                putExtra(PunchForegroundService.EXTRA_SOURCE, PunchForegroundService.SOURCE_MANUAL)
            }
            // 【P1 修复】requestCode 必须按班次派生。
            //
            // Android 匹配 PendingIntent 时**不比较 extras**，只看
            // (requestCode, Intent 的 action/data/component/type)。
            // 原先固定 200 且两种班次共用，于是上班失败与下班失败的通知
            // 指向的是**同一个 PI 记录**，后构建的会覆盖前者的 extras ——
            // 点"上班失败"的重试按钮可能以 CLOCK_OUT 执行 → 打错班次。
            val retryRequestCode = 200 + kind.ordinal
            val retryPi = PendingIntent.getForegroundService(
                context,
                retryRequestCode,
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

        // 成功 / 失败等全部打卡通知统一使用系统原生样式（BigTextStyle），不再挂载深色自定义 RemoteViews 卡片
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
}