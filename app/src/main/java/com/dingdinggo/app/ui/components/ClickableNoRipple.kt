package com.dingdinggo.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/**
 * 无涟漪点击 + 玻璃按压动效 + 确定态点击震动。
 *
 * 这是全项目统一的可点元素入口：
 * 1. 去掉 Material 水波纹；
 * 2. 按下缩放 + 平滑回弹；
 * 3. 在真实 `onClick` 完成点击时立即触发清脆震动（长按无功能、绝不在按住超时时误震）。
 */
fun Modifier.ClickableNoRipple(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    /** 可传入外部 InteractionSource（液态玻璃按钮要共享按压状态） */
    interactionSource: MutableInteractionSource? = null,
    hapticOnClick: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val view = LocalView.current
    this
        .then(
            if (interactionSource == null) {
                Modifier.pressEffect(interactionSource = source, scaleDown = scaleDown, enabled = enabled)
            } else {
                Modifier
            },
        )
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
        ) {
            if (hapticOnClick) view.performClickHaptic()
            onClick()
        }
}

/** 更轻的按压动效，用在小控件（星期选择、开关、分段控件）上 */
fun Modifier.ClickableNoRippleSubtle(
    enabled: Boolean = true,
    hapticOnClick: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val view = LocalView.current
    this
        .pressEffect(interactionSource = interactionSource, scaleDown = 0.94f, enabled = enabled)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
        ) {
            if (hapticOnClick) view.performClickHaptic()
            onClick()
        }
}