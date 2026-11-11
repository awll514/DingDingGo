package com.redmi.autoattend.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 倒计时数字显示。
 *
 * ## 为什么必须 clipToBounds（踩过的坑）
 *
 * 用 AnimatedContent 做"数字向上滑出、新数字从下溜入"时，**过渡期间新旧两个
 * 数字是同时存在于布局里的**。如果没有裁剪边界，就会看到：
 * - 旧数字往上飘出面板顶部；
 * - 新数字从下方叠在"目标时间"那一行上；
 * - 整组数字看起来像错位/重影，甚至溢出玻璃面板边缘。
 *
 * 真机上（截图可见）表现就是 00:25: 后面跟着两个叠在一起的秒数。
 *
 * 修法有两层：
 * 1. 每个数字槽用固定尺寸的 Box + clipToBounds 裁掉溢出部分；
 * 2. 用等宽字体（Monospace）保证数字宽度恒定，避免秒位变化时整个 Row 宽度
 *    抖动导致相邻元素位移。
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
            color = colors.textPrimary,
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

/** 倒计时主字号 */
private val CountdownFontSize = 62.sp

/**
 * 每个数字槽的固定行高。
 *
 * 【真机修正】之前定 78dp，实测不够 —— Monospace 的 descent 偏大，
 * 62sp 的数字底部被 clipToBounds 切掉了一截（截图上秒位明显缺一截）。
 *
 * 重新按字体度量算：
 *   62sp × Monospace 基线比 ≈ 1.25 → 77.5sp ≈ 77.5dp（fontScale 1.0 时 sp≈dp）
 *   再留 15% 余量 → 约 90dp
 *
 * 96dp 是实测能完整容纳 62sp Monospace 数字（含小数点下伸部分）的最小安全值。
 */
private val CountdownLineHeight = 96.dp

/**
 * 单个两位数数字槽。
 *
 * 固定宽度 + 固定高度 + clipToBounds：
 * 滑动动画只能在这个盒子内发生，永远不会叠到别处或溢出面板。
 */
@Composable
private fun DigitSlot(
    value: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(CountdownLineHeight)
            // clipToBounds 是这个组件正常工作的关键：
            // AnimatedContent 过渡期间新旧数字同时在布局里，
            // 不裁剪的话旧数字会飘出槽位、压到面板其它内容上。
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (                    slideInVertically(animationSpec = tween(300)) { full -> full } +
                        fadeIn(animationSpec = tween(200))
                    ) togetherWith (                    slideOutVertically(animationSpec = tween(300)) { full -> -full } +
                        fadeOut(animationSpec = tween(160))
                    )
            },
            contentAlignment = Alignment.Center,
            label = "digit",
        ) { v ->
            Text(
                text = v,
                color = color,
                fontSize = CountdownFontSize,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
                // 等宽字体：数字宽度恒定，秒位跳动不会让整行宽度抖动
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
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
        fontSize = 42.sp,
        fontWeight = FontWeight.Light,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/**
 * 倒计时面板内容布局。
 *
 * 信息层级（从上到下）：
 *   小号 type 标签 → 超大倒计时 → 目标时刻 → 一句状态说明
 */
@Composable
fun CountdownPanelContent(
    kindLabel: String,
    countdown: String,
    targetTimeLabel: String,
    hintLabel: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = kindLabel,
            color = colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.5.sp,
        )

        Spacer(Modifier.height(14.dp))

        CountdownText(
            text = countdown,
            active = active,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = targetTimeLabel,
            color = colors.textSecondary,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp,
        )

        if (hintLabel.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hintLabel,
                color = colors.textTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}