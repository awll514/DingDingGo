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
    // iOS 18 标准：慢速非线性。FastOutSlowInEasing 偏"急停"，
    // 这里换成 Apple 常用的 cubic-bezier(0.32, 0.72, 0, 1)。
    val iosEaseOut = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            androidx.compose.animation.core.tween(360, easing = iosEaseOut),
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        alpha.animateTo(
            1f,
            androidx.compose.animation.core.tween(240, easing = iosEaseOut),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 【系统级真模糊】这是 iOS sheet 的真实做法：
        // 由 SurfaceFlinger 在合成阶段模糊弹窗背后的整个界面。
        // AGSL 在这里不起作用（主界面被遮挡后录制层不再更新），
        // 所以必须走系统管线。
        com.redmi.autoattend.ui.glass.DialogSystemBlur(radiusDp = 26f)
        // 【关键】弹窗必须显式传采样源。
        //
        // Dialog 是 subcomposition，会继承父级 LocalBackdropLayer，
        // 但那层只录了流动背景（纯光斑），没有页面内容 ——
        // 弹窗玻璃采样它就只剩一片蓝色渐变，没有任何质感。
        //
        // 这里改用 SharedBackdrop.contentLayer（背景+卡片+文字）。
        // 弹窗自己不在内容层里，采样它**不会**自我引用。
        androidx.compose.runtime.CompositionLocalProvider(
            com.redmi.autoattend.ui.glass.LocalDialogBackdrop provides
                com.redmi.autoattend.ui.glass.SharedBackdrop.contentLayer,
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
                // 【不要再自绘遮罩】
                //
                // 之前这里铺了一层半透明色，把系统模糊的效果盖住了。
                // iOS sheet 的背景是**系统模糊 + 极淡的纱**，
                // 不是纯色蒙版。现在遮罩透明，让 SurfaceFlinger 的
                // 模糊结果直接透出，再叠一层极轻的纱即可。
                // 系统模糊已经提供了层次，这里不再叠自绘蒙版。
                // 之前那层 0.04~0.12 的色把模糊结果又压暗了一次。
                .background(Color.Transparent)
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
                    // ============ iOS 26 磨砂玻璃窗 ============
                    //
                    // 目标是 Apple 的 sheet：背景内容**完全柔化成色块**，
                    // 只留得下"大概是什么颜色"，文字绝不可辨认；
                    // 而弹窗自己的文字清晰锐利、对比充足。
                    //
                    // ## 两次调错的方向
                    //
                    // 第一次（tint 0.058 + 白描边 0.95）：整片发白，像蒙雾。
                    //   原因：浅色背景被白 tint 继续提亮，把内容洗掉。
                    //
                    // 第二次（tint 0.012 + 模糊 32dp）：透过头且发暗。
                    //   原因：模糊不够大，背后文字没糊成色块，
                    //   两层文字打架；同时描边被压太狠，整个面板失去存在感。
                    //
                    // ## 正确配方
                    //
                    //   1. 模糊 52dp —— 文字必须糊到不可辨认，只剩色块；
                    //   2. tint 用**中性灰白**（不是纯白也不是深灰），
                    //      alpha 0.045 —— 给玻璃一层自己的"底"，
                    //      既不提亮（纯白会洗掉内容）也不压暗（深灰会显脏）；
                    //   3. 描边保持中等强度，靠它定义"厚度"。
                    // 系统模糊已经提供了真正的背景虚化，
                    // 面板自身只需要一层薄材质来定形与提亮，
                    // 不再是"既要模糊又要着色"的双重负担。
                    tint = Color(0x12F8FAFD),
                    tintAlpha = 0.055f,
                    blurRadius = 12.dp,
                    refractionHeightOverride = 46.dp,
                    refractionAmountOverride = 64.dp,
                    dispersionOverride = 0.16f,
                    sampleOffsetX = 0.5f,
                    sampleOffsetY = 0.5f,
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
        }   // CompositionLocalProvider
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