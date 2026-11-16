package com.dingdinggo.app.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.dingdinggo.app.ui.theme.GlassColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全屏流动极光背景（液态玻璃的底层光学光源）。
 *
 * 使用平滑的正弦/余弦简谐漂移替代锯齿状折返，并节流在约 24fps：
 * 既保持丝滑极光流动感，又避免高频重录 GraphicsLayer 浪费 GPU。
 * 相位状态仅在 [Canvas] 的 Draw 阶段读取，刷新时不触发 Compose 重组。
 */
@Composable
fun FlowingBackground(
    colors: GlassColors,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    var phaseRad by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        var lastUpdateMs = -FRAME_INTERVAL_MS

        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - startNanos) / 1_000_000L
                if (elapsedMs - lastUpdateMs >= FRAME_INTERVAL_MS) {
                    lastUpdateMs = elapsedMs
                    // 连续弧度，各光斑按不同无理数倍频异步运行
                    phaseRad = (elapsedMs / 1000f) * 0.24f
                }
            }
        }
    }

    val blobGain = if (colors.isDark) 1.0f else 1.52f

    Canvas(modifier = modifier.fillMaxSize()) {
        drawAuroraFrame(colors = colors, phase = phaseRad, blobGain = blobGain)
    }
}

private const val FRAME_INTERVAL_MS = 42L

private fun DrawScope.drawAuroraFrame(
    colors: GlassColors,
    phase: Float,
    blobGain: Float,
) {
    // 1. 深空冷蓝底色对角线渐变
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                colors.backgroundDeep,
                colors.backgroundMid,
                colors.backgroundAccent,
                colors.backgroundDeep,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        size = size,
    )

    val p1 = 0.5f + 0.5f * sin(phase)
    val p2 = 0.5f + 0.5f * cos(phase * 0.77f + 0.9f)
    val p3 = 0.5f + 0.5f * sin(phase * 0.59f + 2.1f)
    val breathe = 0.86f + 0.28f * (0.5f + 0.5f * sin(phase * 2.15f))

    // 2. 四团低频漂移的极光径向光斑
    drawBlob(
        center = Offset(
            x = size.width * (0.18f + 0.22f * p1),
            y = size.height * (0.14f + 0.14f * p2),
        ),
        radius = size.minDimension * 0.84f,
        color = colors.blobPrimary,
        alpha = (0.50f * breathe * blobGain).coerceAtMost(0.92f),
    )

    drawBlob(
        center = Offset(
            x = size.width * (0.86f - 0.22f * p2),
            y = size.height * (0.28f + 0.20f * p1),
        ),
        radius = size.minDimension * 0.72f,
        color = colors.blobSecondary,
        alpha = (0.48f * breathe * blobGain).coerceAtMost(0.90f),
    )

    drawBlob(
        center = Offset(
            x = size.width * (0.38f + 0.26f * p3),
            y = size.height * (0.86f - 0.18f * p3),
        ),
        radius = size.minDimension * 0.76f,
        color = colors.blobTertiary,
        alpha = (0.46f * breathe * blobGain).coerceAtMost(0.88f),
    )

    drawBlob(
        center = Offset(
            x = size.width * (0.64f - 0.14f * cos(phase * 0.68f)),
            y = size.height * (0.52f + 0.12f * sin((phase * 0.83f + PI / 3).toFloat())),
        ),
        radius = size.minDimension * 0.54f,
        color = colors.blobQuartiary,
        alpha = (0.34f * breathe * blobGain).coerceAtMost(0.75f),
    )

    // 3. 顶部与底部的微弱压暗遮罩，保护状态栏和底栏文字对比度
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                colors.backdropVeilColor.copy(alpha = colors.backdropVeil),
                Color.Transparent,
                Color.Transparent,
                colors.backdropVeilColor.copy(alpha = colors.backdropVeil * 1.15f),
            ),
        ),
        size = size,
    )
}

private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.44f),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
