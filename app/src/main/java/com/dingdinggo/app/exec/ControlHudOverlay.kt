package com.dingdinggo.app.exec

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.dingdinggo.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

enum class ControlHudTone { RUNNING, SUCCESS, FAILURE, LATE, PAUSED }

data class AnimeHudCopy(
    val title: String,
    val subtitle: String,
)

/** 纯 Kotlin 钉钉娘悬浮窗二次元文案格式化器，无 Android 视图依赖，可独立单测。 */
object ControlHudCopyFormatter {

    fun formatInteractiveCopy(
        paused: Boolean,
        rawTitle: String,
        rawSubtitle: String,
    ): AnimeHudCopy {
        if (paused) {
            return AnimeHudCopy(
                title = "☕ 摸鱼暂停中~",
                subtitle = "点「继续」复工，或点「取消」收工",
            )
        }
        val title = when {
            rawTitle.contains("预览") -> "✨ 钉钉娘上线啦~"
            rawTitle.contains("恢复") || rawTitle.contains("继续") -> "✨ 元气恢复，继续打卡！"
            rawTitle.contains("接管") || rawTitle.contains("自动") -> "✨ 钉钉娘行动中~"
            else -> rawTitle
        }
        val subtitle = when {
            rawSubtitle.contains("可左右拖拽") -> "点我收起/展开 · 7秒后退场"
            rawSubtitle.contains("正在操作界面") -> "正在帮你赶往考勤页…"
            rawSubtitle.contains("恢复") -> "重新连上打卡流程啦~"
            else -> rawSubtitle
        }
        return AnimeHudCopy(title = title, subtitle = subtitle)
    }

    fun formatStatusCopy(
        tone: ControlHudTone,
        rawTitle: String,
        rawSubtitle: String,
        clockInMinute: Int? = null,
    ): AnimeHudCopy {
        val cleanTitle = rawTitle
            .removePrefix("✅ ")
            .removePrefix("⚠️ ")
            .removePrefix("⏰ ")
            .removePrefix("⏭️ ")
        val title = when (tone) {
            ControlHudTone.SUCCESS -> when {
                cleanTitle.contains("上班") -> {
                    val timeLabel = clockInMinute
                        ?.takeIf { it in 0 until 24 * 60 }
                        ?.let { "%02d:%02d ".format(it / 60, it % 60) }
                        .orEmpty()
                    "✌️ " + timeLabel + "上班元气打卡搞定~"
                }
                cleanTitle.contains("下班") -> "🌙 辛苦啦！下班打卡完成~"
                else -> "✌️ 打卡任务完成啦~"
            }
            ControlHudTone.LATE -> when {
                cleanTitle.contains("跳过") -> "☁️ 本次班次先跳过休息~"
                else -> "⏰ 惊险压哨打上啦~"
            }
            ControlHudTone.FAILURE -> "💦 呜…打卡好像卡住了"
            ControlHudTone.PAUSED -> "☕ 摸鱼暂停中~"
            ControlHudTone.RUNNING -> "✨ 钉钉娘行动中~"
        }
        val subtitle = when (tone) {
            ControlHudTone.SUCCESS -> if (rawSubtitle.isNotBlank()) "真实考勤时间：$rawSubtitle" else "本时段已经稳稳记录下来啦"
            ControlHudTone.LATE -> if (rawSubtitle.isNotBlank()) "节点回执：$rawSubtitle" else "已按策略收尾，记得看一眼详情"
            ControlHudTone.FAILURE -> if (rawSubtitle.isNotBlank()) rawSubtitle else "点通知即可立即重试一次~"
            else -> rawSubtitle
        }
        return AnimeHudCopy(title = title, subtitle = subtitle)
    }
}

/**
 * 钉钉娘系统级液态玻璃悬浮窗。
 *
 * - 默认展开「钉钉娘头像 + 二次元气泡对话框 + 暂停/取消控制钮」；
 * - 点击钉钉娘可在展开/收起间丝滑切换，气泡自带指向头像侧的对话尖角；
 * - 收起后仅保留可拖拽小头像，运行/暂停状态通过头像晴空光晕、像素星芒闪烁与轻柔夜幕色调融入本身，无突兀红点；
 * - 支持任意拖拽与贴边磁吸回弹，停靠左/右侧时自动镜像对话尖角与展开方向；
 * - 退场或隐藏时立刻注销全部动画与 WindowManager 视图，保持后台零渲染消耗。
 */
object ControlHudOverlay {

    private const val TAG = "ControlHud"

    private const val EDGE_MARGIN_DP = 12
    private const val MASCOT_SLOT_DP = 72

    private val IOS_EASE_OUT = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val SPRING_POP = PathInterpolator(0.22f, 1.16f, 0.36f, 1f)
    private val ACCEL_EXIT = AccelerateInterpolator(1.25f)

    typealias Tone = ControlHudTone

    private class HudRefs(
        val root: HudRootLayout,
        val params: WindowManager.LayoutParams,
        val mascotView: MascotAvatarView,
        val dialogColumn: LinearLayout,
        val bubbleBox: LinearLayout,
        val bubbleBg: SpeechBubbleDrawable,
        val titleView: TextView,
        val subtitleView: TextView,
        val bubbleHost: HudRootLayout? = null,
        val bubbleParams: WindowManager.LayoutParams? = null,
        var mascotSpacer: View? = null,
        var buttonRow: LinearLayout? = null,
        var toggleButton: TextView? = null,
        var cancelButton: TextView? = null,
        var expanded: Boolean = true,
        var interactive: Boolean = true,
        var activeTitle: String = "",
        var activeSubtitle: String = "",
    )

    private var activeRefs: HudRefs? = null
    private var wm: WindowManager? = null
    private var haloAnim: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null
    private var expandAnimator: AnimatorSet? = null
    private var dockedRight: Boolean = true
    private var rememberedY: Int? = null
    private var displayEpoch: Long = 0L

    @Volatile
    private var isPaused = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    /** 最近一次显示失败原因；空字符串表示显示正常 */
    val lastFailureReason = MutableStateFlow("")

    val isShowing: Boolean get() = activeRefs != null

    /** 当前悬浮窗是否处于暂停态（主流程取消时据此决定保留暂停态悬浮窗） */
    fun isPausedState(): Boolean = isPaused

    /** 把底层执行/结果文案映射成贴合「钉钉娘」人设的轻二次元气泡对白 */
    internal fun formatInteractiveCopy(
        paused: Boolean,
        rawTitle: String,
        rawSubtitle: String,
    ): AnimeHudCopy = ControlHudCopyFormatter.formatInteractiveCopy(paused, rawTitle, rawSubtitle)

    internal fun formatStatusCopy(
        tone: Tone,
        rawTitle: String,
        rawSubtitle: String,
        clockInMinute: Int? = null,
    ): AnimeHudCopy = ControlHudCopyFormatter.formatStatusCopy(tone, rawTitle, rawSubtitle, clockInMinute)

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = context.applicationContext
        runOnMain { showInternal(app, title, subtitle, onPause, onResume, onCancel) }
    }

    /** 暂停状态变化后刷新气泡文案、按钮样式与头像氛围 */
    fun setPaused(context: Context, paused: Boolean, title: String, subtitle: String) {
        isPaused = paused
        runOnMain {
            isPaused = paused
            val refs = activeRefs ?: return@runOnMain
            refs.activeTitle = title
            refs.activeSubtitle = subtitle
            val copy = formatInteractiveCopy(paused, title, subtitle)
            refs.titleView.text = copy.title
            refs.subtitleView.text = copy.subtitle
            updateToggleButtonLook(context, refs.toggleButton, paused)
            val tone = if (paused) Tone.PAUSED else Tone.RUNNING
            refs.mascotView.setTone(tone)
            refs.bubbleBg.setToneTint(tone, isDarkMode(context))
            refs.bubbleBox.requestLayout()
            refs.bubbleHost?.let { host ->
                host.requestLayout()
                if (host.isAttachedToWindow) {
                    refs.bubbleParams?.let { bp -> runCatching { wm?.updateViewLayout(host, bp) } }
                }
            } ?: refs.root.requestLayout()
            startMascotHaloIfNeeded(refs.mascotView, tone)
            playMicroBounce(refs.bubbleHost ?: refs.root)
        }
    }

    /** 显示结果气泡悬浮窗，默认停留 3 秒后丝滑退场 */
    fun showStatus(
        context: Context,
        tone: Tone,
        title: String,
        subtitle: String,
        holdMs: Long = 3000L,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = context.applicationContext
        runOnMain {
            showStatusInternal(app, tone, title, subtitle)
            cancelPendingDismiss()
            val epoch = displayEpoch
            val dismiss = Runnable { fadeOutAndRemove(epoch) }
            dismissRunnable = dismiss
            mainHandler.postDelayed(dismiss, holdMs)
        }
    }

    /** 【自检】依次预览成功 / 失败 / 迟到状态气泡 */
    fun testStatus(context: Context) {
        showStatus(context, Tone.SUCCESS, "上班打卡成功", "09:30:12")
        mainHandler.postDelayed({
            showStatus(context, Tone.FAILURE, "打卡失败", "点击 · 未找到打卡按钮")
        }, 3600L)
        mainHandler.postDelayed({
            showStatus(context, Tone.LATE, "上班打卡成功（迟到）", "09:41:08")
        }, 7200L)
    }

    /** 【预览入口】展示 7 秒可点击收起/展开、可拖拽的钉钉娘气泡悬浮窗 */
    fun showForTest(
        context: Context,
        holdMs: Long = 7_000L,
    ) {
        show(
            context = context,
            title = "屏幕接管提示 · 预览",
            subtitle = "可左右拖拽贴边 · 点击钉钉娘收起/展开",
            onPause = {
                setPaused(
                    context = context,
                    paused = true,
                    title = "屏幕接管提示 · 预览",
                    subtitle = "可左右拖拽贴边 · 点击钉钉娘收起/展开",
                )
            },
            onResume = {
                setPaused(
                    context = context,
                    paused = false,
                    title = "屏幕接管提示 · 预览",
                    subtitle = "可左右拖拽贴边 · 点击钉钉娘收起/展开",
                )
            },
            onCancel = { hide() },
        )
        runOnMain {
            cancelPendingDismiss()
            val epoch = displayEpoch
            val dismiss = Runnable { fadeOutAndRemove(epoch) }
            dismissRunnable = dismiss
            mainHandler.postDelayed(dismiss, holdMs)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal(
        context: Context,
        title: String,
        subtitle: String,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (!Settings.canDrawOverlays(context)) {
            lastFailureReason.value = "缺少「显示在其他应用上层」权限，钉钉娘悬浮窗无法显示"
            Log.w(
                TAG,
                "没有悬浮窗权限（canDrawOverlays=false），悬浮窗不会显示。" +
                    "请到系统设置 → 应用 → 钉钉GO → 显示在其他应用的上层 打开权限。",
            )
            return
        }

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        cancelPendingDismiss()
        isPaused = false
        val epoch = ++displayEpoch
        val isDark = isDarkMode(context)

        val mascotHost = HudRootLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6))
        }

        val mascotView = MascotAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 66), dp(context, 66))
            setTone(Tone.RUNNING)
        }
        mascotHost.addView(mascotView)

        val copy = formatInteractiveCopy(paused = false, rawTitle = title, rawSubtitle = subtitle)
        val bubbleMaxWidth = (context.resources.displayMetrics.widthPixels * 0.62f).toInt()
        val bubbleBg = SpeechBubbleDrawable(
            context = context,
            isDark = isDark,
            tailOnRight = dockedRight,
            tone = Tone.RUNNING,
        )
        val wrapLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val bubbleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            background = bubbleBg
            elevation = dp(context, 10).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isDark) 0x80030814.toInt() else 0x3D234578
                outlineSpotShadowColor = if (isDark) 0x99040A1A.toInt() else 0x4E234578
            }
            updateBubblePadding(context, this, tailOnRight = dockedRight)
        }

        val titleView = TextView(context).apply {
            text = copy.title
            textSize = 12.5f
            setTextColor(if (isDark) 0xFFF4F7FC.toInt() else 0xFF152238.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.01f
            maxLines = 1
            maxWidth = bubbleMaxWidth
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(context).apply {
            text = copy.subtitle
            textSize = 10.5f
            setTextColor(if (isDark) 0xFFA6B9D4.toInt() else 0xFF4D6280.toInt())
            maxLines = 2
            maxWidth = bubbleMaxWidth
            ellipsize = TextUtils.TruncateAt.END
        }
        bubbleBox.addView(titleView, LinearLayout.LayoutParams(wrapLp))
        bubbleBox.addView(subtitleView, LinearLayout.LayoutParams(wrapLp).apply { topMargin = dp(context, 1) })

        val toggleButton = makeControlButton(
            context = context,
            label = "⏸ 暂停",
            accentArgb = 0xFFFF9F0A.toInt(),
            isDark = isDark,
        ) {
            isPaused = !isPaused
            if (isPaused) onPause() else onResume()
        }
        val cancelButton = makeControlButton(
            context = context,
            label = "✕ 取消",
            accentArgb = 0xFFFF453A.toInt(),
            isDark = isDark,
        ) {
            isPaused = false
            onCancel()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (dockedRight) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 7)
                if (dockedRight) marginEnd = dp(context, 7) else marginStart = dp(context, 7)
            }
            addView(toggleButton)
            addView(spacer(context, 8))
            addView(cancelButton)
        }

        val dialogColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (dockedRight) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            clipChildren = false
            clipToPadding = false
            addView(bubbleBox)
            addView(buttonRow)
        }

        val bubbleHost = HudRootLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6))
            addView(dialogColumn)
        }

        val edgeMargin = dp(context, EDGE_MARGIN_DP)
        val defaultY = clampY(context, rememberedY ?: (statusBarHeight(context) + dp(context, 48)), dp(context, 110))
        val mascotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (dockedRight) Gravity.END else Gravity.START)
            x = edgeMargin
            y = defaultY
        }

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (dockedRight) Gravity.END else Gravity.START)
            x = edgeMargin + dp(context, MASCOT_SLOT_DP)
            y = defaultY
        }

        val refs = HudRefs(
            root = mascotHost,
            params = mascotParams,
            mascotView = mascotView,
            dialogColumn = dialogColumn,
            bubbleBox = bubbleBox,
            bubbleBg = bubbleBg,
            titleView = titleView,
            subtitleView = subtitleView,
            bubbleHost = bubbleHost,
            bubbleParams = bubbleParams,
            buttonRow = buttonRow,
            toggleButton = toggleButton,
            cancelButton = cancelButton,
            expanded = true,
            interactive = true,
            activeTitle = title,
            activeSubtitle = subtitle,
        )

        mascotView.setOnClickListener { v ->
            runCatching { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            toggleBubbleExpanded(context, manager, refs)
        }

        arrangeChildren(context, refs, dockRight = dockedRight)
        attachDragAndMagneticSnap(context, manager, refs)

        detachActiveViewSafely(manager)

        runCatching {
            // 双独立窗口：钉钉娘头像固定锚定在 mascotHost 中，展开/收起只增删 bubbleHost，彻底杜绝瞬移
            manager.addView(mascotHost, mascotParams)
            manager.addView(bubbleHost, bubbleParams)
            activeRefs = refs
            lastFailureReason.value = ""
            startMascotHaloIfNeeded(mascotView, Tone.RUNNING)
            playEntranceAnimation(context, mascotHost, epoch)
            playEntranceAnimation(context, bubbleHost, epoch)
            Log.i(TAG, "钉钉娘悬浮窗已显示（双窗防瞬移模式）")
        }.onFailure { e ->
            removeViewSafely(manager, bubbleHost)
            removeViewSafely(manager, mascotHost)
            activeRefs = null
            lastFailureReason.value = "钉钉娘悬浮窗显示失败：" + (e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "显示悬浮窗失败: " + e.javaClass.simpleName + " " + e.message)
        }
    }

    private fun showStatusInternal(
        context: Context,
        tone: Tone,
        title: String,
        subtitle: String,
    ) {
        if (!Settings.canDrawOverlays(context)) {
            lastFailureReason.value = "缺少悬浮窗权限，状态提示无法显示"
            return
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        cancelPendingDismiss()
        isPaused = false
        val epoch = ++displayEpoch
        val isDark = isDarkMode(context)

        val root = HudRootLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6))
        }

        val mascotView = MascotAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 58), dp(context, 58))
            setTone(tone)
        }

        val clockInMinute = runCatching {
            com.dingdinggo.app.core.AppConfig.settings.value.clockInMinute
        }.getOrNull()
        val copy = formatStatusCopy(tone, title, subtitle, clockInMinute)
        val bubbleMaxWidth = (context.resources.displayMetrics.widthPixels * 0.64f).toInt()
        val bubbleBg = SpeechBubbleDrawable(
            context = context,
            isDark = isDark,
            tailOnRight = false,
            tone = tone,
        )
        val wrapLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val bubbleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(wrapLp)
            background = bubbleBg
            elevation = dp(context, 10).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isDark) 0x80030814.toInt() else 0x3D234578
                outlineSpotShadowColor = if (isDark) 0x99040A1A.toInt() else 0x4E234578
            }
            updateBubblePadding(context, this, tailOnRight = false)
        }

        val titleView = TextView(context).apply {
            text = copy.title
            textSize = 12.5f
            setTextColor(if (isDark) 0xFFF4F7FC.toInt() else 0xFF152238.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.01f
            maxLines = 1
            maxWidth = bubbleMaxWidth
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(context).apply {
            text = copy.subtitle
            textSize = 10.5f
            setTextColor(if (isDark) 0xFFA6B9D4.toInt() else 0xFF4D6280.toInt())
            maxLines = 2
            maxWidth = bubbleMaxWidth
            ellipsize = TextUtils.TruncateAt.END
        }
        bubbleBox.addView(titleView, LinearLayout.LayoutParams(wrapLp))
        if (copy.subtitle.isNotEmpty()) {
            bubbleBox.addView(subtitleView, LinearLayout.LayoutParams(wrapLp).apply { topMargin = dp(context, 1) })
        }

        val dialogColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(wrapLp)
            addView(bubbleBox)
        }

        root.addView(mascotView)
        root.addView(spacer(context, 6))
        root.addView(dialogColumn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight(context) + dp(context, 14)
        }

        val refs = HudRefs(
            root = root,
            params = params,
            mascotView = mascotView,
            dialogColumn = dialogColumn,
            bubbleBox = bubbleBox,
            bubbleBg = bubbleBg,
            titleView = titleView,
            subtitleView = subtitleView,
            expanded = true,
            interactive = false,
        )

        detachActiveViewSafely(manager)

        runCatching {
            manager.addView(root, params)
            activeRefs = refs
            lastFailureReason.value = ""
            startMascotHaloIfNeeded(mascotView, tone)
            playEntranceAnimation(context, root, epoch)
        }.onFailure { e ->
            activeRefs = null
            lastFailureReason.value = "状态悬浮窗显示失败：" + (e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "显示状态悬浮窗失败: " + e.javaClass.simpleName + " " + e.message)
        }
    }

    /**
     * 点击钉钉娘切换展开 / 收起气泡与控制按钮。
     *
     * 只操作独立的 bubbleHost 窗口，完全不改动 mascotHost 的尺寸或坐标，
     * 保证钉钉娘本人在收起和展开全过程**像素级静止**，绝不瞬移。
     */
    private fun toggleBubbleExpanded(
        context: Context,
        manager: WindowManager,
        refs: HudRefs,
    ) {
        if (activeRefs !== refs) return
        val bHost = refs.bubbleHost ?: return
        val bParams = refs.bubbleParams ?: return
        val nextExpanded = !refs.expanded
        refs.expanded = nextExpanded
        val col = refs.dialogColumn
        val shift = dp(context, 14).toFloat() * (if (dockedRight) 1f else -1f)

        expandAnimator?.cancel()

        if (nextExpanded) {
            arrangeChildren(context, refs, dockRight = dockedRight)
            val screenW = context.resources.displayMetrics.widthPixels
            bParams.gravity = Gravity.TOP or (if (dockedRight) Gravity.END else Gravity.START)
            bParams.x = dp(context, EDGE_MARGIN_DP) + dp(context, MASCOT_SLOT_DP)
            bParams.y = refs.params.y
            col.visibility = View.VISIBLE
            col.alpha = 0f
            col.scaleX = 0.80f
            col.scaleY = 0.80f
            col.translationX = shift

            if (!bHost.isAttachedToWindow) {
                runCatching { manager.addView(bHost, bParams) }
            } else {
                runCatching { manager.updateViewLayout(bHost, bParams) }
            }
            if (bHost.width == 0 && bHost.visibility == View.VISIBLE) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                bHost.measure(widthSpec, heightSpec)
            }
            col.pivotX = if (dockedRight) col.measuredWidth.toFloat().coerceAtLeast(1f) else 0f
            col.pivotY = dp(context, 24).toFloat()

            val anim = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(col, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(col, View.SCALE_X, 0.80f, 1f),
                    ObjectAnimator.ofFloat(col, View.SCALE_Y, 0.80f, 1f),
                    ObjectAnimator.ofFloat(col, View.TRANSLATION_X, shift, 0f),
                )
                duration = 250L
                interpolator = SPRING_POP
            }
            expandAnimator = anim
            anim.start()
        } else {
            if (!bHost.isAttachedToWindow) return
            col.pivotX = if (dockedRight) col.width.toFloat().coerceAtLeast(1f) else 0f
            col.pivotY = dp(context, 24).toFloat()
            val anim = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(col, View.ALPHA, col.alpha, 0f),
                    ObjectAnimator.ofFloat(col, View.SCALE_X, col.scaleX, 0.78f),
                    ObjectAnimator.ofFloat(col, View.SCALE_Y, col.scaleY, 0.78f),
                    ObjectAnimator.ofFloat(col, View.TRANSLATION_X, col.translationX, shift),
                )
                duration = 180L
                interpolator = ACCEL_EXIT
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (activeRefs !== refs || refs.expanded) return
                        removeViewSafely(manager, bHost)
                        col.alpha = 1f
                        col.scaleX = 1f
                        col.scaleY = 1f
                        col.translationX = 0f
                    }
                })
            }
            expandAnimator = anim
            anim.start()
        }
    }

    /** 按停靠左/右同步对话尖角朝向与按钮对齐 */
    private fun arrangeChildren(context: Context, refs: HudRefs, dockRight: Boolean) {
        refs.bubbleBg.setTailOnRight(dockRight)
        updateBubblePadding(context, refs.bubbleBox, dockRight)
        refs.dialogColumn.gravity = if (dockRight) Gravity.END else Gravity.START
        refs.buttonRow?.let { row ->
            row.gravity = if (dockRight) Gravity.END else Gravity.START
            val lp = row.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.marginEnd = if (dockRight) dp(context, 7) else 0
                lp.marginStart = if (dockRight) 0 else dp(context, 7)
                row.layoutParams = lp
            }
        }
    }

    private fun updateBubblePadding(context: Context, bubbleBox: LinearLayout, tailOnRight: Boolean) {
        val tailPad = dp(context, 18)
        val sidePad = dp(context, 13)
        bubbleBox.setPadding(
            if (tailOnRight) sidePad else tailPad,
            dp(context, 9),
            if (tailOnRight) tailPad else sidePad,
            dp(context, 9),
        )
    }

    /** 把气泡窗口紧贴跟随在钉钉娘窗口左/右侧 */
    private fun syncBubblePosition(
        context: Context,
        manager: WindowManager,
        refs: HudRefs,
        mascotLeftPx: Int,
        mascotTopPx: Int,
        mascotW: Int,
        screenW: Int,
    ) {
        val bHost = refs.bubbleHost ?: return
        val bp = refs.bubbleParams ?: return
        if (!refs.expanded || !bHost.isAttachedToWindow) return

        val edgeMargin = dp(context, EDGE_MARGIN_DP)
        val bubbleW = bHost.width.takeIf { it > 0 } ?: bHost.measuredWidth.takeIf { it > 0 } ?: dp(context, 224)
        val overlapPad = dp(context, 2)
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = if (dockedRight) {
            (mascotLeftPx + overlapPad - bubbleW).coerceAtLeast(edgeMargin / 2)
        } else {
            (mascotLeftPx + mascotW - overlapPad)
                .coerceAtMost((screenW - bubbleW - edgeMargin / 2).coerceAtLeast(edgeMargin / 2))
        }
        bp.y = mascotTopPx
        runCatching { manager.updateViewLayout(bHost, bp) }
    }

    /** 支持拖拽与松手磁吸贴边；无论展开还是收起状态，拖任意窗口都会让双窗联动 */
    private fun attachDragAndMagneticSnap(
        context: Context,
        manager: WindowManager,
        refs: HudRefs,
    ) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val edgeMargin = dp(context, EDGE_MARGIN_DP)
        var startRawX = 0f
        var startRawY = 0f
        var startParamY = 0
        var startMascotLeft = 0
        var dragging = false
        var screenWidth = context.resources.displayMetrics.widthPixels

        val intercept: (MotionEvent) -> Boolean = { ev ->
            val p = refs.params
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    snapAnimator = null
                    screenWidth = context.resources.displayMetrics.widthPixels
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    startParamY = p.y
                    val loc = IntArray(2)
                    refs.root.getLocationOnScreen(loc)
                    startMascotLeft = loc[0]
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        p.gravity = Gravity.TOP or Gravity.START
                        p.x = startMascotLeft
                        true
                    } else {
                        dragging
                    }
                }
                else -> false
            }
        }

        val touch: (View, MotionEvent) -> Boolean = touch@{ _, ev ->
            val p = refs.params
            val mascotViewHost = refs.root
            val mascotW = mascotViewHost.width.takeIf { it > 0 } ?: dp(context, 74)
            val mascotH = mascotViewHost.height.takeIf { it > 0 } ?: dp(context, 76)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    p.x = (startMascotLeft + dx.toInt()).coerceIn(
                        edgeMargin / 2,
                        (screenWidth - mascotW - edgeMargin / 2).coerceAtLeast(edgeMargin / 2),
                    )
                    p.y = clampY(context, startParamY + dy.toInt(), mascotH)
                    rememberedY = p.y
                    runCatching { manager.updateViewLayout(mascotViewHost, p) }
                    syncBubblePosition(context, manager, refs, p.x, p.y, mascotW, screenWidth)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@touch false
                    dragging = false
                    val centerX = p.x + mascotW / 2f
                    val snapToRight = centerX >= screenWidth / 2f
                    val targetLeft = if (snapToRight) {
                        (screenWidth - mascotW - edgeMargin).coerceAtLeast(edgeMargin)
                    } else {
                        edgeMargin
                    }
                    val fromX = p.x
                    snapAnimator?.cancel()
                    snapAnimator = ValueAnimator.ofInt(fromX, targetLeft).apply {
                        duration = 300L
                        interpolator = IOS_EASE_OUT
                        addUpdateListener { anim ->
                            if (activeRefs !== refs) {
                                anim.cancel()
                                return@addUpdateListener
                            }
                            p.gravity = Gravity.TOP or Gravity.START
                            p.x = anim.animatedValue as Int
                            runCatching { manager.updateViewLayout(mascotViewHost, p) }
                            syncBubblePosition(context, manager, refs, p.x, p.y, mascotW, screenWidth)
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (activeRefs !== refs) return
                                if (dockedRight != snapToRight) {
                                    dockedRight = snapToRight
                                    arrangeChildren(context, refs, dockRight = snapToRight)
                                }
                                p.gravity = Gravity.TOP or (if (dockedRight) Gravity.END else Gravity.START)
                                p.x = edgeMargin
                                runCatching { manager.updateViewLayout(mascotViewHost, p) }
                                refs.bubbleHost?.let { bHost ->
                                    refs.bubbleParams?.let { bp ->
                                        bp.gravity = Gravity.TOP or (if (dockedRight) Gravity.END else Gravity.START)
                                        bp.x = edgeMargin + dp(context, MASCOT_SLOT_DP)
                                        bp.y = p.y
                                        if (refs.expanded && bHost.isAttachedToWindow) {
                                            runCatching { manager.updateViewLayout(bHost, bp) }
                                        }
                                    }
                                }
                            }
                        })
                        start()
                    }
                    true
                }
                else -> false
            }
        }

        refs.root.interceptHandler = intercept
        refs.root.touchHandler = touch
        refs.bubbleHost?.interceptHandler = intercept
        refs.bubbleHost?.touchHandler = touch
    }

    private fun fadeOutAndRemove(expectedEpoch: Long) {
        if (expectedEpoch != displayEpoch) return
        val refs = activeRefs ?: return
        val mascotHost = refs.root
        val bHost = refs.bubbleHost?.takeIf { it.isAttachedToWindow }
        stopTransientAnimations()

        bHost?.let { bh ->
            bh.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            bh.animate().cancel()
            bh.animate()
                .alpha(0f)
                .scaleX(0.90f)
                .scaleY(0.90f)
                .translationY(-dp(bh.context, 12).toFloat())
                .setDuration(230L)
                .setInterpolator(ACCEL_EXIT)
                .withEndAction {
                    bh.setLayerType(View.LAYER_TYPE_NONE, null)
                    removeViewSafely(wm, bh)
                }
                .start()
        }

        mascotHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        mascotHost.animate().cancel()
        mascotHost.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .translationY(-dp(mascotHost.context, 12).toFloat())
            .setDuration(230L)
            .setInterpolator(ACCEL_EXIT)
            .withEndAction {
                mascotHost.setLayerType(View.LAYER_TYPE_NONE, null)
                if (expectedEpoch == displayEpoch && activeRefs === refs) {
                    removeViewSafely(wm, mascotHost)
                    bHost?.let { removeViewSafely(wm, it) }
                    activeRefs = null
                }
            }
            .start()
    }

    private fun hideInternal() {
        cancelPendingDismiss()
        isPaused = false
        val refs = activeRefs ?: return
        val epoch = ++displayEpoch
        val mascotHost = refs.root
        val bHost = refs.bubbleHost?.takeIf { it.isAttachedToWindow }
        stopTransientAnimations()

        bHost?.let { bh ->
            bh.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            bh.animate().cancel()
            bh.animate()
                .alpha(0f)
                .scaleX(0.90f)
                .scaleY(0.90f)
                .translationY(-dp(bh.context, 12).toFloat())
                .setDuration(210L)
                .setInterpolator(ACCEL_EXIT)
                .withEndAction {
                    bh.setLayerType(View.LAYER_TYPE_NONE, null)
                    removeViewSafely(wm, bh)
                }
                .start()
        }

        mascotHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        mascotHost.animate().cancel()
        mascotHost.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .translationY(-dp(mascotHost.context, 12).toFloat())
            .setDuration(210L)
            .setInterpolator(ACCEL_EXIT)
            .withEndAction {
                mascotHost.setLayerType(View.LAYER_TYPE_NONE, null)
                if (epoch == displayEpoch && activeRefs === refs) {
                    removeViewSafely(wm, mascotHost)
                    bHost?.let { removeViewSafely(wm, it) }
                    activeRefs = null
                }
            }
            .start()
    }

    private fun detachActiveViewSafely(manager: WindowManager) {
        stopTransientAnimations()
        val old = activeRefs
        activeRefs = null
        old?.bubbleHost?.let { bh ->
            bh.animate().cancel()
            bh.setLayerType(View.LAYER_TYPE_NONE, null)
            removeViewSafely(manager, bh)
        }
        old?.root?.let { root ->
            root.animate().cancel()
            root.setLayerType(View.LAYER_TYPE_NONE, null)
            removeViewSafely(manager, root)
        }
    }

    private fun removeViewSafely(manager: WindowManager?, target: View) {
        if (manager == null) return
        runCatching {
            if (target.isAttachedToWindow) {
                manager.removeViewImmediate(target)
            } else {
                manager.removeView(target)
            }
        }
    }

    private fun cancelPendingDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun stopTransientAnimations() {
        haloAnim?.cancel()
        haloAnim = null
        snapAnimator?.cancel()
        snapAnimator = null
        expandAnimator?.cancel()
        expandAnimator = null
    }

    private fun playEntranceAnimation(context: Context, root: View, epoch: Long) {
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.alpha = 0f
        root.scaleX = 0.85f
        root.scaleY = 0.85f
        root.translationY = -dp(context, 14).toFloat()
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(330L)
            .setInterpolator(SPRING_POP)
            .withEndAction {
                if (epoch == displayEpoch) {
                    root.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }
            .start()
    }

    private fun playMicroBounce(target: View) {
        target.animate().cancel()
        target.scaleX = 0.94f
        target.scaleY = 0.94f
        target.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(SPRING_POP)
            .start()
    }

    private fun startMascotHaloIfNeeded(mascotView: MascotAvatarView, tone: Tone) {
        haloAnim?.cancel()
        haloAnim = null
        mascotView.haloPhase = if (tone == Tone.RUNNING) 0.5f else 0f
        if (tone != Tone.RUNNING) return

        haloAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = IOS_EASE_OUT
            addUpdateListener { anim ->
                mascotView.haloPhase = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun updateToggleButtonLook(context: Context, btn: TextView?, paused: Boolean) {
        if (btn == null) return
        val isDark = isDarkMode(context)
        val label = if (paused) "▶ 继续" else "⏸ 暂停"
        val accentArgb = if (paused) 0xFF0A84FF.toInt() else 0xFFFF9F0A.toInt()
        btn.text = label
        applyControlPillDrawable(context, btn, accentArgb, isDark)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeControlButton(
        context: Context,
        label: String,
        accentArgb: Int,
        isDark: Boolean,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
            applyControlPillDrawable(context, this, accentArgb, isDark)
            elevation = dp(context, 4).toFloat()
            setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate().scaleX(0.90f).scaleY(0.90f)
                            .setDuration(75L).setInterpolator(IOS_EASE_OUT).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200L).setInterpolator(SPRING_POP).start()
                    }
                }
                false
            }
            setOnClickListener { view ->
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                onClick()
            }
        }
    }

    private fun applyControlPillDrawable(
        context: Context,
        btn: TextView,
        accentArgb: Int,
        isDark: Boolean,
    ) {
        val r = Color.red(accentArgb)
        val g = Color.green(accentArgb)
        val b = Color.blue(accentArgb)
        btn.setTextColor(
            if (isDark) Color.argb(245, (r + 255) / 2, (g + 255) / 2, (b + 255) / 2)
            else Color.argb(245, r, (g * 0.78f).toInt(), (b * 0.78f).toInt()),
        )
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999).toFloat()
            setColor(
                if (isDark) Color.argb(230, 16, 28, 52)
                else Color.argb(244, 248, 251, 255),
            )
            setStroke(
                dp(context, 1),
                Color.argb(if (isDark) 148 else 135, r, g, b),
            )
        }
    }

    /** 支持子控件点击同时保留整窗拖拽的根布局 */
    private class HudRootLayout(context: Context) : LinearLayout(context) {
        var interceptHandler: ((MotionEvent) -> Boolean)? = null
        var touchHandler: ((View, MotionEvent) -> Boolean)? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return interceptHandler?.invoke(ev) ?: super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            return (touchHandler?.invoke(this, event) == true) || super.onTouchEvent(event)
        }
    }

    /**
     * 纯净无背景框的像素风「钉钉娘」桌面宠物视图：
     * - 去掉底层蓝框、描边与云底，直接把透明底钉钉娘立绘浮在屏幕上；
     * - 收起态将运行状态自然融入钉钉娘本身轮廓与发卡元素中（不挂突兀红点）：
     *   - RUNNING：贴合角色轮廓轻柔呼吸的晴空蓝光晕 + 右发翼旁像素十字星微闪；
     *   - PAUSED：人物柔和降饱和进入半透明小憩态 + 头部右上方轻落像素小「Z」；
     *   - SUCCESS / FAILURE / LATE：融合对应轻柔轮廓光晕。
     */
    private class MascotAvatarView(
        context: Context,
    ) : View(context) {

        private val sprite: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.ic_dingtalk_girl_sprite)
        private val alphaMask: Bitmap? = sprite?.extractAlpha()

        private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = false
            isDither = false
        }
        private val mutedFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0.45f) },
        )
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            colorFilter = PorterDuffColorFilter(0x460C1838, PorterDuff.Mode.SRC_IN)
            maskFilter = BlurMaskFilter(dp(context, 2).toFloat().coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            maskFilter = BlurMaskFilter(dp(context, 4).toFloat().coerceAtLeast(2f), BlurMaskFilter.Blur.OUTER)
        }
        private val pixelPaint = Paint().apply { isAntiAlias = false }
        private val spriteRect = RectF()
        private val shadowRect = RectF()
        private val auraRect = RectF()

        private var tone: Tone = Tone.RUNNING
        var haloPhase: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        init {
            isClickable = true
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setTone(newTone: Tone) {
            tone = newTone
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f || sprite == null) return

            val pad = dp(context, 4).toFloat()
            spriteRect.set(pad, pad, w - pad, h - pad)
            val u = spriteRect.width() / 16f

            // 1) 轮廓柔光晕（无背景框，贴合人物剪影本身）
            val auraColor = when (tone) {
                Tone.RUNNING -> 0xFF38BDF8.toInt()
                Tone.PAUSED -> 0xFF94A3B8.toInt()
                Tone.SUCCESS -> 0xFF34D399.toInt()
                Tone.FAILURE -> 0xFFFF5C7A.toInt()
                Tone.LATE -> 0xFFFFB020.toInt()
            }
            if (alphaMask != null) {
                // 轻柔贴合剪影下投影，保证在浅色/深色背景上都立体清晰
                val sy = dp(context, 2).toFloat()
                shadowRect.set(spriteRect.left, spriteRect.top + sy, spriteRect.right, spriteRect.bottom + sy)
                canvas.drawBitmap(alphaMask, null, shadowRect, shadowPaint)

                if (tone != Tone.PAUSED) {
                    val expand = dp(context, 1).toFloat()
                    val alpha = if (tone == Tone.RUNNING) (110 + (120 * haloPhase)).toInt() else 185
                    auraRect.set(
                        spriteRect.left - expand,
                        spriteRect.top - expand,
                        spriteRect.right + expand,
                        spriteRect.bottom + expand,
                    )
                    auraPaint.colorFilter = PorterDuffColorFilter(
                        Color.argb(alpha, Color.red(auraColor), Color.green(auraColor), Color.blue(auraColor)),
                        PorterDuff.Mode.SRC_IN,
                    )
                    canvas.drawBitmap(alphaMask, null, auraRect, auraPaint)
                }
            }

            // 2) 绘制纯粹的像素钉钉娘本尊（Paused 时轻降饱和与透明度呈现小憩感）
            if (tone == Tone.PAUSED) {
                spritePaint.colorFilter = mutedFilter
                spritePaint.alpha = 188
            } else {
                spritePaint.colorFilter = null
                spritePaint.alpha = 255
            }
            canvas.drawBitmap(sprite, null, spriteRect, spritePaint)

            // 3) 融入头翼发卡/发梢的细腻像素状态小信号（无突兀红点）
            val left = spriteRect.left
            val top = spriteRect.top
            when (tone) {
                Tone.RUNNING -> {
                    // 右翼发卡旁闪烁的元气像素小十字星
                    val starAlpha = (145 + (110 * haloPhase)).toInt()
                    pixelPaint.color = Color.argb(starAlpha, 125, 224, 255)
                    canvas.drawRect(left + u * 13.6f, top + u * 1.1f, left + u * 14.4f, top + u * 3.3f, pixelPaint)
                    canvas.drawRect(left + u * 12.9f, top + u * 1.8f, left + u * 15.1f, top + u * 2.6f, pixelPaint)
                    pixelPaint.color = Color.argb(starAlpha, 255, 250, 210)
                    canvas.drawRect(left + u * 13.6f, top + u * 1.8f, left + u * 14.4f, top + u * 2.6f, pixelPaint)
                }
                Tone.PAUSED -> {
                    // 小憩时头顶右侧漂浮的小小像素 "Z"
                    pixelPaint.color = 0xE67CA6D9.toInt()
                    canvas.drawRect(left + u * 12.6f, top + u * 1.0f, left + u * 14.8f, top + u * 1.6f, pixelPaint)
                    canvas.drawRect(left + u * 13.3f, top + u * 1.6f, left + u * 14.1f, top + u * 2.2f, pixelPaint)
                    canvas.drawRect(left + u * 12.6f, top + u * 2.2f, left + u * 14.8f, top + u * 2.8f, pixelPaint)
                }
                Tone.SUCCESS, Tone.LATE, Tone.FAILURE -> {
                    pixelPaint.color = Color.argb(235, Color.red(auraColor), Color.green(auraColor), Color.blue(auraColor))
                    canvas.drawRect(left + u * 13.6f, top + u * 1.1f, left + u * 14.4f, top + u * 3.3f, pixelPaint)
                    canvas.drawRect(left + u * 12.9f, top + u * 1.8f, left + u * 15.1f, top + u * 2.6f, pixelPaint)
                    pixelPaint.color = Color.WHITE
                    canvas.drawRect(left + u * 13.6f, top + u * 1.8f, left + u * 14.4f, top + u * 2.6f, pixelPaint)
                }
            }
        }
    }

    /**
     * 自绘对话气泡背景：圆角液态玻璃本体 + 指向钉钉娘的三角对话尖角。
     */
    private class SpeechBubbleDrawable(
        private val context: Context,
        private val isDark: Boolean,
        private var tailOnRight: Boolean,
        private var tone: Tone,
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bubblePath = Path()

        fun setTailOnRight(onRight: Boolean) {
            if (tailOnRight != onRight) {
                tailOnRight = onRight
                invalidateSelf()
            }
        }

        fun setToneTint(newTone: Tone, dark: Boolean) {
            if (tone != newTone) {
                tone = newTone
                invalidateSelf()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 4 || b.height() <= 4) return

            val tailW = dp(context, 7).toFloat()
            val tailH = dp(context, 11).toFloat()
            val radius = dp(context, 16).toFloat()
            val strokeW = dp(context, 1).toFloat()
            val halfStroke = strokeW / 2f

            val left = (b.left + if (tailOnRight) halfStroke else tailW + halfStroke)
            val right = (b.right - if (tailOnRight) tailW + halfStroke else halfStroke)
            val top = b.top + halfStroke
            val bottom = b.bottom - halfStroke
            val tailMidY = (top + dp(context, 24)).coerceIn(top + radius + 2f, bottom - tailH / 2f - 4f)

            bubblePath.reset()
            if (tailOnRight) {
                bubblePath.moveTo(left + radius, top)
                bubblePath.lineTo(right - radius, top)
                bubblePath.quadTo(right, top, right, top + radius)
                bubblePath.lineTo(right, tailMidY - tailH * 0.45f)
                bubblePath.lineTo(b.right - halfStroke, tailMidY)
                bubblePath.lineTo(right, tailMidY + tailH * 0.45f)
                bubblePath.lineTo(right, bottom - radius)
                bubblePath.quadTo(right, bottom, right - radius, bottom)
                bubblePath.lineTo(left + radius, bottom)
                bubblePath.quadTo(left, bottom, left, bottom - radius)
                bubblePath.lineTo(left, top + radius)
                bubblePath.quadTo(left, top, left + radius, top)
            } else {
                bubblePath.moveTo(left + radius, top)
                bubblePath.lineTo(right - radius, top)
                bubblePath.quadTo(right, top, right, top + radius)
                bubblePath.lineTo(right, bottom - radius)
                bubblePath.quadTo(right, bottom, right - radius, bottom)
                bubblePath.lineTo(left + radius, bottom)
                bubblePath.quadTo(left, bottom, left, bottom - radius)
                bubblePath.lineTo(left, tailMidY + tailH * 0.45f)
                bubblePath.lineTo(b.left + halfStroke, tailMidY)
                bubblePath.lineTo(left, tailMidY - tailH * 0.45f)
                bubblePath.lineTo(left, top + radius)
                bubblePath.quadTo(left, top, left + radius, top)
            }
            bubblePath.close()

            fillPaint.shader = LinearGradient(
                0f,
                top,
                0f,
                bottom,
                if (isDark) {
                    intArrayOf(0xF2152440.toInt(), 0xE80E1930.toInt())
                } else {
                    intArrayOf(0xF8F9FCFF.toInt(), 0xF0ECF4FF.toInt())
                },
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(bubblePath, fillPaint)

            sheenPaint.shader = LinearGradient(
                0f,
                top,
                0f,
                top + (bottom - top) * 0.48f,
                if (isDark) intArrayOf(0x2EFFFFFF, 0x00FFFFFF) else intArrayOf(0xB8FFFFFF.toInt(), 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(bubblePath, sheenPaint)

            strokePaint.strokeWidth = strokeW
            strokePaint.color = when (tone) {
                Tone.FAILURE -> 0x99FF5672.toInt()
                Tone.PAUSED -> if (isDark) 0x6094A3B8 else 0xA894A3B8.toInt()
                Tone.SUCCESS -> 0x9034D399.toInt()
                Tone.LATE -> 0x90FFB020.toInt()
                Tone.RUNNING -> if (isDark) 0x668CD6FF else 0xE0FFFFFF.toInt()
            }
            canvas.drawPath(bubblePath, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 36)
    }

    private fun clampY(context: Context, targetY: Int, viewHeight: Int): Int {
        val screenH = context.resources.displayMetrics.heightPixels
        val minY = statusBarHeight(context) + dp(context, 8)
        val maxY = (screenH - viewHeight - dp(context, 72)).coerceAtLeast(minY)
        return targetY.coerceIn(minY, maxY)
    }

    private fun spacer(context: Context, w: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, w), 1)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
