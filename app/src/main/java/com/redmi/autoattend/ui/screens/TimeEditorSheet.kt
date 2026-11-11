package com.redmi.autoattend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.ui.components.ClickableNoRipple
import com.redmi.autoattend.ui.components.GlassWheelTimePicker
import com.redmi.autoattend.ui.glass.FrostedPanel
import com.redmi.autoattend.ui.glass.GlassRadius
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 时间编辑弹层。
 *
 * 用自定义 Dialog + 玻璃面板，而不是 Material 的 AlertDialog / TimePicker ——
 * 后两者都会带浅色表面和系统圆角，放进深色玻璃语境里会很突兀。
 *
 * 这里的玻璃面板是"临时出现"的，弹层消失后不再占用 AGSL 预算，
 * 因此可以放心用全管线。
 */
@Composable
fun TimeEditorSheet(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var minute by remember { mutableStateOf(initialMinute) }
    val colors = GlassTheme.colors

    // 入场动画：与 ConfirmDialog 一致（缩放 0.90→1.0 + 淡入 280ms）。
    // 苹果的 sheet 都是"弹出来"而不是"直接出现"。
    val scale = remember { androidx.compose.animation.core.Animatable(0.92f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scale.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(200))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 【不要黑底】之前是 Black 55%，整个屏幕被压成灰黑，
                // 和苹果的 sheet 体验完全相反。
                //
                // Apple 的做法（iOS 26 / macOS Tahoe）：
                // 背景**不压黑**，而是保持原样 + 轻微去饱和/降亮度，
                // 让玻璃面板自己通过折射和模糊产生层次。
                //
                // 这里用 12% 的极淡白（浅色）或 8% 的黑（深色），
                // 只做"轻微后退"，绝不压暗画面。
                .background(
                    if (colors.isDark) {
                        Color.Black.copy(alpha = 0.30f)
                    } else {
                        // 浅色主题：用极淡的冷白，模拟 iOS 的"背景微退"
                        Color(0xFF0A1220).copy(alpha = 0.10f)
                    },
                )
                .ClickableNoRipple(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            // 阻止点击穿透：内层自己消费点击
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .ClickableNoRipple { },
            ) {
                LiquidGlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            this.alpha = alpha.value
                        },
                    cornerRadius = GlassRadius.Large,
                    sampleOffsetX = 0.5f,
                    sampleOffsetY = 0.4f,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = title,
                            color = GlassTheme.colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = PunchSchedule.formatMinute(minute),
                            color = GlassTheme.colors.accent,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )

                        Spacer(Modifier.height(16.dp))

                        GlassWheelTimePicker(
                            minuteOfDay = minute,
                            onMinuteChange = { minute = it },
                            itemHeight = 46.dp,
                            visibleCount = 5,
                        )

                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ActionButton(
                                text = "取消",
                                primary = false,
                                modifier = Modifier.weight(1f),
                                onClick = onDismiss,
                            )
                            ActionButton(
                                text = "确定",
                                primary = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onConfirm(minute) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostedPanel(
        modifier = modifier
            .height(46.dp)
            .ClickableNoRipple(onClick = onClick),
        cornerRadius = GlassRadius.Pill,
        tint = if (primary) {
            GlassTheme.colors.accent.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.09f)
        },
        borderAlpha = if (primary) 0.28f else 0.12f,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (primary) GlassTheme.colors.accent else GlassTheme.colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}