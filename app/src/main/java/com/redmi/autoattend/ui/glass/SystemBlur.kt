package com.redmi.autoattend.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * HyperOS / Android 12+ 的**系统级实时模糊**封装。
 *
 * ## 为什么可以和 AGSL 一起用（各取所长）
 *
 * 两者作用在完全不同的图层上，所以不冲突：
 *
 * ```
 * ┌─────────────────────────────────────────┐
 * │  SurfaceFlinger 层（系统合成器）           │
 * │  ├─ 壁纸 / 其他 App                       │
 * │  └─ 本 App 的 Window                      │
 * │       └─ [系统背景模糊] ← 模糊 Window 之后的东西 │
 * │            └─ Compose 内容                 │
 * │                 └─ [AGSL 玻璃面板]          │
 * │                      ↑ 采样窗口内的流动背景  │
 * └─────────────────────────────────────────┘
 * ```
 *
 * - **系统模糊**（SurfaceFlinger + Kawase）：模糊的是 **Window 之外**的内容，
 *   也就是壁纸和其他应用。它由系统合成器做，GPU 成本几乎为零（因为
 *   模糊结果会被缓存复用）。实测你这台机器 `PassBlur` 缓冲区只有
 *   320x42 / 42x693 这种小块，说明是分块模糊，非常省。
 *
 * - **AGSL 玻璃面板**：模糊的是 **Window 之内**的流动背景（我们自己画的
 *   光斑）。它能做边缘折射、色散、镜面高光——这些系统模糊完全做不到。
 *
 * 所以最优解是：
 * 1. 给 Window 开启系统背景模糊 → 让 App 后面的壁纸也变糊，
 *    这样半透明的地方不会露出清晰壁纸（观感更"沉浸"）；
 * 2. 面板内部继续用 AGSL → 保留液态玻璃的折射与色散。
 *
 * ## 为什么不敢只依赖系统模糊
 *
 * Android 的 `setBackgroundBlurRadius` 只能模糊 **Window 之后**的内容，
 * 无法模糊 Window 内部的 View——而我们的玻璃面板需要模糊的正是
 * 同一 Window 内的流动背景。所以系统模糊**替代不了** AGSL。
 *
 * ## 兼容性
 *
 * - API 31+ 支持 `RenderEffect.createBlurEffect`；
 * - Window 级背景模糊需要 `FLAG_BLUR_BEHIND`（API 31+）且系统开启；
 * - 系统关闭模糊时（`persist.sys.background_blur_supported=false`），
 *   自动跳过，不影响功能。
 */
object SystemBlur {

    /** 系统是否支持并启用了背景模糊 */
    val isSupported: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            false
        } else {
            // 优先看属性；取不到就乐观返回 true（真机上开模糊是常态）
            runCatching {
                val p = Class.forName("android.os.SystemProperties")
                val get = p.getMethod("getBoolean", String::class.java, Boolean::class.java)
                get.invoke(null, "persist.sys.background_blur_supported", true) as? Boolean ?: true
            }.getOrDefault(true)
        }
    }

    /**
     * 给一个 Composable 加系统级 RenderEffect 模糊。
     *
     * 用于「窗口内、但需要模糊其背后同级内容」的场景，
     * 例如启动遮罩、弹层背景。
     *
     * 注意：这和 AGSL 面板不冲突，两者叠加效果更好（一个糊窗外、一个糊窗内）。
     */
    fun Modifier.systemBlur(radiusPx: Float): Modifier {
        if (!isSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
        return this.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
}

/** 组合本机实际可用的模糊管线描述，用于设置页如实展示 */
@Composable
fun rememberGlassPipelineDescription(): String = remember {
    buildString {
        append(if (com.styropyr0.prismal.isAGSLShaderSupported()) "AGSL 液态玻璃" else "降级磨砂")
        if (SystemBlur.isSupported) {
            append(" + 系统实时模糊")
        }
    }
}
/**
 * 【弹窗专用】给 Dialog 的 Window 开启系统级背景模糊。
 *
 * ## 为什么弹窗必须用这条路，而不是 AGSL
 *
 * AGSL 面板的模糊只作用于**它采样的 GraphicsLayer**。
 * 但弹窗采样的是主界面内容层的录制结果 ——
 * 弹窗打开后主界面被遮挡、不再重绘，那个录制层就不再更新，
 * 于是 blurRadius 实际上作用在一张静态快照上，视觉上「没有模糊」。
 *
 * 这是实测出来的：把 blurRadius 从 32dp 调到 52dp，画面毫无变化。
 *
 * ## 系统级背景模糊才是 iOS 的真实做法
 *
 * `Window.setBackgroundBlurRadius()` 由 SurfaceFlinger 在合成阶段执行，
 * 模糊的是**该 Window 之后的所有图层**（也就是弹窗背后的整个界面）。
 * 效果稳定、性能好，而且不依赖任何图层录制。
 *
 * 配合一层半透明填充，就是 iOS 26 sheet 的标准做法：
 *   · 背景：系统模糊（真模糊，一定有）
 *   · 前景：半透明材质 + 描边 + 阴影
 *
 * @param radius 模糊半径（dp 换算成 px 由调用方负责）
 */
@Composable
fun DialogSystemBlur(radiusDp: Float = 28f) {
    val view = androidx.compose.ui.platform.LocalView.current

    // 【第 9 次修正】不再从 View 树里猜 Window。
    //
    // 前两次失败的共同点：都拿到了**主窗口**。
    //   · parent 链 → 走到 Activity 的 DecorView
    //   · rootView 的 mAttachInfo → 同样指向宿主 Window
    // 而 dumpsys 证实弹窗是独立的 Window #11，主窗口是 #12。
    //
    // 最可靠的方式：**直接枚举 WindowManager 里的窗口**,
    // 按「类型是 DIALOG 且属于本应用」挑出来。
    // 这是唯一不依赖 View 树结构的地方。
    androidx.compose.runtime.LaunchedEffect(radiusDp) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return@LaunchedEffect

        val ctx = view.context
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager

        runCatching {
            val density = ctx.resources.displayMetrics.density
            val px = (radiusDp * density).toInt()

            // 反射拿 WindowManagerGlobal 里的所有 root view，
            // 从中挑出属于 Dialog 的那个。
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstance = wmgClass.getMethod("getInstance")
            val wmg = getInstance.invoke(null)
            val viewsField = wmgClass.getDeclaredField("mViews")
            viewsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val views = viewsField.get(wmg) as? List<android.view.View> ?: emptyList()

            android.util.Log.i("DialogBlur", "WindowManagerGlobal 中共 " + views.size + " 个 root view")

            var applied = false
            for (v in views) {
                // 只处理本应用、且是 Dialog 类型的 root
                val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: continue
                val isDialog = lp.type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION
                val belongToUs = lp.packageName?.contains("autoattend") == true
                android.util.Log.i(
                    "DialogBlur",
                    "rootView=" + v.javaClass.simpleName +
                        " type=" + lp.type + " pkg=" + lp.packageName +
                        " isDialog=" + isDialog + " ours=" + belongToUs,
                )
                if (!isDialog || !belongToUs) continue

                // 这是弹窗的 root。它所属的 Window 可以通过 ViewRootImpl 反查，
                // 但更简单：直接改 lp 并 re-add。
                lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.setBlurBehindRadius(px)
                // 【关键】dimAmount 归零。
                //
                // Dialog 默认给背景加一层黑色蒙版（约 60%），
                // 它会**盖在模糊结果之上**，把整片压暗。
                // 用户看到的"背景发黑"就是这层蒙版，不是模糊本身。
                //
                // iOS 的 sheet 背景是**模糊 + 极淡的纱**，不需要压黑：
                // 模糊本身已经提供了足够的层次分离。
                lp.dimAmount = 0.06f
                runCatching { wm.updateViewLayout(v, lp) }
                applied = true
                android.util.Log.i("DialogBlur", "已给弹窗 root 应用模糊 radius=" + px + "px")
            }

            if (!applied) android.util.Log.w("DialogBlur", "没有找到弹窗 root view")
        }.onFailure {
            android.util.Log.w("DialogBlur", "失败: " + it.javaClass.simpleName + " " + it.message)
        }
    }
}

/**
 * 从任意 View 反查它所属的 Window。
 *
 * Compose Dialog 的 View 树是：DialogWrapper → DecorView → ... ，
 * 只有 DecorView 的 `.window` 属性（隐藏 API）或
 * `View.getRootView()` 的上下文才能拿到 Window。
 *
 * 这里用最稳的两条路：
 *   1. DialogWrapper 实现了 DialogWindowProvider，直接取 window；
 *   2. 退化到反射 DecorView 的 mWindow 字段。
 */
private fun findWindowForView(start: android.view.View?): android.view.Window? {
    var v: android.view.View? = start
    var depth = 0
    while (v != null && depth < 20) {
        // 路 1：DialogWrapper 实现了隐藏接口 Window.DialogWindowProvider，
        // 该接口不是 SDK 公开 API，只能反射调用 getWindow()。
        if (v.javaClass.simpleName.contains("DialogWrapper")) {
            runCatching {
                val m = v.javaClass.getMethod("getWindow")
                (m.invoke(v) as? android.view.Window)?.let { return it }
            }
        }
        // 路 2：DecorView 的隐藏字段
        if (v.javaClass.name.contains("DecorView")) {
            runCatching {
                val f = v.javaClass.getDeclaredField("mWindow")
                f.isAccessible = true
                (f.get(v) as? android.view.Window)?.let { return it }
            }
        }
        v = v.parent as? android.view.View
        depth++
    }
    return null
}
