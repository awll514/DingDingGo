package com.dingdinggo.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.dingdinggo.app.ui.theme.GlassMotion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * 统一的「点击确认」触觉反馈。
 *
 * 必须直接在 `onClick` 里触发，而不能放在 `LaunchedEffect(pressed)` 里：
 * 在可滚动容器（如首页/设置页 Column）内，快速点击会被 Compose 的 tap-slop 等待折叠成
 * 同时到达的 Press+Release，导致 `LaunchedEffect(pressed)` 错过点击、反而变成
 * 只有长按才触发震动。
 */
fun View.performClickHaptic() {
    runCatching {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/**
 * 按下反馈动效（仅负责平滑按压缩放与回弹；点击震动统一由 onClick 触发，避免长按误震）。
 */
fun Modifier.pressEffect(
    interactionSource: MutableInteractionSource,
    scaleDown: Float = 0.96f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(pressed) {
        if (!enabled) return@LaunchedEffect

        if (pressed) {
            launch {
                scale.animateTo(scaleDown, tween(GlassMotion.PRESS_DOWN, easing = com.dingdinggo.app.ui.theme.IosEaseOut))
            }
        } else {
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
