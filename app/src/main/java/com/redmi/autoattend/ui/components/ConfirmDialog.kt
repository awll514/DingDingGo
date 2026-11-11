package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 二次确认弹层（苹果风格）。
 *
 * ## 为什么要有它
 *
 * 「立即手动打卡」会真的打开钉钉、点下打卡按钮 —— 误触的代价是一次真实的考勤记录；
 * 「跳过今天」会静默跳过一整天的自动打卡 —— 误触的代价是一天漏卡。
 * 两者都不可逆，所以都需要一次明确确认。
 *
 * ## 为什么不用 Material AlertDialog
 *
 * 它会带浅色 surface + 系统圆角 + 深色文字，塞进玻璃语境里非常突兀。
 * 这里用同样的玻璃语言重画，并且：
 *
 * - 背景**不压黑**（Apple sheet 的行为），只做轻微后退；
 * - 标题在上、说明在中、按钮在下，与 iOS 的 alert 布局一致；
 * - 主按钮在右（危险/确认操作），次按钮在左，符合 iOS 习惯。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GlassTheme.colors

    // 入场动画：缩放 + 淡入（Apple 的 alert 就是这种"弹出来"的感觉）
    val scale = remember { Animatable(0.90f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(200))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 系统级真模糊：和 TimeEditorSheet 同一套方案。
        // 枚举 WindowManagerGlobal 找到 type=TYPE_APPLICATION 的弹窗窗口。
        com.redmi.autoattend.ui.glass.DialogSystemBlur(radiusDp = 26f)
        // 【关键】显式注入采样源。
        // Dialog 继承了父级的 LocalBackdropLayer（只含流动背景），
        // 采样它只能得到一片渐变，没有页面内容 —— 看起来像塑料板。
        // 改用 contentLayer（背景+卡片+文字），玻璃后才能透出真实内容。
        androidx.compose.runtime.CompositionLocalProvider(
            com.redmi.autoattend.ui.glass.LocalDialogBackdrop provides
                com.redmi.autoattend.ui.glass.SharedBackdrop.contentLayer,
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 不压黑：只做极轻的背景后退，符合 Apple sheet 的观感
                // 系统模糊已提供层次，不再叠自绘蒙版。
                .background(Color.Transparent)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                    },
            ) {
                LiquidGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = GlassDimens.radiusLarge,
                    // 与时间弹层同一套材质：iOS 26 磨砂玻璃窗。
                    // 模糊 52dp 把背景糊成色块，tint 0.045 给一层中性底。
                    tint = Color(0x0BF2F5FA),
                    tintAlpha = 0.045f,
                    blurRadius = 52.dp,
                    refractionHeightOverride = 40.dp,
                    refractionAmountOverride = 58.dp,
                    dispersionOverride = 0.15f,
                    sampleOffsetX = 0.5f,
                    sampleOffsetY = 0.5f,
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                    ) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = message,
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 用原生 clickable 而不是 LiquidGlassButton。
                            //
                            // 【真机血案】LiquidGlassButton 走的是 ClickableNoRipple，
                            // 而后者用 composed{} 包了 pressEffect + clickable。
                            // 放在 Dialog 里时，首次组合会**幽灵触发一次 onClick** ——
                            // 表现就是打开应用什么都没点，"跳过今天"却被写进了配置。
                            // 用最朴素的 clickable 就完全没有这个问题。
                            DialogButton(
                                text = dismissText,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                onClick = onDismiss,
                            )
                            DialogButton(
                                text = confirmText,
                                color = if (danger) colors.error else colors.accent,
                                bold = true,
                                modifier = Modifier.weight(1f),
                                onClick = onConfirm,
                            )
                        }
                    }
                }
            }
        }
        }   // CompositionLocalProvider
    }
}

/**
 * 弹层里的按钮。
 *
 * 刻意**不用** LiquidGlassButton —— 见上面的注释：
 * 它在 Dialog 里会幽灵触发 onClick，导致"没点也执行"。
 * 这里用最朴素的 clickable + 玻璃描边，行为可预测。
 */
@Composable
private fun DialogButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(999.dp)

    // 按压缩放：纯本地状态，不依赖 composed 或 InteractionSource 的副作用
    var pressed by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            scale.animateTo(0.96f, tween(90))
        } else {
            scale.animateTo(1f, tween(140))
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            .background(color.copy(alpha = if (colors.isDark) 0.16f else 0.14f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (colors.isDark) 0.28f else 0.75f),
                        Color.White.copy(alpha = 0.10f),
                    ),
                ),
                shape = shape,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.5.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
