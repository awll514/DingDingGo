package com.redmi.autoattend.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redmi.autoattend.ui.theme.GlassColors
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 轻量磨砂容器。
 *
 * 这是"性能红线"的落地点（AGENTS.md UI 规格）：
 * 同时活跃的**全 AGSL 玻璃面不超过 3 个**。首页顶部倒计时、状态总览、底部 tab bar
 * 这三块才用全管线；其余列表项、设置行、按钮一律用本组件：
 *
 * - 一层低透明度着色（深色=淡白，浅色=更实的白）
 * - 一层从左上到右下的线性渐变（模拟环境光斜射）
 * - 一圈描边（深色用白色高光；浅色用白色更亮 + 外层阴影感）
 *
 * 视觉上和大玻璃面板连续，但只有一个 drawRect + 一个 stroke，GPU 成本可忽略。
 */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusMedium,
    contentPadding: PaddingValues = PaddingValues(GlassDimens.cardPadding),
    /** 覆盖底色（不传则用主题的 glassTint） */
    tint: Color? = null,
    /** 覆盖描边透明度 */
    borderAlpha: Float? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)

    val base = tint ?: colors.glassTint
    val alpha = borderAlpha ?: colors.borderAlpha

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    // 【通透性】轻量磨砂也要跟着变透，否则首页大面板是透的、
                    // 下面的健康度卡是实的，视觉上会断层。
                    // 这里把填充压到原来的 ~55%，主要靠描边勾出边界。
                    colors = if (colors.isDark) {
                        listOf(
                            base.copy(alpha = base.alpha * 0.55f),
                            base.copy(alpha = base.alpha * 0.22f),
                        )
                    } else {
                        // 浅色：再压一档。
                        // iOS Liquid Glass 的透明感来自"边缘定义 + 极淡填充"，
                        // 而不是靠一层可见的白色底。这里把填充降到 0.42/0.24，
                        // 主要靠 glassBorder 的描边勾出边界。
                        listOf(
                            base.copy(alpha = base.alpha * 0.42f),
                            base.copy(alpha = base.alpha * 0.24f),
                        )
                    },
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (colors.isDark) {
                        listOf(
                            colors.borderColor.copy(alpha = alpha),
                            colors.borderColor.copy(alpha = alpha * 0.18f),
                            colors.borderColor.copy(alpha = alpha * 0.55f),
                        )
                    } else {
                        // 浅色下白描边几乎看不见，需要更强的白 + 边缘略暗
                        listOf(
                            Color.White.copy(alpha = 0.95f),
                            Color.White.copy(alpha = 0.35f),
                            Color(0xFF9FB0C4).copy(alpha = 0.22f),
                        )
                    },
                ),
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 高光描边 Modifier：给任意容器套一圈玻璃边缘的高光。
 */
@Composable
fun Modifier.glassBorder(
    cornerRadius: Dp = GlassDimens.radiusMedium,
    alpha: Float = 0.15f,
): Modifier {
    val colors = GlassTheme.colors
    return this.border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                colors.borderColor.copy(alpha = alpha),
                colors.borderColor.copy(alpha = alpha * 0.2f),
                colors.borderColor.copy(alpha = alpha * 0.55f),
            ),
        ),
        shape = RoundedCornerShape(cornerRadius),
    )
}

/** 圆角档位（保留兼容别名，实际值统一到 GlassDimens） */
object GlassRadius {
    val Large: Dp = GlassDimens.radiusLarge
    val Medium: Dp = GlassDimens.radiusMedium
    val Small: Dp = GlassDimens.radiusSmall
    val Pill: Dp = GlassDimens.radiusPill
}