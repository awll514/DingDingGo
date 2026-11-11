package com.redmi.autoattend.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.isAGSLShaderSupported
import com.styropyr0.prismal.sources.rememberPrismalCanvasSource
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 液态玻璃面板 —— 基于 Kyant0/AndroidLiquidGlass 实现。
 *
 * ## 为什么换库
 *
 * 原先用 PrismalAGSL（50 星）。它在本机有一个硬伤：**blurRadius 完全不生效**。
 * 真机实测证据：
 *   · 14dp / 60dp / 200dp 三档截图逐像素一致
 *   · 折射、色散归零 → 仍然不糊
 *   · 底栏高度 52dp → 120dp → 仍然不糊
 *   · 它自己的 supportsBackdropBlur 却返回 true
 *
 * 为此试过七八种绕过方案（外层 RenderEffect、RenderNode 中转、
 * Bitmap 拷贝、录制层预模糊、CanvasDrawScope），全部卡在同一个物理限制：
 * **Compose 的 GraphicsLayer 无法在绘制阶段同步读成像素**，
 * 而 drawLayer 画出去的内容不经过父节点的 RenderEffect。
 *
 * 换成 Kyant0/AndroidLiquidGlass（4600 星）后，模糊一次就通了 ——
 * 它自带 Backdrop 录制 + 库内模糊/折射，不走 Compose 效果链。
 *
 * ## 效果链（顺序不能改）
 *
 * 照抄官方 LiquidBottomTabs 的写法：
 *
 *     effects = {
 *         vibrancy()                        // 提亮 + 提饱和
 *         blur(radius)                      // 真实高斯模糊
 *         lens(w, h)                        // 边缘透镜，参数是**像素尺寸**
 *     }
 *
 * 踩过的坑：
 *   · 把 vibrancy 放到最后 → 模糊被覆盖，等于没糊
 *   · lens 传 1.2 / 0.35 这种"强度系数" → 实际是 1 像素，等于没折射
 *
 * ## tintAlpha 不是"着色浓度"，而是"挡住下层多少"
 *
 * 这是整件事最容易误判的地方。取 0.05 时玻璃几乎全透明，
 * 下层页面文字**原样透上来**，采样到的模糊被它盖过去 ——
 * 无论 blur 调到 8dp 还是 150px，看起来都是"完全没糊"。
 *
 * 官方底栏用 containerColor.copy(0.4f)，即 40% 不透明。
 * 这个量既能压住下层锐利边缘，又能让玻璃里的模糊光影透出来。
 */
@Composable
fun LiquidGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusLarge,
    /**
     * 模糊半径。
     *
     * 默认 30dp —— 这是「磨砂」的门槛值：
     * 低于 24dp 时背后文字仍能辨认出形状，看起来像亚克力而不是磨砂玻璃。
     * 30dp 以上才能真正把内容柔化成色块。
     */
    blurRadius: Dp = 30.dp,
    tint: Color? = null,
    /** 覆盖默认着色强度；null 用主题默认值 */
    tintAlpha: Float? = null,
    /** 覆盖折射高度（边缘透镜区的厚度）；null 用默认 38dp */
    refractionHeightOverride: Dp? = null,
    /** 覆盖折射强度（背景被外推的像素量）；null 用默认 52dp */
    refractionAmountOverride: Dp? = null,
    /** 覆盖色散（边缘红蓝分离）；null 用主题默认值 */
    dispersionOverride: Float? = null,
    /**
     * 亮度自适应参数。
     *
     * ## 为什么需要暴露这个
     *
     * PrismalGlassSurface 内部会跑一条 **vibrancy 色彩矩阵**，
     * 默认按 Standard 档（maxBrightness = 0.22）**提亮采样结果**。
     *
     * 这在浅色主题下是好事：背景本来就亮，再提一点，玻璃显得通透有光。
     * 但在**深色主题下是灾难**：背景 RGB(69,62,114)，提亮后变成
     * RGB(173,195,229) —— 比背景亮了 100 多，
     * 底栏在深色界面上就像一块发光的白板，完全脱离整体氛围。
     *
     * 所以深色主题必须传一个"几乎不提亮"的 tuning。
     */
    adaptiveTuning: com.styropyr0.prismal.effects.PrismalAdaptiveTuning? = null,
    /**
     * 边缘高光强度。
     *
     * 高光画在**内容之上** —— 对卡片没问题（卡片内容在中间），
     * 但对底栏会盖住贴边的文字。底栏传 0 关掉即可。
     */
    highlightAlpha: Float? = null,
    onClick: (() -> Unit)? = null,
    sampleOffsetX: Float = 0.35f,
    sampleOffsetY: Float = 0.25f,
    /**
     * 是否启用**方向性光学效果**（vibrancy 提亮 + lens 边缘折射）。
     *
     * ## 为什么需要这个开关（用户反馈"模糊上面强下面弱"）
     *
     * `lens()` 的折射只作用在**边缘带**（默认 8dp）：它把面板外面的像素
     * 向内拉伸。于是面板**四周一圈**都比中间更"实"——
     * 因为边缘采到的是未被模糊的外部原始内容。
     *
     * `vibrancy()` 同样带方向性：它按亮度做非线性提亮，
     * 顶部亮、底部暗的背景会被拉出上下差异。
     *
     * 对**卡片**来说这是优点（有玻璃厚度感）；
     * 但对**底栏**这种横向长条，顶部边缘和底部边缘的"厚度"占比不同，
     * 视觉上就是"上面糊得厉害、下面却没糊"。
     *
     * 关掉后只剩 blur + tint：模糊强度在整个面板内**均匀**，
     * 这也更接近 iOS 底栏的真实观感（iOS 底栏是均匀磨砂，不做透镜）。
     */
    directionalOptics: Boolean = true,
    /**
     * 用**系统 RenderEffect** 对采样结果做模糊；null 表示不加。
     *
     * ## 为什么需要它（真机实测，别再试库内的 blur）
     *
     * 库内的 `effects { blur(r) }` 在本机**完全不生效**：
     * 把 blurRadius 从 8dp 一路调到 200dp，同一位置的像素 stdev
     * 始终在 8 附近（甚至轻微升高），背后文字一直清晰可读。
     *
     * 原因是它走 AGSL 采样管线，和 HANDOVER.md 里记录的
     * 「AGSL blurRadius 在本机不生效」是同一个限制。
     *
     * 但同一台机器上，系统的 `RenderEffect.createBlurEffect` **完全有效**。
     * 库恰好提供了 `effects.effect(...)` 这个入口，
     * 可以把一个 Compose RenderEffect 送进**采样管线内部** ——
     * 于是模糊只作用于「玻璃采到的那份画面」，
     * 屏幕上显示的内容完全不受影响。
     *
     * ## 为什么不能像之前那样挂在 graphicsLayer 上
     *
     * 那个做法会连带把**可见图层**一起糊掉（实测整屏发虚）。
     * 而 effect() 作用在采样阶段，是「只糊玻璃背后」，二者本质不同。
     */
    systemBlur: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val density = LocalDensity.current

    // ============ 采样源 ============
    //
    // 优先用新库（Kyant0/AndroidLiquidGlass）的 Backdrop；
    // 拿不到就退回旧的 Prismal 层 —— 这样弹窗等还没迁移的场景不会变空白。
    val liquid = LocalLiquidBackdrop.current

    // 折射强度：把旧的"像素外推量"映射成新库的 refractiveIndex。
    // 旧参数 refractionAmountOverride 越大 → 折射越强，
    // 新库的合理区间是 0.8 ~ 1.8，这里线性映射并夹住。
    val refractionIndex = when {
        refractionAmountOverride == null -> 1.2f
        else -> (0.8f + refractionAmountOverride.value / 60f).coerceIn(0.8f, 1.8f)
    }

    // 着色层颜色。
    //
    // 【关键】tint 的**色相**不能两种主题都用白。
    //
    // 官方 LiquidBottomTabs 是这么写的：
    //     containerColor =
    //         if (浅色) Color(0xFFFAFAFA).copy(0.4f)   // 亮白
    //         else      Color(0xFF121212).copy(0.4f)   // 近黑
    //
    // 深色主题必须用**深色** tint。用白色会得到一个致命的后果：
    // 白叠在深蓝紫的背景上 → 数学上必然趋向灰 → 底栏变成一块灰紫色，
    // 和旁边蓝青色的卡片完全不搭（用户原话"风格不统一"）。
    //
    // 深色用近黑 tint，玻璃就只压暗不泛灰，色相仍由背后内容决定，
    // 于是底栏和卡片自然落在同一个色系里。
    //
    // 浅色主题反过来：白 tint 压在浅蓝背景上只会变亮，不会偏色，安全。
    // 深色用近黑 tint，但**带一点蓝**（0x0A101E）。
    //
    // 为什么不是纯 0xFF121212：
    // 流动背景是「上蓝下紫」的渐变，底栏位于屏幕底部，采到的天然偏紫。
    // 纯中性黑叠上去不会改变色相，于是底栏保持紫调，
    // 而卡片在中部采到蓝色 —— 两者亮度接近但色相分家，看着不统一。
    //
    // 补一点蓝之后，底栏被轻轻拉回蓝青系，和卡片落在同一个色温里。
    val surfaceTint = (tint ?: if (colors.isDark) {
        Color(0xFF0A101E)
    } else {
        Color(0xFFFAFAFA)
    }).copy(
        alpha = (tintAlpha ?: 0.40f).coerceIn(0f, 1f),
    )

    if (liquid != null) {
        // ---- 新实现：库内完成 模糊 + 折射 + 高光 + 阴影 ----
        Box(
            modifier = modifier.drawBackdrop(
                backdrop = liquid,
                shape = { shape },
                effects = {
                    // 【顺序照抄官方示例】vibrancy → blur → lens
                    //
                    // 官方 LiquidBottomTabs 里就是这么写的：
                    //     effects = {
                    //         vibrancy()
                    //         blur(8f.dp.toPx())
                    //         lens(24f.dp.toPx(), 24f.dp.toPx())
                    //     }
                    //
                    // 之前我把 vibrancy 放在最后，模糊被前面的效果覆盖掉了。

                    // 提亮：让玻璃比背景亮一档
                    //
                    // 方向性开关关闭时不加 —— vibrancy 按亮度非线性提亮，
                    // 会让上亮下暗的背景产生可见的上下差。
                    if (directionalOptics) vibrancy()
                    // 模糊：这才是"看不清背后文字"的关键。
                    //
                    // 【注意】这一行在本机**不生效**（见 systemBlur 的注释），
                    // 保留它是为了在其它走 AGSL 成功的设备上仍有正确的观感；
                    // 本机真正的模糊由下面的 effect(...) 承担。
                    blur(with(density) { blurRadius.toPx() })

                    // 系统 RenderEffect 模糊：本机唯一真正生效的模糊。
                    //
                    // 作用在**采样管线内部**，只糊「玻璃背后采到的画面」，
                    // 屏幕上显示的内容不受任何影响。
                    //
                    // 调用顺序放在 blur 之后、lens 之前：
                    //   · 先糊掉细节，再让 lens 对已柔化的画面做折射，
                    //     边缘不会把外部高对比笔画整段吸进来（那正是"脏边"的来源）；
                    //   · 如果放在 lens 之后，折射会先把锐利内容拉进来再糊，
                    //     边缘会留下一圈清晰的鬼影。
                    if (systemBlur != null &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    ) {
                        val px = with(density) { systemBlur.toPx() }
                        effect(
                            android.graphics.RenderEffect
                                .createBlurEffect(
                                    px,
                                    px,
                                    android.graphics.Shader.TileMode.CLAMP,
                                )
                                .asComposeRenderEffect(),
                        )
                    }
                    // 折射：两个参数都是**像素尺寸**，不是强度系数。
                    // 官方底栏用 24dp / 24dp。
                    // 折射：两个参数都是**像素尺寸**，不是强度系数。
                    //
                    // 【为什么从 20dp 降到 8dp】
                    //
                    // 用户反馈「折射遇到深黑字体会很脏」。
                    // 放大看是：折射把面板外的高对比黑色笔画拉进边缘，
                    // 而模糊又不足以化开，于是在玻璃内部形成一块块发黑的污渍。
                    //
                    // 折射位移越小，被拉进来的内容越靠近面板内部（那里已经被模糊过），
                    // 边缘就越干净。8dp 仍有明显的透镜感，但不会把外部黑字整段吸进来。
                    // 方向性开关关闭时不加 lens。
                    //
                    // lens 只作用在边缘带，会把外部未模糊的原始内容向内拉，
                    // 于是面板四周一圈比中间更锐利 —— 横向长条（底栏）尤其明显，
                    // 表现为"上面强下面弱"。
                    if (directionalOptics) {
                        lens(
                            with(density) { 8.dp.toPx() },
                            with(density) { 8.dp.toPx() },
                        )
                    }
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = highlightAlpha ?: if (colors.isDark) 0.22f else 0.35f,
                    )
                },
                shadow = {
                    Shadow(radius = 12.dp, alpha = if (colors.isDark) 0.18f else 0.12f)
                },
                innerShadow = {
                    InnerShadow(radius = 8.dp, alpha = 0.10f)
                },
                onDrawSurface = {
                    drawRect(surfaceTint)
                },
            ),
        ) {
            content()
        }
    } else {
        // ---- 兜底：采样源还没就绪时，画一个最简的圆角底色，避免闪白 ----
        Box(
            modifier = modifier.background(
                color = surfaceTint,
                shape = shape,
            ),
        ) {
            content()
        }
    }
}

/**
 * 当前 GPU 管线等级，用于设置页的"如实告知"——不夸大效果。
 */
fun currentGlassPipelineLabel(): String =
    if (isAGSLShaderSupported()) {
        "AGSL 全管线（Android 13+）"
    } else {
        "RenderEffect 模糊（降级渲染）"
    }
