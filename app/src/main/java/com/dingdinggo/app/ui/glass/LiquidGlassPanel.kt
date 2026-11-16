package com.dingdinggo.app.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 统一液态玻璃主面板（基于 Kyant0/backdrop-android + 系统 RenderEffect）。
 *
 * 光学链路顺序（经 Redmi K90 Ultra 真机特性调优）：
 * 1. `vibrancy()`（仅开启方向性光学时）：增强透射背景色彩活力
 * 2. `RenderEffect.createBlurEffect(CLAMP)`（API 31+）：真实高斯模糊采样内容
 * 3. `lens(heightPx, amountPx)`（仅开启方向性光学时）：在已模糊内容上施加干净的边缘透镜折射
 * 4. 顶部高光、外阴影、内阴影与柔和的垂直表面渐层着色
 *
 * 若 [GlassPerfGuard.shouldDegrade] 为 true 或采样源尚未就绪，自动平滑降级为 [FrostedPanel]。
 */
@Composable
fun LiquidGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusLarge,
    blurRadius: Dp = 28.dp,
    lensHeight: Dp = 10.dp,
    lensAmount: Dp = 14.dp,
    tint: Color? = null,
    tintAlpha: Float? = null,
    highlightAlpha: Float? = null,
    shadowAlpha: Float? = null,
    directionalOptics: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val density = LocalDensity.current
    val backdrop = LocalLiquidBackdrop.current

    if (backdrop == null || GlassPerfGuard.shouldDegrade) {
        FrostedPanel(
            modifier = modifier,
            cornerRadius = cornerRadius,
            contentPadding = contentPadding,
            tint = tint,
            content = content,
        )
        return
    }

    val effectiveAlpha = (tintAlpha ?: if (colors.isDark) 0.34f else 0.48f).coerceIn(0f, 0.92f)
    val baseTint = tint ?: if (colors.isDark) {
        Color(0xFF0A1428)
    } else {
        Color(0xFFF3F8FF)
    }
    val topOverlay = baseTint.copy(alpha = (effectiveAlpha * 1.12f).coerceAtMost(0.94f))
    val bottomOverlay = baseTint.copy(alpha = (effectiveAlpha * 0.78f).coerceAtLeast(0.05f))

    val resolvedHighlight = highlightAlpha ?: if (colors.isDark) 0.26f else 0.42f
    val resolvedShadow = shadowAlpha ?: if (colors.isDark) 0.22f else 0.10f

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (directionalOptics) {
                        vibrancy()
                    }
                    val blurPx = with(density) { blurRadius.toPx() }
                    if (blurPx > 0.5f) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            effect(
                                RenderEffect
                                    .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect(),
                            )
                        } else {
                            blur(blurPx)
                        }
                    }
                    if (directionalOptics && lensAmount > 0.dp && lensHeight > 0.dp) {
                        val hPx = with(density) { lensHeight.toPx() }
                        val aPx = with(density) { lensAmount.toPx() }
                        lens(
                            refractionHeight = hPx,
                            refractionAmount = aPx,
                            depthEffect = false,
                            chromaticAberration = false,
                        )
                    }
                },
                highlight = {
                    if (resolvedHighlight > 0.01f) {
                        Highlight.Default.copy(alpha = resolvedHighlight)
                    } else {
                        Highlight.Plain.copy(alpha = 0f)
                    }
                },
                shadow = {
                    if (resolvedShadow > 0.005f) {
                        Shadow(
                            radius = 16.dp,
                            alpha = resolvedShadow,
                        )
                    } else {
                        Shadow(radius = 0.dp, alpha = 0f)
                    }
                },
                innerShadow = {
                    InnerShadow(
                        radius = 8.dp,
                        alpha = if (colors.isDark) 0.12f else 0.06f,
                    )
                },
                onDrawSurface = {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(topOverlay, bottomOverlay),
                        ),
                    )
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 当前实际运行的液态玻璃管线描述，供设置页如实显示。
 */
fun currentGlassPipelineLabel(): String = when {
    GlassPerfGuard.shouldDegrade -> "轻量磨砂（已自动降级以保障流畅）"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "Backdrop RenderEffect + 边缘透镜（API ${Build.VERSION.SDK_INT}）"
    else -> "Backdrop 模糊管线"
}
