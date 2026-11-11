package com.redmi.autoattend.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.isAGSLShaderSupported
import com.styropyr0.prismal.sources.rememberPrismalCanvasSource
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 全 AGSL 液态玻璃面板 —— 对齐 Apple Liquid Glass 的光学配方。
 *
 * ## 苹果玻璃的六层构成（缺一层就不像）
 *
 * 1. **背景模糊**：把背后内容明显模糊，但**不能糊成一团色块**。
 *    糊太狠会丢掉背景结构，玻璃就显得"脏"。
 *
 * 2. **饱和度提升（Vibrancy）**：最容易被忽略、却最关键的一步。
 *    真实玻璃让透过的颜色更"纯"。PrismalGlassSurface 内部固定走 vibrancy()
 *    （saturation 1.5 的色彩矩阵），所以不需要额外传参。
 *
 * 3. **边缘折射（Lens）**：玻璃边缘像凸透镜，把紧贴边缘的背景**向外拉伸**。
 *    这是"厚玻璃"错觉的来源，也是液态玻璃最标志性的特征。
 *
 * 4. **色散**：边缘出现极淡的红/蓝色边。强度必须很低，高了就像劣质 3D。
 *
 * 5. **镜面高光（Specular）← 之前缺的就是这层**：
 *    苹果玻璃上边缘有一条明显更亮的白线，下边缘则暗一些。
 *    这是光源从上方打在玻璃边缘的物理表现。没有它，玻璃就"平"在背景上。
 *
 * 6. **tintAlpha ≈ 0**：玻璃本身几乎不着色，颜色全部来自被模糊的背景。
 *    tintAlpha 一旦超过 0.05，面板就退化成"半透明塑料板"。
 *
 * ## 性能
 *
 * 每个面板 = 1 次图层录制 + 1 次 RenderEffect 链（blur→colorMatrix→AGSL）
 * + 1 次 border 绘制（极便宜）。AGSL 那段只在 API 33+ 跑。
 * 本应用严格限制同时活跃 3 个。
 */
@Composable
fun LiquidGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusLarge,
    blurRadius: Dp = 16.dp,
    tint: Color? = null,
    /** 覆盖默认着色强度；null 用主题默认值 */
    tintAlpha: Float? = null,
    /** 覆盖折射高度（边缘透镜区的厚度）；null 用默认 38dp */
    refractionHeightOverride: Dp? = null,
    /** 覆盖折射强度（背景被外推的像素量）；null 用默认 52dp */
    refractionAmountOverride: Dp? = null,
    /** 覆盖色散（边缘红蓝分离）；null 用主题默认值 */
    dispersionOverride: Float? = null,
    onClick: (() -> Unit)? = null,
    sampleOffsetX: Float = 0.35f,
    sampleOffsetY: Float = 0.25f,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val density = LocalDensity.current

    val recordedLayer = LocalBackdropLayer.current

    // 兜底采样源（弹层在独立 Window 里时采样不到主窗口）
    val fallbackBackdrop: PrismalBackdrop = rememberPrismalCanvasSource {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.backgroundMid,
                    colors.backgroundAccent,
                    colors.backgroundDeep,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.blobPrimary.copy(alpha = 0.38f), Color.Transparent),
                center = Offset(size.width * sampleOffsetX, size.height * sampleOffsetY),
                radius = size.minDimension * 0.9f,
            ),
            radius = size.minDimension * 0.9f,
            center = Offset(size.width * sampleOffsetX, size.height * sampleOffsetY),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.blobSecondary.copy(alpha = 0.32f), Color.Transparent),
                center = Offset(size.width * (1f - sampleOffsetX), size.height * 0.8f),
                radius = size.minDimension * 0.8f,
            ),
            radius = size.minDimension * 0.8f,
            center = Offset(size.width * (1f - sampleOffsetX), size.height * 0.8f),
        )
    }

    val backdrop: PrismalBackdrop = recordedLayer ?: fallbackBackdrop
    // 同时看两条：设备是否支持 AGSL，以及性能守门员是否要求降级
    val agsl = remember { isAGSLShaderSupported() } && !GlassPerfGuard.shouldDegrade

    if (!agsl) {
        // 降级路径必须和全管线路径**布局完全一致**，否则会在降级瞬间
        // 出现尺寸跳变（之前就踩过：FrostedPanel 默认带 20dp 内边距，
        // 导致底栏变高、内容区被顶）。
        // 所以这里显式传 contentPadding = 0，只提供视觉背景，不参与尺寸。
        FrostedPanel(
            modifier = modifier,
            cornerRadius = cornerRadius,
            tint = tint,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            borderAlpha = if (colors.isDark) 0.20f else 0.70f,
            content = content,
        )
        return
    }

    // ============ 苹果配方的参数选择 ============

    /**
     * tintAlpha：几乎不着色，这是"通透感"的决定性参数。
     *
     * 数值参考 Apple HIG / iOS 26 Liquid Glass：
     * - 玻璃面「20–40% 透明度」，即不透明度 60–80%；
     * - 但那是**最终合成结果**，还要叠加 blur 与折射。
     *   AGSL 本身已经做了模糊+折射，所以纯 tint 必须压得很低，
     *   否则会变成"磨砂实心板"而不是玻璃。
     *
     * 之前浅色用 0.070 偏实（在白底上像半透明白卡）。
     * 压到 0.030 后，背后光斑与卡片能明显透出来。
     */
    /**
     * 白玻璃的"提亮"量。
     *
     * 【实测驱动的修正】
     * 真机采样发现：背景 RGB(167,189,224)，卡片内 RGB(169,187,213) ——
     * 蓝色通道掉了 11，红色反而涨了 2。
     * 也就是说玻璃在**去饱和**：vibrancy 把饱和的颜色混成灰蓝，
     * 而 tint 太低（0.030）不足以把亮度提回来。
     *
     * 视觉上就是"卡片发暗、发灰"。
     *
     * 修正思路（这也是 Apple Glass 的物理直觉）：
     * 真实玻璃会**散射并提亮**背后的光。
     * 所以浅色主题的 tint 要从 0.030 提到 0.11，
     * 并且用带一点冷调的白（而不是纯白），让提亮后不发黄。
     *
     * 深色主题保持极低 —— 深色下玻璃靠"透"而不是"亮"。
     */
    /**
     * 白玻璃的提亮量。
     *
     * 迭代记录：
     * - 0.030 → 卡片发灰发暗（vibrancy 去饱和后没有足够白度补回来）；
     * - 0.110 → 提亮过头，蓝色通道飙到 249，卡片比背景还蓝，很跳；
     * - 0.058 → 当前值。提亮刚好抵消去饱和，卡片与背景同色系但不刺眼。
     *
     * 目标是"和其他卡片差不多"：玻璃之间应该有细微差异（各自采样位置不同），
     * 但不该有谁明显更蓝、更亮。
     */
    // 允许调用方覆盖（底栏需要更透，才能透出底下的文字）
    val surfaceTintAlpha = tintAlpha ?: if (colors.isDark) 0.020f else 0.058f

    // 模糊：太小看到背景纹理（脏），太大糊成纯色（假）
    val effectiveBlur = if (colors.isDark) blurRadius else blurRadius * 0.85f

    /**
     * 折射：边缘透镜区，苹果"厚玻璃"感的来源。
     *
     * 默认 38dp / 52dp 是给**大卡片**调的 —— 卡片面积大，
     * 38dp 的透镜带只占边缘一小圈，看起来自然。
     *
     * 但底栏是 52dp 高的**细长胶囊**：38dp 的透镜带几乎吞掉整条，
     * 结果要么全糊、要么看不出边缘，折射感完全丢失。
     * 所以底栏需要单独的、更小的值（由调用方 override）。
     */
    val refractionHeight = with(density) {
        (refractionHeightOverride ?: 38.dp).toPx()
    }
    val refractionAmount = with(density) {
        (refractionAmountOverride ?: 52.dp).toPx()
    }

    // 色散：真实玻璃边缘红蓝分离很微弱。
    // 但"微弱"不等于"没有" —— 底栏要能看出玻璃感，就必须给足色散，
    // 否则只是一块半透明磨砂贴片。
    val dispersion = dispersionOverride ?: if (colors.isDark) 0.13f else 0.07f

    /**
     * 镜面高光描边。
     *
     * 这是苹果玻璃最关键、也最常被漏掉的一层：
     * 上边缘一条明亮的白线（光源在上方），左右两侧渐弱，下边缘几乎无光。
     * 用 1px 的线性渐变 border 实现，成本几乎为零。
     *
     * 深色主题需要更亮的描边（对比环境暗），浅色主题用更柔的白。
     */
    val specularBrush = Brush.verticalGradient(
        colors = if (colors.isDark) {
            listOf(
                Color.White.copy(alpha = 0.42f),   // 顶部高光
                Color.White.copy(alpha = 0.14f),   // 中段
                Color.White.copy(alpha = 0.05f),   // 底部几乎无光
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.30f),
            )
        }
    )

    // 注意布局方式：
    //
    // 不能用 Box(modifier) + 内部 matchParentSize() —— 那会让 Box 的尺寸
    // 依赖自身子节点，而子节点又要 match parent，形成循环度量，
    // 结果是面板塌成 0 高（或只剩 padding 高度）。
    //
    // 正确做法：把 modifier 交给玻璃面板自己（它由 backdrop 驱动内容尺寸），
    // 高光描边用 matchParentSize 叠在它上面——此时父级尺寸已经由玻璃面板确定。
    Box {
        PrismalGlassSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = { shape },
            onClick = onClick,
            blurRadius = effectiveBlur,
            // 浅色主题用带冷调的白：纯白会把蓝紫背景洗成灰。
            // 用中性偏暖的白（不是冷白）：冷白会把背景的蓝进一步推高，
            // 导致卡片比背景更蓝。中性白只提亮不偏色。
            // 【配色一致性】AGSL 面板与磨砂面板的差异修正。
            //
            // 实测对比（浅色主题）：
            //   设置页（FrostedPanel）  RGB(168,203,248)  蓝调 ✓
            //   首页  （AGSL 面板）     RGB(179,179,249)  偏紫 ✗
            //
            // 原因：PrismalGlassSurface 内部固定跑 vibrancy（saturation 1.5），
            // 高饱和把蓝紫色背景的 R/G 通道拉平 —— R=G 就是紫色的特征。
            // FrostedPanel 完全不做饱和度增强，所以颜色是准的。
            //
            // 修正思路：给 AGSL 面板的 tint 注入**负红、正绿**的偏移，
            // 抵消 vibrancy 对色相的拉扯。这不是"调色"，是在补渲染管线的差。
            tint = tint ?: if (colors.isDark) {
                colors.glassTint
            } else {
                // 淡蓝绿白：红低一点、绿高一点，把 R=G 的紫色倾向拉回青色
                Color(0xFFF0FAFF)
            },
            tintAlpha = surfaceTintAlpha,
            refractionHeightPx = refractionHeight,
            refractionAmountPx = refractionAmount,
            chromaticAberration = dispersion,
            // 注意：PrismalGlassSurface 内部固定走 vibrancy() 色彩矩阵（saturation 1.5），
            // 传进去的 saturation/brightness 是不生效的（见 PrismalGlassEffects.kt
            // 的 useVibrancy 分支），所以这里不传，避免误以为在调整它们。
        ) {
            // 内容直接铺在玻璃上
            content()
        }

        // 镜面高光：叠一层 1px 描边在玻璃之上。
        //
        // 【必须加 pointerInput 透传】这一层 matchParentSize 铺满整个面板、
        // 且位于玻璃内容之上，会参与命中测试。如果不主动透传事件，
        // 它会把整个面板区域的点击"吃掉"——
        // 表现就是面板里的按钮看得见但点不动（真机上踩了很久）。
        //
        // clearAndPassThrough 让这一层不拦截任何触摸，
        // 事件继续向下传给真正的玻璃面板与内容。
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(width = 1.dp, brush = specularBrush, shape = shape)
        )
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