package com.dingdinggo.app.exec

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dingdinggo.app.core.LateConfirmRule
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.ui.glass.SystemBlur

/**
 * 早上首次触发迟到卡时的系统级确认弹窗（TYPE_APPLICATION_OVERLAY）。
 *
 * - 仅在亮屏且已解锁时直接弹出（锁屏期不强盖，改为通知 + 下次解锁自动补弹）；
 * - 60 秒无操作自动关闭，超时默认「不打迟到卡」（Fail-Closed），用户仍可在通知栏继续操作；
 * - 关闭或退场时立即停止全部计时器并移除窗口，满足后台零消耗红线。
 */
object LateConfirmOverlay {

    private const val TAG = "LateConfirmOverlay"

    private val IOS_EASE_OUT = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var activeContainer: FrameLayout? = null
    private var enterAnimator: AnimatorSet? = null
    private var countdownRunnable: Runnable? = null
    private var displayEpoch: Long = 0L

    fun show(
        context: Context,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
        onConfirm: () -> Unit,
        onDecline: () -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "缺少悬浮窗权限，跳过屏幕弹窗（仍保留高优先级通知）")
            return false
        }
        runOnMain {
            showInternal(
                context = app,
                plannedTargetAt = plannedTargetAt,
                lateSeconds = lateSeconds,
                source = source,
                previewMode = false,
                onConfirm = onConfirm,
                onDecline = onDecline,
            )
        }
        return true
    }

    /** 设置页「调试」分组的安全预览入口，绝不会触发真实打卡或修改状态 */
    fun showPreview(context: Context) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            runOnMain {
                Toast.makeText(
                    app,
                    "需要「显示在其他应用上层」权限才能预览迟到确认弹窗",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        runOnMain {
            showInternal(
                context = app,
                plannedTargetAt = System.currentTimeMillis() - 5 * 60_000L,
                lateSeconds = 5 * 60L,
                source = "preview",
                previewMode = true,
                onConfirm = {
                    Toast.makeText(app, "预览模式：已模拟「确认打迟到卡」", Toast.LENGTH_SHORT).show()
                },
                onDecline = {
                    Toast.makeText(app, "预览模式：已模拟「今天不打」", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    fun hide() {
        runOnMain { hideInternal() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun showInternal(
        context: Context,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
        previewMode: Boolean,
        onConfirm: () -> Unit,
        onDecline: () -> Unit,
    ) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        hideInternal()

        val epoch = ++displayEpoch
        val isDark = isDarkMode(context)

        val sourceLabel = when (source) {
            PunchForegroundService.SOURCE_WIFI -> "公司 Wi-Fi"
            PunchForegroundService.SOURCE_BLUETOOTH -> "蓝牙考勤机"
            "preview" -> "设置页预览"
            else -> "定时上班闹钟"
        }
        val lateText = PunchSchedule.formatRemainSeconds(lateSeconds)
        val targetText = PunchSchedule.formatMinute(
            java.util.Calendar.getInstance().apply { timeInMillis = plannedTargetAt }.let {
                it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
            },
        )

        val scrimContainer = FrameLayout(context).apply {
            setBackgroundColor(if (isDark) 0x56040914 else 0x240A1424)
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
        }

        val cardBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (isDark) {
                intArrayOf(0xEB13223C.toInt(), 0xEB0B1628.toInt())
            } else {
                intArrayOf(0xF5F7FBFF.toInt(), 0xEDEAF3FE.toInt())
            },
        ).apply {
            cornerRadius = dp(context, 24).toFloat()
            setStroke(
                dp(context, 1),
                if (isDark) 0x4592B8FF else 0xB8FFFFFF.toInt(),
            )
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(context, 18).toFloat()
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 18))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isDark) 0x99020612.toInt() else 0x44234578
                outlineSpotShadowColor = if (isDark) 0xB3030818.toInt() else 0x55234578
            }
            alpha = 0f
            scaleX = 0.93f
            scaleY = 0.93f
        }

        val titleView = TextView(context).apply {
            text = "⏰ 上班已迟到，是否打迟到卡？"
            textSize = 17f
            setTextColor(if (isDark) 0xFFF4F7FC.toInt() else 0xFF142136.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val detailView = TextView(context).apply {
            text = "计划上班时间 $targetText，已超过 $lateText（来源：$sourceLabel）。\n" +
                "为防止误打迟到卡，默认不会自动打卡；确认后将立即打开钉钉补打。"
            textSize = 13.5f
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setTextColor(if (isDark) 0xFFB8CAE2.toInt() else 0xFF425775.toInt())
        }

        val countdownView = TextView(context).apply {
            textSize = 11.5f
            setTextColor(if (isDark) 0xFFFFB340.toInt() else 0xFFC77200.toInt())
        }

        val declineBtn = makeActionPill(
            context = context,
            label = "今天不打",
            bgColor = if (isDark) 0x1FFFFFFF else 0xB8FFFFFF.toInt(),
            strokeColor = if (isDark) 0x2BFFFFFF else 0x242B3A4A,
            textColor = if (isDark) 0xFFD4E0F0.toInt() else 0xFF3E526D.toInt(),
            bold = false,
        ) { v ->
            runCatching { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            hideInternal()
            onDecline()
        }

        val confirmBtn = makeActionPill(
            context = context,
            label = "确认打迟到卡",
            bgColor = if (isDark) 0x3DFFA00A else 0x29FF9F0A,
            strokeColor = if (isDark) 0x70FFA00A else 0x5EFF9F0A,
            textColor = if (isDark) 0xFFFFBA49.toInt() else 0xFFC56A00.toInt(),
            bold = true,
        ) { v ->
            runCatching { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            hideInternal()
            onConfirm()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                declineBtn,
                LinearLayout.LayoutParams(0, dp(context, 44), 1f),
            )
            addView(View(context), LinearLayout.LayoutParams(dp(context, 10), 1))
            addView(
                confirmBtn,
                LinearLayout.LayoutParams(0, dp(context, 44), 1f),
            )
        }

        card.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            detailView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 10) },
        )
        card.addView(
            countdownView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 10) },
        )
        card.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 18) },
        )

        val maxCardW = minOf(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            dp(context, 380),
        )
        scrimContainer.addView(
            card,
            FrameLayout.LayoutParams(
                maxCardW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SystemBlur.isSupported) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = dp(context, 20)
            }
        }

        runCatching {
            manager.addView(scrimContainer, params)
            activeContainer = scrimContainer

            val anim = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(card, View.SCALE_X, 0.93f, 1f),
                    ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.93f, 1f),
                )
                duration = 260L
                interpolator = IOS_EASE_OUT
            }
            enterAnimator = anim
            anim.start()

            val totalSec = (LateConfirmRule.OVERLAY_AUTO_DISMISS_MS / 1000L).toInt().coerceAtLeast(1)
            startCountdown(epoch, totalSec, previewMode, countdownView)
            Log.i(TAG, "已展示迟到确认弹窗 preview=" + previewMode)
        }.onFailure { e ->
            activeContainer = null
            Log.w(TAG, "展示迟到确认弹窗失败: " + e.javaClass.simpleName + " " + e.message)
        }
    }

    private fun startCountdown(
        epoch: Long,
        remainSec: Int,
        previewMode: Boolean,
        countdownView: TextView,
    ) {
        if (displayEpoch != epoch || activeContainer == null) return
        if (remainSec <= 0) {
            Log.i(TAG, "迟到确认弹窗 60 秒超时未操作，自动关闭（默认不打）")
            hideInternal()
            return
        }
        countdownView.text = if (previewMode) {
            "${remainSec} 秒后自动关闭（预览不会执行任何打卡）"
        } else {
            "${remainSec} 秒后自动关闭（默认不打），可在通知栏继续操作"
        }
        val next = Runnable {
            startCountdown(epoch, remainSec - 1, previewMode, countdownView)
        }
        countdownRunnable = next
        mainHandler.postDelayed(next, 1_000L)
    }

    private fun hideInternal() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        val container = activeContainer ?: return
        activeContainer = null
        runCatching { wm?.removeViewImmediate(container) }
            .onFailure { runCatching { wm?.removeView(container) } }
    }

    private fun makeActionPill(
        context: Context,
        label: String,
        bgColor: Int,
        strokeColor: Int,
        textColor: Int,
        bold: Boolean,
        onClick: (View) -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999).toFloat()
            setColor(bgColor)
            setStroke(dp(context, 1), strokeColor)
        }
        setOnClickListener(onClick)
    }

    private fun isDarkMode(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
