package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.redmi.autoattend.ui.theme.GlassMotion
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * 按下反馈动效。
 *
 * ## 为什么不用 Material 的 ripple
 *
 * 液态玻璃的质感和 Material 的水波纹是冲突的：
 * 水波纹是"墨水扩散"，玻璃是"实体受挤压"。在磨砂面板上加水波纹，
 * 看起来像玻璃上抹了一道油，非常违和。
 *
 * 这里用玻璃该有的物理反馈：
 * 1. **缩放**：按下去整体缩小到 0.96（弹簧回弹），像按在一块弹性玻璃上；
 * 2. **压暗**：轻微降低亮度，模拟手指遮挡；
 * 3. **触觉**：一次极轻的震动（HapticFeedbackType.TextHandleMove，最小档）。
 *
 * ## 为什么要单独抽出来
 *
 * 项目里有 20+ 个可点元素（tab、按钮、设置行、星期选择器、开关…），
 * 每一个都写一遍 remember + LaunchedEffect 会非常啰嗦且容易漏。
 * 抽成 Modifier 后，所有地方只加一行 [pressEffect] 即可。
 */
fun Modifier.pressEffect(
    interactionSource: MutableInteractionSource,
    scaleDown: Float = 0.96f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }

    LaunchedEffect(pressed) {
        if (!enabled) return@LaunchedEffect

        if (pressed) {
            // 按下：180ms 主曲线。不用 spring —— 见 GlassMotion 的说明。
            launch {
                scale.animateTo(scaleDown, tween(GlassMotion.PRESS_DOWN, easing = com.redmi.autoattend.ui.theme.IosEaseOut))
            }
            // 触觉反馈：只在按下时触发一次
            runCatching {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else {
            // 抬起：340ms 主曲线回落。
            //
            // 原先这里用 spring(DampingRatioMediumBouncy)，会产生一次可见的
            // 过冲再回弹。AGENTS.md 已明确禁用 spring：在"按下→抬起"这种
            // 有明确终点的闭环里，过冲属于多余动作，观感上更廉价。
            launch {
                scale.animateTo(1f, GlassMotion.settleSpec())
            }
        }
    }

    this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * 便捷版：内部自己创建 interactionSource。
 *
 * 适用于不关心 InteractionSource 的简单按钮。
 * 需要和 clickable 共用 source 时，用带 source 参数的重载。
 */
@Composable
fun rememberPressInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }
