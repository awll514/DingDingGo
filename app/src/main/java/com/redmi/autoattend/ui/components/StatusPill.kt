package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 状态徽标。
 *
 * 语义色：
 * - 未到时间：中性灰
 * - 等待执行：琥珀（有脉冲）
 * - 执行中：青绿（脉冲更快）
 * - 成功：绿
 * - 失败：红
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = color, pulsing = pulsing)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        )
    }
}

/**
 * 带脉冲的小圆点。
 *
 * 脉冲用 scale 而不是 alpha——胶囊背景本身有透明度，叠 alpha 会发灰。
 */
@Composable
fun PulsingDot(
    color: Color,
    pulsing: Boolean,
    size: Int = 7,
) {
    val scale = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        val s by transition.animateFloat(
            initialValue = 0.75f,
            targetValue = 1.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scale",
        )
        s
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0.7f)),
                ),
                shape = CircleShape,
            )
            .scale(scale),
    )
}

/**
 * 今日打卡的单行状态。
 *
 * 布局：左侧色条（状态指示） → 标签 + 状态字 → 右侧实际时间
 *
 * 时间是这一行的视觉重点，用大号细体；标签用常规字重，形成对比。
 */
@Composable
fun PunchStatusRow(
    label: String,
    doneTime: String?,
    done: Boolean,
    modifier: Modifier = Modifier,
    onColor: Color? = null,
) {
    val colors = GlassTheme.colors
    val accent = onColor ?: colors.success

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态指示条
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(
                    if (done) accent.copy(alpha = 0.9f) else colors.textTertiary.copy(alpha = 0.22f)
                ),
        )

        Spacer(Modifier.width(13.dp))

        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (done) "已打卡" else "未打卡",
                color = if (done) accent else colors.textTertiary,
                fontSize = 11.sp,
                fontWeight = if (done) FontWeight.Medium else FontWeight.Normal,
            )
        }

        Text(
            text = doneTime ?: "--:--",
            color = if (done) colors.textPrimary else colors.textTertiary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (-0.5).sp,
        )
    }
}