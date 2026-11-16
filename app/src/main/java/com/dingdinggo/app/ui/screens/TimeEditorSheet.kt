package com.dingdinggo.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.ui.components.ClickableNoRipple
import com.dingdinggo.app.ui.components.GlassWheelTimePicker
import com.dingdinggo.app.ui.glass.DialogSystemBlur
import com.dingdinggo.app.ui.glass.GlassModalScrimBox
import com.dingdinggo.app.ui.glass.LiquidGlassPanel
import com.dingdinggo.app.ui.glass.LocalLiquidBackdrop
import com.dingdinggo.app.ui.glass.SharedBackdrop
import com.dingdinggo.app.ui.glass.rememberAttachToGlassModalHost
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * 液态玻璃时间编辑弹层。
 *
 * 采用自研玻璃双轮选择器，禁用系统 TimePicker；
 * 优先挂载在同窗口顶层宿主，实现包含状态栏在内的全屏背景模糊与真实边缘透镜折射。
 */
@Composable
fun TimeEditorSheet(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val attached = rememberAttachToGlassModalHost {
        GlassModalScrimBox(
            onDismiss = onDismiss,
        ) {
            TimeEditorSheetCard(
                title = title,
                initialMinute = initialMinute,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
    }
    if (attached) return

    val colors = GlassTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogSystemBlur(radiusDp = 28f)
        CompositionLocalProvider(
            LocalLiquidBackdrop provides SharedBackdrop.sceneBackdrop,
        ) {
            val scrimColor = if (colors.isDark) {
                Color.Black.copy(alpha = 0.24f)
            } else {
                Color(0xFF0A1424).copy(alpha = 0.06f)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                TimeEditorSheetCard(
                    title = title,
                    initialMinute = initialMinute,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun TimeEditorSheetCard(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 仅在子组件中读取 selectedMinute，滑动滚轮期间外层液态玻璃面板零重组
    val selectedMinute = remember(initialMinute) { mutableIntStateOf(initialMinute) }
    val colors = GlassTheme.colors
    val density = LocalDensity.current.density

    val scale = remember { Animatable(0.93f) }
    val alpha = remember { Animatable(0f) }
    val slideY = remember { Animatable(22f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, GlassMotion.easeOut(GlassMotion.SHEET_ENTER)) }
        launch { alpha.animateTo(1f, GlassMotion.easeOut(220)) }
        launch { slideY.animateTo(0f, GlassMotion.easeOut(GlassMotion.SHEET_ENTER)) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        LiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                    translationY = slideY.value * density
                },
            cornerRadius = GlassDimens.radiusLarge,
            blurRadius = 32.dp,
            lensHeight = 14.dp,
            lensAmount = 22.dp,
            tint = if (colors.isDark) Color(0xFF0D1B35) else Color(0xFFF6FAFF),
            tintAlpha = if (colors.isDark) 0.30f else 0.28f,
            highlightAlpha = if (colors.isDark) 0.28f else 0.46f,
            shadowAlpha = 0f,
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(4.dp))

                SelectedMinuteReadout(
                    minuteProvider = { selectedMinute.intValue },
                    color = colors.accent,
                )

                Spacer(Modifier.height(16.dp))

                GlassWheelTimePicker(
                    minuteOfDay = initialMinute,
                    onMinuteChange = { selectedMinute.intValue = it },
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SheetActionButton(
                        text = "取消",
                        accentColor = null,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    SheetActionButton(
                        text = "保存",
                        accentColor = colors.accent,
                        onClick = { onConfirm(selectedMinute.intValue) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedMinuteReadout(
    minuteProvider: () -> Int,
    color: Color,
) {
    Text(
        text = "已选 " + PunchSchedule.formatMinute(minuteProvider()),
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SheetActionButton(
    text: String,
    accentColor: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(GlassDimens.radiusPill)
    val bg = when {
        accentColor != null -> accentColor.copy(alpha = if (colors.isDark) 0.20f else 0.12f)
        colors.isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.38f)
    }
    val stroke = when {
        accentColor != null -> accentColor.copy(alpha = if (colors.isDark) 0.32f else 0.24f)
        colors.isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color(0xFF2B3A4A).copy(alpha = 0.10f)
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(0.75.dp, stroke, shape)
            .ClickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accentColor ?: colors.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (accentColor != null) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
