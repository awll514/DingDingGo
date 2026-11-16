package com.dingdinggo.app.ui.glass

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
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 轻量磨砂玻璃容器。
 *
 * 用于列表项、设置分组与次级状态卡，以及 [GlassPerfGuard] 触发降级时的兜底表面。
 * 通过斜向光影渐层与顶部偏亮的方向性高光描边，在视觉上与 [LiquidGlassPanel] 保持同一质感谱系，
 * 同时保持极低的渲染开销。
 */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusMedium,
    contentPadding: PaddingValues = PaddingValues(GlassDimens.cardPadding),
    tint: Color? = null,
    borderAlpha: Float? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val baseTint = tint ?: colors.glassTint
    val edgeAlpha = borderAlpha ?: colors.borderAlpha

    val fillBrush = if (colors.isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1A2B4C).copy(alpha = 0.42f),
                baseTint.copy(alpha = 0.34f),
                Color(0xFF060C1A).copy(alpha = 0.48f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF6FAFF).copy(alpha = 0.52f),
                baseTint.copy(alpha = 0.34f),
                Color(0xFFE6F0FE).copy(alpha = 0.26f),
            ),
        )
    }

    val strokeBrush = if (colors.isDark) {
        Brush.verticalGradient(
            colors = listOf(
                colors.borderColor.copy(alpha = (edgeAlpha * 1.25f).coerceAtMost(0.45f)),
                colors.borderColor.copy(alpha = edgeAlpha * 0.30f),
                colors.borderColor.copy(alpha = edgeAlpha * 0.55f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.96f),
                Color.White.copy(alpha = 0.42f),
                Color(0xFF8FA6C4).copy(alpha = 0.24f),
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush = fillBrush)
            .border(width = 1.dp, brush = strokeBrush, shape = shape)
            .padding(contentPadding),
        content = content,
    )
}
