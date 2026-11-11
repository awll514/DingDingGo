package com.redmi.autoattend.ui.glass

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
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.redmi.autoattend.ui.theme.GlassColors

/**
 * 液态玻璃的「被采样背景」。
 *
 * ## 为什么这一层决定了玻璃好不好看
 *
 * 玻璃面板之所以能"透"，前提是背后真的有一层**在流动**的内容可供折射。
 * 纯色背景上的玻璃只会退化成半透明色块。
 *
 * 而且光斑必须够亮：光斑太暗时，无论玻璃参数怎么调，面板里都只是一片灰——
 * 玻璃就"没东西可透"。这里刻意把光斑做得比"看起来合适"更亮，反正玻璃会模糊它们。
 *
 * ## 帧率节流（关键性能设计）
 *
 * 这是一个容易踩的坑：如果背景用标准的 infiniteTransition 以 60fps 重绘，
 * 那么挂了 [prismalGlassLayer] 的录制层就会**每帧重新录制一次**，
 * 导致每个玻璃面板每帧都要重新采样 + 跑一遍 RenderEffect 链。
 * 3 个面板 × 60fps = 每秒 180 次全管线重算，GPU 直接打满。
 *
 * 但光斑的动画周期是 26~42 秒——**根本不需要 60fps**。
 * 所以这里用 [withFrameNanos] 手动节流到约 20fps：
 * 视觉上完全看不出差别（慢速运动模糊对人眼足够），
 * GPU 工作量直接降到 1/3。
 */
@Composable
fun FlowingBackground(
    colors: GlassColors,
    modifier: Modifier = Modifier,
    layer: PrismalGlassLayer? = null,
) {
    // 四个独立的相位（0..1），各自不同周期，避免同步呼吸显得机械
    var t1 by remember { mutableFloatStateOf(0f) }
    var t2 by remember { mutableFloatStateOf(0f) }
    var t3 by remember { mutableFloatStateOf(0f) }
    var breathe by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        var lastUpdateNanos = 0L

        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - startNanos) / 1_000_000L

                // 节流：只有距上次更新超过 FRAME_INTERVAL_MS 才算新相位
                if (elapsedMs - lastUpdateNanos >= FRAME_INTERVAL_MS) {
                    lastUpdateNanos = elapsedMs

                    // 三角波：0 -> 1 -> 0，对应 RepeatMode.Reverse
                    t1 = triangle(elapsedMs, 26_000L)
                    t2 = triangle(elapsedMs, 34_000L)
                    t3 = triangle(elapsedMs, 42_000L)

                    // 呼吸：7 秒一个来回，幅度 0.82 ~ 1.18
                    val breathPhase = triangle(elapsedMs, 7_000L)
                    breathe = 0.82f + 0.36f * breathPhase
                }
            }
        }
    }

    // 光斑亮度增益。
    //
    // 浅色主题需要更高的增益：白色玻璃会降低对比，
    // 若光斑不够亮，玻璃看起来就是"灰雾"而不是"透光的玻璃"。
    val blobGain = if (colors.isDark) 1.0f else 1.55f

    val drawBlock: DrawScope.() -> Unit = {
        // 1. 底色渐变
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.backgroundDeep,
                    colors.backgroundMid,
                    colors.backgroundAccent,
                    colors.backgroundDeep,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            size = size,
        )

        // 2. 四个缓慢漂移的径向光斑
        drawBlob(
            center = Offset(
                x = size.width * (0.20f + 0.16f * t1),
                y = size.height * (0.16f + 0.12f * t1),
            ),
            radius = size.minDimension * 0.78f,
            color = colors.blobPrimary,
            alpha = 0.52f * breathe * blobGain,
        )

        drawBlob(
            center = Offset(
                x = size.width * (0.86f - 0.18f * t2),
                y = size.height * (0.30f + 0.18f * t2),
            ),
            radius = size.minDimension * 0.68f,
            color = colors.blobSecondary,
            alpha = 0.52f * breathe * blobGain,
        )

        drawBlob(
            center = Offset(
                x = size.width * (0.42f + 0.22f * t3),
                y = size.height * (0.90f - 0.16f * t3),
            ),
            radius = size.minDimension * 0.70f,
            color = colors.blobTertiary,
            alpha = 0.48f * breathe * blobGain,
        )

        drawBlob(
            center = Offset(
                x = size.width * (0.64f - 0.10f * t1),
                y = size.height * (0.54f - 0.12f * t2),
            ),
            radius = size.minDimension * 0.50f,
            color = colors.blobQuartiary,
            alpha = 0.36f * breathe * blobGain,
        )

        // 3. 上下压暗（深色保护文字可读性；浅色轻微加重让内容聚焦中部）
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colors.backdropVeilColor.copy(alpha = colors.backdropVeil),
                    Color.Transparent,
                    Color.Transparent,
                    colors.backdropVeilColor.copy(alpha = colors.backdropVeil * 1.2f),
                ),
            ),
            size = size,
        )
    }

    val canvasModifier = if (layer != null) {
        modifier.fillMaxSize().prismalGlassLayer(layer)
    } else {
        modifier.fillMaxSize()
    }

    Canvas(modifier = canvasModifier) {
        drawBlock()
    }
}

/** 背景动画的目标帧间隔：约 20fps（50ms）。慢速运动完全够用。 */
private const val FRAME_INTERVAL_MS = 50L

/** 计算三角波相位 0..1..0 */
private fun triangle(elapsedMs: Long, periodMs: Long): Float {
    val pos = (elapsedMs % periodMs).toFloat() / periodMs
    return if (pos <= 0.5f) pos * 2f else (1f - pos) * 2f
}

/** 画一个柔和的径向光斑 */
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
                color.copy(alpha = alpha * 0.42f),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}