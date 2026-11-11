package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.redmi.autoattend.ui.theme.GlassMotion
import com.redmi.autoattend.ui.theme.IosEaseOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redmi.autoattend.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * 液态玻璃按钮。
 *
 * ## 与普通按钮的区别（对应你说的"按钮动效也必须用液态玻璃"）
 *
 * 普通按钮按下去只是缩放 + 变暗。液态玻璃按钮应该有**材质层面的反应**：
 * 这是 Apple 在 iOS 26 给 `.interactive()` 玻璃定的行为 ——
 * 触摸时材质本身发生光学变化，而不只是几何变换。
 *
 * 这里实现了四层反馈（全部围绕"玻璃被按压"的物理直觉）：
 *
 * 1. **缩放**：0.97，配合 spring 回弹；
 * 2. **高光偏移**：按压时镜面高光从顶部滑向中心（模拟手指遮挡光源）；
 * 3. **折射增强**：边缘透镜带的亮度轻微提升（玻璃被压薄，透光更多）；
 * 4. **触觉**：一次极轻的震动。
 *
 * ## 为什么不用 PrismalGlassSurface
 *
 * 玻璃面有性能红线（同时活跃 ≤3 块）。按钮可能有多个，
 * 每个都开 AGSL 面会直接把 GPU 打满。
 * 所以这里用**渐变 + 描边模拟**玻璃，成本几乎为零，
 * 但动效语义完全对齐真实玻璃。
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 999.dp,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val haptics = LocalHapticFeedback.current

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 四层可动画量
    val scale = remember { Animatable(1f) }
    val highlightShift = remember { Animatable(0f) }  // 0=顶部高光, 1=滑到中心
    val refractionGlow = remember { Animatable(0f) }  // 边缘透镜亮度

    LaunchedEffect(pressed) {
        if (!enabled) return@LaunchedEffect

        if (pressed) {
            // 按下：180ms（AGENTS.md 的"按下放大"档），保持跟手
            launch { scale.animateTo(0.97f, tween(GlassMotion.PRESS_DOWN, easing = IosEaseOut)) }
            launch { highlightShift.animateTo(1f, tween(GlassMotion.PRESS_DOWN, easing = IosEaseOut)) }
            launch { refractionGlow.animateTo(1f, tween(GlassMotion.PRESS_DOWN, easing = IosEaseOut)) }
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        } else {
            // 松手：340ms（"松手回落"档）。
            //
            // 这里原来是 spring(DampingRatioMediumBouncy)，与 AGENTS.md
            // "全应用禁用 spring" 冲突，且和 PressEffect 的表现不一致
            // （同一个"按下回弹"在两个组件里观感不同）。
            // 现在两者共用 GlassMotion.settleSpec()。
            launch { scale.animateTo(1f, GlassMotion.settleSpec()) }
            launch { highlightShift.animateTo(0f, tween(GlassMotion.PRESS_UP, easing = IosEaseOut)) }
            launch { refractionGlow.animateTo(0f, tween(GlassMotion.PRESS_UP, easing = IosEaseOut)) }
        }
    }

    val baseTint = tint ?: colors.accent

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            // 玻璃底：极淡着色 + 斜向渐变
            .background(
                Brush.linearGradient(
                    listOf(
                        baseTint.copy(alpha = if (colors.isDark) 0.16f else 0.20f),
                        baseTint.copy(alpha = if (colors.isDark) 0.08f else 0.10f),
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            // 镜面高光：按压时整条渐变向下滑（高光中心从顶部移向中心）
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.30f * (1f - highlightShift.value) + 0.10f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.02f),
                    ),
                ),
            )
            // 折射边缘：按压时变亮（玻璃被压薄）
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(
                            alpha = (if (colors.isDark) 0.34f else 0.85f) +
                                0.20f * refractionGlow.value,
                        ),
                        Color.White.copy(alpha = 0.10f),
                    ),
                ),
                shape = shape,
            )
            .then(
                if (enabled) {
                    Modifier.ClickableNoRipple(onClick = onClick, interactionSource = interactionSource)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            content = content,
        )
    }
}
