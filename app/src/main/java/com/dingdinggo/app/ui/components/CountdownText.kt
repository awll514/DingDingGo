package com.dingdinggo.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 精密等宽（`tnum`）倒计时时钟。
 *
 * 使用 SansSerif + `fontFeatureSettings = "tnum"`，兼具 iOS 锁屏数字的现代感与恒定字宽，
 * 秒数滚动切换时整行位置零抖动，上下平滑推送。
 */
@Composable
fun CountdownText(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val colors = GlassTheme.colors
    val parts = text.split(":")
    if (parts.size != 3) {
        Text(
            text = text,
            color = if (active) colors.textPrimary else colors.textTertiary,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
        return
    }

    val digitColor = if (active) colors.textPrimary else colors.textTertiary

    Row(
        modifier = modifier.clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        DigitSlot(value = parts[0], color = digitColor)
        Separator(colors.textTertiary)
        DigitSlot(value = parts[1], color = digitColor)
        Separator(colors.textTertiary)
        DigitSlot(value = parts[2], color = digitColor)
    }
}

private val CountdownFontSize = 54.sp
private val CountdownLineHeight = 76.dp
private const val DigitShiftMs = 560
private const val DigitFadeInMs = 380
private const val DigitFadeOutMs = 480

@Composable
private fun DigitSlot(
    value: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .width(74.dp)
            .height(CountdownLineHeight)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (
                    slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            DigitShiftMs,
                            easing = com.dingdinggo.app.ui.theme.IosEaseOut,
                        ),
                        initialOffsetY = { fullHeight -> fullHeight },
                    ) + fadeIn(animationSpec = GlassMotion.easeOut(DigitFadeInMs))
                ) togetherWith (
                    slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            DigitShiftMs,
                            easing = com.dingdinggo.app.ui.theme.IosEaseOut,
                        ),
                        targetOffsetY = { fullHeight -> -fullHeight },
                    ) + fadeOut(animationSpec = GlassMotion.easeOut(DigitFadeOutMs))
                )
            },
            contentAlignment = Alignment.Center,
            label = "digit",
        ) { v ->
            Text(
                text = v,
                color = color,
                style = TextStyle(
                    fontSize = CountdownFontSize,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-1.5).sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Separator(color: Color) {
    Text(
        text = ":",
        color = color.copy(alpha = 0.45f),
        fontSize = 38.sp,
        fontWeight = FontWeight.ExtraLight,
        modifier = Modifier.padding(bottom = 6.dp, start = 1.dp, end = 1.dp),
    )
}

/**
 * 首页核心倒计时液态玻璃面板内容。
 */
@Composable
fun CountdownPanelContent(
    kindLabel: String,
    countdown: String,
    targetTimeLabel: String,
    hintLabel: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
) {
    val colors = GlassTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (headerTrailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = kindLabel,
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.8.sp,
                )
                headerTrailing()
            }
        } else {
            Text(
                text = kindLabel,
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.6.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        CountdownText(
            text = countdown,
            active = active,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = targetTimeLabel,
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        )

        if (progress != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (colors.isDark) Color.White.copy(alpha = 0.10f)
                        else Color(0xFF1E3250).copy(alpha = 0.10f),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (active) colors.accent else colors.stateIdle),
                )
            }
        }

        if (hintLabel.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = hintLabel,
                color = colors.textTertiary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
