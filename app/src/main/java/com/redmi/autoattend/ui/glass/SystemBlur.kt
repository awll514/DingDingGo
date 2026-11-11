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
