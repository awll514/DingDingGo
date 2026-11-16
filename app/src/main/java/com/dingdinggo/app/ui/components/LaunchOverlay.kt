package com.dingdinggo.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme
import com.dingdinggo.app.ui.theme.IosEaseInOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动动画遮罩（KernelSU 风格）。
 *
 * ## 解决什么问题
 *
 * 之前冷启动是"黑屏 → 突然出现界面"，中间那 1 秒纯黑观感很差。
 * 系统 SplashScreen 虽然能画，但它只画静态色块，而且
 * 一旦加了动画图标在部分 ROM 上会卡死（踩过，见 themes.xml 注释）。
 *
 * 所以改成**应用自己画启动遮罩**：
 * - 第一帧就由 Compose 绘制（此时 SPI 已经开始，不等任何数据）；
 * - 画一个 KSU 那种"最小但精致"的加载态：旋转弧 + 底部小字；
 * - 首页数据就绪后 220ms 淡出，视觉上"无缝长进主界面"。
 *
 * ## 为什么这样最快
 *
 * 关键：遮罩**不等待任何 IO**。它和首页数据加载是并行的，
 * 所以它不增加启动时间 —— 它只是把"原本的黑屏"换成"好看的加载态"。
 * `windowBackground` 已经设成同色渐变，所以从系统 Splash 到本遮罩
 * 之间没有颜色跳变。
 */
@Composable
fun LaunchOverlay(
    visible: Boolean,
    onFadeOutFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(1f) }
    var present by remember { mutableStateOf(true) }

    /**
     * 【关键】只有"收到 visible=true"时才淡出。
     *
     * 之前写成 `if (!visible) { 淡出 }` 有致命问题：
     * visible 的初值本就是 false（bootReady 初始 false），
     * 于是首帧就触发淡出分支，遮罩还没画出来就没了。
     */
    var everReady by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (!everReady) {
            everReady = true
            // 【第五轮】240ms 硬等待 → 90ms。
            //
            // 这 240ms 是**纯延迟**：它不等待任何 IO，只是让弧线转够一圈再淡出。
            // 但它直接加在"用户看到完整界面"的时间上 —— 属于拿性能换观感，
            // 而且转 0.2 圈和转 1.4 圈在主观上并没有区别。
            //
            // 90ms 足够让首帧稳定（此时首页已经完成第一次布局），
            // 又不会让人感觉到"卡了一下才出现"。
            delay(80)
            alpha.animateTo(0f, GlassMotion.easeOut(GlassMotion.SHEET_ENTER))
            present = false
            onFadeOutFinished()
        }
    }

    if (!present) return

    val colors = GlassTheme.colors

    // 旋转弧的角度
    val spin = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            while (true) {
                spin.animateTo(
                    spin.value + 360f,
                    tween(1100, easing = LinearEasing),
                )
            }
        }
    }

    // 呼吸透明度，让弧有"活着"的感觉
    val breath = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch {
            while (true) {
                breath.animateTo(0.45f, GlassMotion.easeInOut(760))
                breath.animateTo(1f, GlassMotion.easeInOut(760))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(colors.backgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(96.dp)) {
                        val stroke = 3.dp.toPx()
                        val inset = stroke / 2f
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val topLeft = Offset(inset, inset)

                        drawArc(
                            color = colors.accent.copy(alpha = 0.16f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )

                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(
                                    colors.accent.copy(alpha = 0f),
                                    colors.accent.copy(alpha = 0.92f * breath.value),
                                ),
                            ),
                            startAngle = spin.value,
                            sweepAngle = 120f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    PixelDingMascotPortal(
                        size = 76.dp,
                        cornerRadius = 24.dp,
                        mood = DingMascotMood.READY,
                        backgroundPaused = false,
                        showStatusDot = false,
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "钉钉GO",
                    color = colors.textPrimary.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.5.sp,
                )
            }
        }
    }
}