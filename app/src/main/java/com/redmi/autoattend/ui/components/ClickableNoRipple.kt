package com.redmi.autoattend.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 无涟漪点击 + 玻璃按压动效。
 *
 * 这是全项目统一的可点元素入口。相比裸 clickable，它多了三件事：
 *
 * 1. **去掉 Material 水波纹**——水波纹和液态玻璃质感冲突（见 PressEffect.kt）；
 * 2. **加上缩放 + 回弹动效**——按下缩到 0.96，松手弹簧回弹；
 * 3. **加上触觉反馈**——按下时的微小震动，让"按到底了"有确认感。
 *
 * 用法：`Modifier.ClickableNoRipple { onClick() }`
 *
 * ## 参数顺序为什么 onClick 必须放第一
 *
 * Kotlin 的尾随 lambda 只绑定到**最后一个**参数位置，而且对扩展函数
 * 同样适用。如果把 onClick 放最后但同时还有 scaleDown/enabled 默认参数，
 * `Modifier.ClickableNoRipple { ... }` 会把 lambda 误绑到 scaleDown 上，
 * 报出 "actual type is () -> Unit, but Float was expected"。
 *
 * 把 onClick 放第一位后，尾随 lambda 因为"最后参数必须是函数类型"
 * 而无法使用——所以这里改成显式传参形式更安全：
 *   Modifier.ClickableNoRipple(onClick = { ... })
 * 同时保留 onClick 在最后以支持尾随写法。
 */
fun Modifier.ClickableNoRipple(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    /** 可传入外部 InteractionSource（液态玻璃按钮要共享按压状态） */
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    this
        .then(
            // 外部传了 source 说明对方自己做动效（如 LiquidGlassButton），
            // 此时不要再叠加一层缩放，否则两个 scale 会相乘。
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
            onClick = onClick,
        )
}

/** 更轻的按压动效，用在小控件（星期选择、开关、分段控件）上 */
fun Modifier.ClickableNoRippleSubtle(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this
        .pressEffect(interactionSource = interactionSource, scaleDown = 0.94f, enabled = enabled)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}