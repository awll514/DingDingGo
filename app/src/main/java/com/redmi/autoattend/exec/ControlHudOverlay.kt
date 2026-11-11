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

    val isShowing: Boolean get() = view != null

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        onPause: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "没有悬浮窗权限，跳过提示")
            return
        }

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
            Log.i(TAG, "悬浮提示已显示")
        }.onFailure {
            Log.w(TAG, "显示悬浮提示失败: " + it.javaClass.simpleName)
        }
    }

    fun hide() {
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
