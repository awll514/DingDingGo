package com.dingdinggo.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dingdinggo.app.R
import com.dingdinggo.app.ui.HomeStatus
import com.dingdinggo.app.ui.HomeUiState
import com.dingdinggo.app.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * 像素风「钉钉娘」看板娘状态枚举。
 *
 * 与首页打卡状态联动，用于徽章角标点缀与点击互动台词。
 */
enum class DingMascotMood(val badgeArgb: Long, val quip: String) {
    READY(0xFF0A84FFL, "准点守候中，到点自动比耶打卡 ✌️"),
    RUNNING(0xFFFFC82EL, "钉钉娘正在执行打卡链路中 ⚡"),
    SUCCESS(0xFF34D399L, "今日打卡任务搞定，安心摸鱼/下班啦 ✓"),
    ALERT(0xFFFF5A79L, "发现打卡异常或权限缺失，点下面查看一下~"),
    PAUSED(0xFF8FB8EAL, "今日跳过或定时已暂停，先在云端休息会儿 ☁️"),
}

fun DingMascotMood.badgeColor(): Color = Color(badgeArgb)

fun resolveDingMascotMood(state: HomeUiState, busy: Boolean): DingMascotMood = when {
    busy || state.status is HomeStatus.Running -> DingMascotMood.RUNNING
    state.status is HomeStatus.Failed -> DingMascotMood.ALERT
    state.status is HomeStatus.Success || (state.todayClockIn != null && state.todayClockOut != null) ->
        DingMascotMood.SUCCESS
    !state.enabled || state.skipToday || state.onVacation -> DingMascotMood.PAUSED
    else -> DingMascotMood.READY
}

/**
 * 像素「钉钉娘」晴空玻璃传送门徽章。
 *
 * 视觉结构与 App 图标同源：
 * - 上段：像素风天蓝→青蓝渐层晴空 + 4 个正交像素小星芒
 * - 中景：透明底像素钉钉娘立绘（双翼发卡、麻花辫、露指手套比耶）
 * - 底部：像素阶梯白云托底，与玻璃圆角边自然融合
 * - 右下角：精巧像素状态灯（仅在前台执行态 RUNNING 且 !backgroundPaused 时呼吸闪烁，后台零渲染）
 */
@Composable
fun PixelDingMascotPortal(
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    cornerRadius: Dp = 17.dp,
    mood: DingMascotMood = DingMascotMood.READY,
    backgroundPaused: Boolean = false,
    showStatusDot: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = GlassTheme.colors
    val res = LocalContext.current.resources
    val sprite = remember(res) { ImageBitmap.imageResource(res, R.drawable.ic_dingtalk_girl_sprite) }
    val shape = RoundedCornerShape(cornerRadius)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val hopScale = remember { Animatable(1f) }

    // 仅在执行中且界面可见时允许轻呼吸脉冲；后台或非执行态不注册持续动画。
    val pulseAlpha = if (mood == DingMascotMood.RUNNING && !backgroundPaused) {
        val transition = rememberInfiniteTransition(label = "mascotPulse")
        val p by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(720, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotPulse",
        )
        p
    } else {
        1f
    }

    val skyBrush = remember(colors.isDark) {
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF0E2C73),
                    Color(0xFF1A63C9),
                    Color(0xFF56B4FB),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF196EEB),
                    Color(0xFF42A8FA),
                    Color(0xFF9BE0FF),
                ),
            )
        }
    }

    val interactiveModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
        ) {
            view.performClickHaptic()
            scope.launch {
                hopScale.snapTo(0.88f)
                hopScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
            }
            onClick()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = hopScale.value
                scaleY = hopScale.value
            }
            .clip(shape)
            .background(skyBrush)
            .border(
                width = 1.dp,
                color = if (colors.isDark) Color(0x669CE0FF) else Color(0xCCFFFFFF),
                shape = shape,
            )
            .semantics { contentDescription = "钉钉娘像素看板娘" }
            .then(interactiveModifier),
    ) {
        // 像素晴空背景星芒（纯静态像素矩形，无模糊渐隐）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = this.size.width / 16f
            fun px(gx: Float, gy: Float, color: Color, w: Float = 1f, h: Float = 1f) {
                drawRect(color, Offset(gx * u, gy * u), Size(w * u, h * u))
            }
            val starGold = Color(0xFFFFEC80)
            val starIce = Color(0xCCDCF5FF)
            // 左上 & 右上像素十字星
            px(2.0f, 2.2f, starGold)
            px(13.0f, 1.8f, starIce)
            px(12.2f, 2.6f, starIce); px(13.8f, 2.6f, starIce)
            px(13.0f, 3.4f, starIce)
            px(2.8f, 5.2f, starIce)
        }

        // 像素钉钉娘立绘（FilterQuality.None 保持像素方格锐利，略向下贴近云层）
        Image(
            bitmap = sprite,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = size * 0.04f, start = size * 0.04f, end = size * 0.04f),
        )

        // 底部阶梯像素白云遮边（自然托住水手服下摆，与 App 图标一致）
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(size * 0.22f),
        ) {
            val cols = 16
            val stepW = this.size.width / cols
            val h = this.size.height
            val cloudTops = floatArrayOf(0.32f, 0.20f, 0.24f, 0.38f, 0.56f, 0.66f, 0.72f, 0.72f, 0.72f, 0.66f, 0.52f, 0.36f, 0.24f, 0.18f, 0.26f, 0.36f)
            for (i in 0 until cols) {
                val topY = h * cloudTops[i]
                drawRect(
                    color = Color(0xF5FFFFFF),
                    topLeft = Offset(i * stepW, topY),
                    size = Size(stepW + 0.5f, h - topY),
                )
                drawRect(
                    color = Color(0xFFD6EDFF),
                    topLeft = Offset(i * stepW, topY + h * 0.28f),
                    size = Size(stepW + 0.5f, (h - topY - h * 0.28f).coerceAtLeast(0f)),
                )
            }
        }

        // 右下角精巧像素状态灯
        if (showStatusDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = (size * 0.07f).coerceAtLeast(3.dp), bottom = (size * 0.07f).coerceAtLeast(3.dp))
                    .size((size * 0.20f).coerceIn(8.dp, 13.dp))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF0D1B44))
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(mood.badgeColor().copy(alpha = pulseAlpha)),
            )
        }
    }
}

/**
 * 像素风小星星点缀（用于打卡/版本装饰，纯静态整数块绘制）。
 */
@Composable
fun PixelStarAccent(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 3f
        drawRect(color, Offset(u, 0f), Size(u, u * 3f))
        drawRect(color, Offset(0f, u), Size(u * 3f, u))
        drawRect(Color.White.copy(alpha = 0.85f), Offset(u, u), Size(u, u))
    }
}
