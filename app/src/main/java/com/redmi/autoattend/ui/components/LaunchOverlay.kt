package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
            delay(90)
            alpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            present = false
            onFadeOutFinished()
        }
    }

    if (!present) return

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
                breath.animateTo(0.45f, tween(760, easing = FastOutSlowInEasing))
                breath.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            // 【第七轮】背景必须跟随**当前主题**，不能写死深色。
            //
            // 之前这里硬编码 Color(0xFF04070F)，于是浅色主题下用户会看到
            // "一块纯黑盖在浅色界面上"，再淡出成浅色 —— 非常突兀。
            //
            // 现在取 GlassTheme.colors.backgroundDeep：
            //   · 深色 → #04070F（与 values-night 的 splash/窗口背景一致）
            //   · 浅色 → #E8F0FB（与 values 的 splash/窗口背景一致）
            //
            // 冷启动的顺畅感取决于这条链路上**没有任何一次视觉跳变**：
            //   系统 Splash → 窗口背景 → 本遮罩 → 首页光斑
            // 前三个用同一个颜色，最后一次才是有意的淡出。
            .background(com.redmi.autoattend.ui.theme.GlassTheme.colors.backgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(54.dp)) {
                    val stroke = 3.5.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(inset, inset)

                    // 底环：很淡，给旋转弧一个"轨道"
                    drawArc(
                        color = Color(0xFF7CC4FF).copy(alpha = 0.14f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )

                    // 旋转弧：120° 的一段，KSU 同款
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF7CC4FF).copy(alpha = 0f),
                                Color(0xFF7CC4FF).copy(alpha = 0.9f * breath.value),
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

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "钉钉GO",
                    // 跟随主题的文字色：深色主题浅字，浅色主题深字
                    color = com.redmi.autoattend.ui.theme.GlassTheme.colors.textPrimary.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.5.sp,
                )
            }
        }
    }
}