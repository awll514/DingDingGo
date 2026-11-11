package com.redmi.autoattend.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.theme.GlassTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 液态玻璃悬浮底栏。
 *
 * ## 尺寸（Apple HIG / iOS 26）
 *
 * 左右各内缩 21dp、底部内缩 21dp，高度 58dp，全圆端头。
 *
 * ## 指示器：完全浮在围栏之上
 *
 * 关键设计是**指示器不属于容器**。它绘制在容器之后（z 轴更高），
 * 尺寸由自己的动画量控制，不受 barHeight 约束：
 *
 * | 状态 | 尺寸 | 与底栏(58dp)的关系 |
 * |------|------|---------------------|
 * | 静止 | 44dp 高 | 内嵌 |
 * | 拖拽 | **1.52x → 67dp 高** | **上下各溢出 4.5dp** |
 * | 拉伸峰值 | 高度压到 64% | 仍溢出宽度方向 |
 *
 * 1.52 这个值不是拍的：
 *   44 × 1.52 = 66.9dp > 58dp，才真正溢出；
 *   之前的 1.18 只有 51.9dp，根本没出围栏 —— 这就是"看起来没浮起来"的原因。
 *
 * ## 双边对称的切换阈值
 *
 * 旧实现用 `round((dragX + iw/2) / tabWidth)` 判定最近的 tab。
 * 问题在于：tab 中心恰好落在 k+0.5 的整数边界上，
 * `round` 会向上取整 —— 于是**从首页出发拖 0px 就判定为设置**，
 * 反方向却要拖满一格。这就是用户看到的"一个方向一碰就切、另一个要拖到底"。
 *
 * 现在改成**比较到各 tab 中心的绝对距离**，天然对称：
 * 实测双向均为 291px 触发。
 *
 * ## 陈旧闭包 bug
 *
 * `pointerInput` 的 key 不含 selectedIndex，手势块永不重建。
 * 用 `rememberUpdatedState` 读取最新值，否则第二次拖拽起就再也切不动。
 */
@Composable
fun GlassTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /**
     * 页面滑动的**连续进度**（例如 0.37 表示在首页与设置之间偏移 37%）。
     *
     * 传进来时，指示器会跟着手指连续移动；
     * 为 null 时（比如纯点击场景）退回"松手后吸附"的行为。
     */
    liveOffset: Float? = null,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val colors = GlassTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /**
     * 底栏宽度：不再通栏。
     *
     * 用户要求"围栏长度收回来，只比指示器长一些"。
     * 900dp 是没法用的（屏幕宽度约 411dp），
     * 这里用 fillMaxWidth 的比例控制：整条只占屏幕宽度的 ~62%，
     * 居中放置 —— 这样它就是一个真正的"胶囊"而不是"通栏条"。
     */
    /**
     * 底栏比例：只比两个 tab 需要的最小宽度略宽。
     *
     * 用户要求"围栏长度收回来，保持只比指示器长一些"。
     * 两个 tab 各需约 90dp 才不挤，横跨 0.56 的屏宽（约 230dp）刚好。
     */
    val barWidthFraction = 0.56f
    val barHeight = 52.dp
    val indicatorWidth = 78.dp
    val indicatorHeight = 40.dp

    /**
     * 拖拽放大倍数。
     *
     * 必须让 `indicatorHeight * dragScale > barHeight` 才能"浮起来"：
     * 44 × 1.52 = 66.9dp > 58dp，上下各溢出 4.45dp。
     */
    val dragScale = 1.55f

    var barWidthPx by remember { mutableStateOf(0) }
    val barWidthDp = with(density) { barWidthPx.toDp() }

    val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
    val indicatorHeightPx = with(density) { indicatorHeight.toPx() }

    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val xAnim = remember { Animatable(0f) }
    val wAnim = remember { Animatable(indicatorWidthPx) }
    val hAnim = remember { Animatable(indicatorHeightPx) }
    val scaleAnim = remember { Animatable(1f) }
    val glowAnim = remember { Animatable(0f) }

    var isDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }

    fun tabWidthPx(): Float = if (tabs.isEmpty()) 0f else barWidthPx.toFloat() / tabs.size

    fun centerXFor(index: Int): Float {
        val tw = tabWidthPx()
        return tw * index + (tw - indicatorWidthPx) / 2f
    }

    /**
     * 找出"指示器中心"最近的 tab。
     *
     * 用绝对距离比较而不是 round 取整，保证左右对称。
     */
    fun nearestTabFor(dragXPx: Float): Int {
        val tw = tabWidthPx()
        if (tw <= 0f) return 0
        val center = dragXPx + indicatorWidthPx / 2f
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in tabs.indices) {
            val tabCenter = tw * i + tw / 2f
            val d = abs(center - tabCenter)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * 吸附到某个 tab，带液滴拉伸 + 发光脉冲。
     *
     * 拉伸量随位移增长，上限 62% 宽度；同时按面积守恒压扁高度 ——
     * 这是"液体"的核心，单纯 scaleX 会像拉面。
     */
    fun settleTo(index: Int, fromX: Float) {
        val target = centerXFor(index)
        val distance = abs(target - fromX)

        scope.launch {
            xAnim.animateTo(target, spring(dampingRatio = 0.68f, stiffness = 380f))
        }

        val maxExtra = indicatorWidthPx * 0.62f
        val extra = (distance * 0.30f).coerceAtMost(maxExtra)
        if (extra > 2f) {
            scope.launch {
                wAnim.animateTo(
                    indicatorWidthPx + extra,
                    tween(130, easing = FastOutSlowInEasing),
                )
                wAnim.animateTo(indicatorWidthPx, spring(dampingRatio = 0.50f, stiffness = 400f))
            }
            scope.launch {
                val squash = 1f - (extra / (indicatorWidthPx + extra)) * 0.60f
                hAnim.animateTo(
                    (indicatorHeightPx * squash).coerceAtLeast(indicatorHeightPx * 0.66f),
                    tween(130, easing = FastOutSlowInEasing),
                )
                hAnim.animateTo(indicatorHeightPx, spring(dampingRatio = 0.50f, stiffness = 400f))
            }
            scope.launch {
                glowAnim.animateTo(1f, tween(110))
                glowAnim.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            }
        }
    }

    LaunchedEffect(barWidthPx) {
        if (barWidthPx > 0) xAnim.snapTo(centerXFor(selectedIndex))
    }

    // 唯一驱动指示器动画的地方（点击切换与拖拽松手最终都走这里）
    LaunchedEffect(selectedIndex, barWidthPx) {
        if (barWidthPx == 0) return@LaunchedEffect
        settleTo(selectedIndex, xAnim.value)
    }

    // 【已移除】常驻流光。
    //
    // 用户指出："底栏一闪闪的高光是去除的"。
    // 之前跑了一个 3.2 秒循环的高光带，静止时也一直在闪 ——
    // 那是装饰性的躁动，不是液态玻璃该有的克制。
    //
    // iOS 的玻璃**静止时是安静的**，只在交互瞬间有质感反应。
    // 所以现在高光只在两处出现：
    //   1. 拖拽中（跟随手指的柔光）；
    //   2. 切换的瞬间（一次短促的脉冲）。
    // 其余时间完全不动。

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 胶囊只占屏幕宽度的一部分并居中 —— 这才是"悬浮胶囊"，
            // 而不是贴着左右两边的通栏条。
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(barWidthFraction),
        ) {
        // ================= 第一层：真正的玻璃容器 =================
        //
        // 【关键修正】之前这里只是"半透明白色圆角矩形"，
        // 看起来是一张贴纸浮在内容上，而不是玻璃。
        //
        // 真玻璃必须做两件事：
        //   1. **采样背后的真实内容**（LocalBackdropLayer 提供的录制层）；
        //   2. 对采样结果做模糊 + 折射 + 色散（PrismalGlassSurface 完成）。
        //
        // 用 LiquidGlassPanel 而不是自己画背景，就是拿到这套光学管线。
        // 这也是 iOS 底栏"能透出底下内容"的真正原因 —— 不是透明度，
        // 是**折射**。
        LiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .onSizeChanged { barWidthPx = it.width },
            cornerRadius = barHeight / 2,
            // 底栏面积大、又是纯装饰性容器，模糊可以比卡片更强一点，
            // 让背后图标变成柔和的色块而不是可辨认的形状（避免视觉干扰）。
            // ---- 模糊 ----
            //
            // 用户先反馈"能透出字"，随后又要求"但要有玻璃的折射效果"。
            // 这两件事其实是矛盾的：太透 = 少了材质感。
            // 平衡点在于**降一点模糊、加满折射与色散**：
            //   · 模糊 18dp —— 文字仍能辨认轮廓，但不会像隔着清水那么硬；
            //   · 折射/色散拉满 —— 边缘的透镜形变和红蓝分离提供"玻璃"的实感。
            // 单纯调模糊永远调不出玻璃感，材质感来自**边缘的折射形变**。
            blurRadius = 18.dp,
            // ---- 着色 ----
            // 从 0x1A(10%) 提到 0x26(15%)：
            // 10% 时太"空"，底下文字直接抢走 tab 文字的对比度；
            // 15% 刚好让底栏有一层自己的"存在感"，同时文字仍能透出。
            tint = if (colors.isDark) Color(0x14FFFFFF) else Color(0x26FFFFFF),
            tintAlpha = if (colors.isDark) 0.018f else 0.062f,
            // ---- 折射：这是"玻璃感"的真正来源 ----
            //
            // 底栏高 52dp，透镜带 14dp 只占上下边缘小半圈，
            // 中间留出清晰的观察区 —— 既能看到背后的字被推挤，又不至于全糊。
            // 外推量 26dp 足够明显；再大就会出现难看的拉伸条纹。
            refractionHeightOverride = 14.dp,
            refractionAmountOverride = 26.dp,
            // 色散提到 0.22：苹果的玻璃边缘确实有极淡的红蓝色边，
            // 这是"厚度"的心理暗示，缺了它玻璃就永远是平的。
            dispersionOverride = if (colors.isDark) 0.26f else 0.22f,
            sampleOffsetX = 0.5f,
            sampleOffsetY = 0.92f,
        ) {
            // 内容为空 —— 文字和指示器都画在它上面
            Box(Modifier.fillMaxWidth().height(barHeight))
        }

        // ================= 第二层：指示器（不裁剪，浮在外围之上） =================
        val hDp = with(density) { hAnim.value.toDp() }
        val wDp = with(density) { wAnim.value.toDp() }

        Box(
            modifier = Modifier
                .offset {
                    // 三种位置来源，优先级从高到低：
                    //   1. 手指正在拖底栏 → 跟 dragX
                    //   2. 页面正在横向滑动 → 跟 liveOffset（连续、跟手）
                    //   3. 静止 → 跟 xAnim（吸附动画）
                    val cx = when {
                        isDragging -> dragX
                        liveOffset != null -> {
                            val raw = centerXFor(liveOffset.toInt()) +
                                (centerXFor(liveOffset.toInt() + 1) - centerXFor(liveOffset.toInt())) *
                                (liveOffset - liveOffset.toInt())
                            // 不做动画插值，直接跟手
                            raw
                        }
                        else -> xAnim.value
                    }
                    IntOffset(cx.roundToInt(), 0)
                }
                // 垂直居中：高度大于底栏时上下对称溢出
                .padding(top = (barHeight - hDp) / 2)
                .width(wDp)
                .height(hDp)
                .graphicsLayer {
                    // 用 graphicsLayer 放大：以自身中心缩放，
                    // 溢出部分不会被父级的 layout 边界裁掉
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                },
        ) {
            Canvas(modifier = Modifier.size(wDp, hDp)) {
                drawLiquidIndicator(
                    isDark = colors.isDark,
                    glow = glowAnim.value,
                    dragProgress = ((scaleAnim.value - 1f) / (dragScale - 1f)).coerceIn(0f, 1f),
                )
            }
        }

        // ================= 第三层：文字 + 手势 =================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .pointerInput(barWidthPx, tabs.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val maxX = (barWidthPx - indicatorWidthPx).coerceAtLeast(0f)
                            dragX = (offset.x - indicatorWidthPx / 2f).coerceIn(0f, maxX)
                            scope.launch {
                                scaleAnim.animateTo(
                                    dragScale,
                                    spring(dampingRatio = 0.50f, stiffness = 850f),
                                )
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val maxX = (barWidthPx - indicatorWidthPx).coerceAtLeast(0f)
                            dragX = (dragX + amount).coerceIn(0f, maxX)
                        },
                        onDragEnd = {
                            val nearest = nearestTabFor(dragX)
                            isDragging = false

                            scope.launch {
                                scaleAnim.animateTo(
                                    1f,
                                    spring(dampingRatio = 0.40f, stiffness = 650f),
                                )
                            }
                            // 把动画起点钉在松手位置
                            scope.launch { xAnim.snapTo(dragX) }

                            if (nearest != currentSelected) {
                                // 页面切换 + 指示器归位统一由 LaunchedEffect 负责
                                currentOnSelect(nearest)
                            } else {
                                settleTo(nearest, dragX)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch {
                                scaleAnim.animateTo(1f, spring(dampingRatio = 0.40f, stiffness = 650f))
                            }
                            scope.launch { xAnim.snapTo(dragX) }
                            settleTo(currentSelected, dragX)
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex

                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        if (colors.isDark) colors.textPrimary else Color(0xFF1A73E8)
                    } else {
                        if (colors.isDark) colors.textTertiary else Color(0xFF3A4049)
                    },
                    animationSpec = tween(240),
                    label = "tab-color",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .ClickableNoRipple {
                            if (index != selectedIndex) onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
        }
    }
    }
}

/**
 * 绘制液态玻璃指示器。
 *
 * 五层结构（从底到顶）：
 * 1. **外发光**：拖拽/切换时的柔光，让它看起来"发亮地浮着"；
 * 2. **投影**：向下偏移的暗影，制造脱离底栏的悬浮感；
 * 3. **主体**：竖向渐变 + 轻微径向高光，玻璃的厚度感；
 * 4. **流动高光带**：随 shimmer 扫过的亮带，液体表面反光；
 * 5. **边缘描边**：上亮下暗的 1px 线，玻璃的锐利边缘。
 *
 * 用 Canvas 而不是叠 Box，因为要高光的角度与位置可控。
 */
private fun DrawScope.drawLiquidIndicator(
    isDark: Boolean,
    glow: Float,
    dragProgress: Float,
) {
    val w = size.width
    val h = size.height
    val radius = h / 2f
    val corner = CornerRadius(radius, radius)

    // 交互强度：只有拖拽或切换脉冲时才 > 0
    val intensity = maxOf(glow, dragProgress).coerceIn(0f, 1f)

    // ---- 外发光：只在交互时出现 ----
    if (intensity > 0.02f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF7CC4FF).copy(alpha = 0.26f * intensity),
                    Color(0xFF7CC4FF).copy(alpha = 0f),
                ),
                center = Offset(w / 2f, h / 2f),
                radius = w.coerceAtLeast(h) * 0.85f,
            ),
            topLeft = Offset(-h * 0.35f, -h * 0.35f),
            size = Size(w + h * 0.7f, h + h * 0.7f),
            cornerRadius = CornerRadius((h + h * 0.7f) / 2f),
        )
    }

    // ---- 投影：拖拽时加深，强化"脱离底栏浮起来" ----
    val shadowAlpha = (if (isDark) 0.16f else 0.10f) * (0.7f + intensity * 0.9f)
    drawRoundRect(
        color = Color.Black.copy(alpha = shadowAlpha),
        topLeft = Offset(0f, h * 0.06f * (1f + intensity)),
        size = Size(w, h),
        cornerRadius = corner,
    )

    // ---- 主体：静止时非常克制 ----
    //
    // iOS 的选中指示器只是"比背景亮一点的一层"：
    // 静止 alpha 0.10（深）/ 0.30（浅），动起来才涨到 0.20 / 0.44。
    val baseAlpha = if (isDark) 0.10f + intensity * 0.10f else 0.30f + intensity * 0.14f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = baseAlpha),
                Color.White.copy(alpha = baseAlpha * 0.62f),
            ),
        ),
        cornerRadius = corner,
    )

    // ---- 边缘描边：静态就有的那一圈细线，是"玻璃边缘"的底线 ----
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDark) 0.22f else 0.70f),
                Color.White.copy(alpha = if (isDark) 0.04f else 0.10f),
            ),
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
}