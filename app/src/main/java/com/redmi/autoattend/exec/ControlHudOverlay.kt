package com.redmi.autoattend.exec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.redmi.autoattend.ui.theme.DarkGlassColors
import com.redmi.autoattend.ui.theme.LightGlassColors

/**
 * 打卡执行时的系统级悬浮提示。
 *
 * ## 为什么必须是系统悬浮窗
 *
 * 打卡时要打开钉钉并覆盖在它上面。那时钉钉在前台，
 * 我们自己的 Activity 在后台 —— 应用内的 Composable 完全不可见。
 *
 * 早期版本把提示做成了应用内的 Compose 组件，实际效果是：
 * 用户在钉钉界面上什么都看不到，回到本应用才看见提示一闪而过。
 * 那等于没有提示。
 *
 * 所以这里用 TYPE_APPLICATION_OVERLAY 的 WindowManager 视图，
 * 它能盖在任何应用之上（需要 SYSTEM_ALERT_WINDOW 授权）。
 *
 * ## 只在接管时存在
 *
 * show() 创建视图，hide() 立即移除 —— 不运行时不占任何资源。
 *
 * ## 放在右侧时向左伸展
 *
 * 视图用 Gravity.END 贴右，内容自然向左铺开。
 */
object ControlHudOverlay {

    private const val TAG = "ControlHud"

    private var view: View? = null
    private var wm: WindowManager? = null

    /**
     * 最近一次显示失败的原因。
     *
     * 空字符串表示没有失败。执行引擎会把它并入打卡结果，
     * 让"提示条没出现"这件事在日志和通知里可见。
     */
    val lastFailureReason = kotlinx.coroutines.flow.MutableStateFlow("")

    val isShowing: Boolean get() = view != null

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        onPause: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // 【线程约束】WindowManager.addView 必须在有 Looper 的线程执行。
        //
        // PunchEngine.execute() 跑在 Dispatchers.IO 上，那里没有 Looper，
        // 直接 addView 会抛异常 —— 被 runCatching 吞掉后提示条永远不出现。
        // 所以这里统一投递到主线程，调用方无需关心自己在哪个线程。
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            showInternal(context, title, subtitle, onPause, onCancel)
        } else {
            main.post { showInternal(context, title, subtitle, onPause, onCancel) }
        }
    }

    private fun showInternal(
        context: Context,
        title: String,
        subtitle: String,
        onPause: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            // 【不再静默跳过】
            //
            // 之前这里只打一条 Log.w 就 return，用户和界面都无从得知
            // 提示条为什么没出现 —— 真机上排查了很久才发现是权限没给。
            //
            // ## 真机发现的关键事实（HyperOS）
            //
            // MIUI/HyperOS 的「显示在其他应用的上层」开关界面会显示**已开启**，
            // 但 Settings.canDrawOverlays() 仍可能返回 false ——
            // appops 里实际的 mode 是 ignore，两个判定源不一致。
            //
            // 所以这条日志把 **canDrawOverlays 的返回值** 也带上，
            // 排查时能立刻区分"真的没授权"和"系统判定不一致"。
            lastFailureReason.value = "缺少「显示在其他应用上层」权限，屏幕接管提示无法显示"
            Log.w(
                TAG,
                "没有悬浮窗权限（canDrawOverlays=false），提示条不会显示。" +
                    "请到系统设置 → 应用 → 钉钉GO → 显示在其他应用的上层 重新开关一次。",
            )
            return
        }

        // 【线程约束】WindowManager.addView 必须在有 Looper 的线程执行。
        //
        // PunchEngine.execute() 跑在 Dispatchers.IO 上，那里没有 Looper，
        // 直接 addView 会抛异常 → 被 runCatching 吞掉 → 提示条永远不出现。
        // 这是真机上"从未见过悬浮窗"的一个可能原因，所以显式投递到主线程。
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val isDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDark) DarkGlassColors else LightGlassColors

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
        }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 26).toFloat()
            if (isDark) {
                setColor(Color.argb(240, 16, 26, 46))
            } else {
                setColor(Color.argb(232, 252, 253, 255))
            }
            setStroke(dp(context, 1), Color.argb(if (isDark) 90 else 150, 255, 255, 255))
        }
        root.background = bg
        root.elevation = dp(context, 8).toFloat()

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textBox.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            setTextColor(if (isDark) Color.parseColor("#F4F7FC") else Color.parseColor("#1B1F26"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textBox.addView(TextView(context).apply {
            text = subtitle
            textSize = 10.5f
            setTextColor(if (isDark) Color.parseColor("#9AA6B8") else Color.parseColor("#5A616B"))
        })
        root.addView(textBox)

        root.addView(spacer(context, 10))

        root.addView(makeButton(context, "II", colors.warning, onPause))
        root.addView(spacer(context, 6))
        root.addView(makeButton(context, "X", colors.error, onCancel))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(context, 14)
            y = dp(context, 120)
        }

        runCatching {
            view?.let { manager.removeView(it) }
            manager.addView(root, params)
            view = root
            lastFailureReason.value = ""
            Log.i(TAG, "悬浮提示已显示")
        }.onFailure {
            // 常见原因：在无 Looper 的线程调用 addView、
            // TYPE_APPLICATION_OVERLAY 被系统策略拒绝、WindowManager 已失效。
            lastFailureReason.value =
                "屏幕接管提示显示失败：" + (it.message ?: it.javaClass.simpleName)
            Log.w(TAG, "显示悬浮提示失败: " + it.javaClass.simpleName + " " + it.message)
        }
    }

    /**
     * 【调试入口】只显示提示条，不涉及任何打卡逻辑。
     *
     * 存在的理由：ControlHudOverlay 正常只在 PunchEngine 的真实打卡流程里出现，
     * 而验证它需要走一次打卡 —— 那会在用户的考勤系统里留下记录。
     * 为了能在**不打卡**的前提下验证"悬浮窗到底能不能显示"，
     * 提供这个纯 UI 的自检入口。
     *
     * 由设置页的「测试悬浮窗」按钮调用。
     */
    fun showForTest(
        context: Context,
        holdMs: Long = 3_000L,
    ) {
        show(
            context = context,
            title = "屏幕接管提示 · 测试",
            subtitle = "如果你看到这条，说明悬浮窗正常",
            onPause = { /* 测试模式不响应 */ },
            onCancel = { hide() },
        )
        // 自动收起，避免测试痕迹留在屏幕上
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { hide() },
            holdMs,
        )
    }

    fun hide() {
        // removeView 同样要求有 Looper 的线程，与 show 保持一致。
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            hideInternal()
        } else {
            main.post { hideInternal() }
        }
    }

    private fun hideInternal() {
        runCatching {
            val v = view ?: return
            wm?.removeView(v)
        }
        view = null
    }

    private fun spacer(context: Context, w: Int): View {
        val v = View(context)
        v.layoutParams = LinearLayout.LayoutParams(dp(context, w), 1)
        return v
    }

    private fun makeButton(
        context: Context,
        label: String,
        accent: androidx.compose.ui.graphics.Color,
        onClick: () -> Unit,
    ): View {
        val size = dp(context, 26)
        val argb = android.graphics.Color.argb(
            (accent.alpha * 255).toInt(),
            (accent.red * 255).toInt(),
            (accent.green * 255).toInt(),
            (accent.blue * 255).toInt(),
        )
        return TextView(context).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(argb)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(40,
                    (accent.red * 255).toInt(),
                    (accent.green * 255).toInt(),
                    (accent.blue * 255).toInt()))
                setStroke(dp(context, 1), android.graphics.Color.argb(90,
                    (accent.red * 255).toInt(),
                    (accent.green * 255).toInt(),
                    (accent.blue * 255).toInt()))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
