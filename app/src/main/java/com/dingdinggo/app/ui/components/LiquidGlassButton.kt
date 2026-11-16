package com.dingdinggo.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dingdinggo.app.ui.glass.LiquidGlassPanel
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * 平面通透的液态玻璃按钮（无 3D 凸起阴影，真实采样背后极光背景）。
 *
 * 按压过程仅改变微幅缩放与表面轻亮过渡，无重组；点击震动直接在 `onClick` 发出，避免长按误震。
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = GlassDimens.radiusPill,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val view = LocalView.current

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale = remember { Animatable(1f) }
    val pressProgress = remember { Animatable(0f) }

    LaunchedEffect(pressed, enabled) {
        if (!enabled) {
            scale.snapTo(1f)
            pressProgress.snapTo(0f)
            return@LaunchedEffect
        }
        if (pressed) {
            launch { scale.animateTo(0.975f, GlassMotion.easeOut(GlassMotion.PRESS_DOWN)) }
            launch { pressProgress.animateTo(1f, GlassMotion.easeOut(GlassMotion.PRESS_DOWN)) }
        } else {
            launch { scale.animateTo(1f, GlassMotion.settleSpec()) }
            launch { pressProgress.animateTo(0f, GlassMotion.easeOut(GlassMotion.PRESS_UP)) }
        }
    }

    val baseTint = tint ?: colors.accent
    val disabledAlpha = if (enabled) 1f else 0.52f
    val glassSurfaceTint = if (colors.isDark) {
        Color(0xFF0E2142)
    } else {
        Color(0xFFEAF3FF)
    }

    LiquidGlassPanel(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = disabledAlpha
            }
            .clip(shape)
            .drawBehind {
                val p = pressProgress.value
                val radiusPx = if (cornerRadius >= 500.dp) size.minDimension / 2f else cornerRadius.toPx()
                val cr = CornerRadius(radiusPx, radiusPx)
                val accentWashAlpha = (if (colors.isDark) 0.18f else 0.14f) + 0.08f * p
                drawRoundRect(
                    color = baseTint.copy(alpha = accentWashAlpha),
                    cornerRadius = cr,
                )
                drawRoundRect(
                    color = baseTint.copy(alpha = if (colors.isDark) 0.34f else 0.28f),
                    cornerRadius = cr,
                    style = Stroke(width = 0.85.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
            ) {
                view.performClickHaptic()
                onClick()
            },
        cornerRadius = cornerRadius,
        blurRadius = 24.dp,
        lensHeight = 4.dp,
        lensAmount = 6.dp,
        tint = glassSurfaceTint,
        tintAlpha = if (colors.isDark) 0.34f else 0.44f,
        highlightAlpha = if (colors.isDark) 0.16f else 0.24f,
        shadowAlpha = 0f,
        directionalOptics = false,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
