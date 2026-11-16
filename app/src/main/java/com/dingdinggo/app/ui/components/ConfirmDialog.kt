package com.dingdinggo.app.ui.components

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
import androidx.compose.runtime.remember
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
 * 液态玻璃确认弹窗。
 *
 * 优先挂载在 [com.dingdinggo.app.ui.glass.GlassModalHostLayer] 中：
 * - 背景模糊铺满状态栏与导航栏全屏，无顶部状态栏未模糊造成的割裂感；
 * - 弹层主体通过同窗口 `modalSceneBackdrop` 对背后页面做真实模糊 + 边缘 `lens` 折射；
 * - 外部独立调用时平滑回退到带 [DialogSystemBlur] 的 `decorFitsSystemWindows = false` 窗口。
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
    val attached = rememberAttachToGlassModalHost {
        GlassModalScrimBox(
            onDismiss = onDismiss,
        ) {
            ConfirmDialogCard(
                title = title,
                message = message,
                confirmText = confirmText,
                dismissText = dismissText,
                danger = danger,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
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
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConfirmDialogCard(
                    title = title,
                    message = message,
                    confirmText = confirmText,
                    dismissText = dismissText,
                    danger = danger,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialogCard(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val scale = remember { Animatable(0.93f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, GlassMotion.easeOut(GlassMotion.SHEET_ENTER)) }
        launch { alpha.animateTo(1f, GlassMotion.easeOut(220)) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
    ) {
        LiquidGlassPanel(
            modifier = Modifier.fillMaxWidth(),
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
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = message,
                    color = colors.textSecondary,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )

                Spacer(Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DialogActionButton(
                        text = dismissText,
                        accentColor = null,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    DialogActionButton(
                        text = confirmText,
                        accentColor = if (danger) colors.error else colors.accent,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogActionButton(
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
