package com.dingdinggo.app.ui.glass

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView

/**
 * HyperOS / Android 12+ 系统级实时模糊工具。
 *
 * 与 Backdrop 玻璃面板分层协同：
 * - 系统 `blurBehindRadius` 模糊独立 Window 之后的桌面壁纸或主界面；
 * - Backdrop 玻璃面板模糊并折射同 Window 内的流动背景和内容。
 */
object SystemBlur {

    val isSupported: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            false
        } else {
            runCatching {
                val p = Class.forName("android.os.SystemProperties")
                val get = p.getMethod("getBoolean", String::class.java, Boolean::class.java)
                get.invoke(null, "persist.sys.background_blur_supported", true) as? Boolean ?: true
            }.getOrDefault(true)
        }
    }

    fun Modifier.systemBlur(radiusPx: Float): Modifier {
        if (!isSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0.5f) {
            return this
        }
        return this.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
}

/**
 * 为 Compose Dialog 所在独立 Window 应用系统级背景模糊。
 *
 * 直接枚举 WindowManagerGlobal.mViews，定位属于本应用的 TYPE_APPLICATION 弹窗视图，
 * 启用 FLAG_BLUR_BEHIND 并降低 dimAmount，避免弹窗背板发黑。
 */
@Composable
fun DialogSystemBlur(radiusDp: Float = 28f) {
    val view = LocalView.current

    LaunchedEffect(radiusDp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val ctx = view.context
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@LaunchedEffect

        runCatching {
            val px = (radiusDp * ctx.resources.displayMetrics.density).toInt()
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wmg = wmgClass.getMethod("getInstance").invoke(null)
            val viewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = viewsField.get(wmg) as? List<View> ?: emptyList()

            for (v in views) {
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
                val isDialog = lp.type == WindowManager.LayoutParams.TYPE_APPLICATION
                val belongToUs = lp.packageName == ctx.packageName
                if (!isDialog || !belongToUs) continue

                lp.flags = lp.flags or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                lp.setBlurBehindRadius(px)
                lp.dimAmount = 0.08f
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }.onFailure {
            Log.w("DialogBlur", "apply blur failed: ${it.javaClass.simpleName}")
        }
    }
}
