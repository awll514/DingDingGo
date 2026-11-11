package com.redmi.autoattend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.theme.GlassTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * 玻璃风格的滚轮时间选择器。
 *
 * 为什么不用系统 TimePicker（需求 F1 明确要求）：
 * 系统 TimePicker 是浅色卡片，放进玻璃语境里会很突兀，
 * 而且无法让选中指示器跟玻璃面板的高光对齐。
 *
 * 实现要点：
 * - LazyColumn + rememberSnapFlingBehavior：真实惯性滚动 + 吸附到整项；
 * - 每一项按"距中心的项数"计算 scale / alpha / 透视旋转，做出滚筒效果；
 * - 上下各叠一层从背景到透明的渐变遮罩，让边缘自然淡出；
 * - 中央一条高光带标记当前值。
 *
 * 正确性细节：LazyColumn 用 contentPadding 把首项顶到中央，
 * 因此"当前值"要从 layoutInfo 里按"离视口中心最近"反推，而不是直接用 index。
 */
@Composable
fun GlassWheelTimePicker(
    minuteOfDay: Int,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 50.dp,
    visibleCount: Int = 5,
) {
    val colors = GlassTheme.colors
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WheelColumn(
            count = 24,
            selected = hour,
            itemHeight = itemHeight,
            visibleCount = visibleCount,
            formatter = { "%02d".format(it) },
            onSelectedChange = { newHour -> onMinuteChange(newHour * 60 + minute) },
            modifier = Modifier.width(88.dp),
        )

        Text(
            text = ":",
            color = colors.textTertiary.copy(alpha = 0.6f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        WheelColumn(
            count = 60,
            selected = minute,
            itemHeight = itemHeight,
            visibleCount = visibleCount,
            formatter = { "%02d".format(it) },
            onSelectedChange = { newMinute -> onMinuteChange(hour * 60 + newMinute) },
            modifier = Modifier.width(88.dp),
        )
    }
}

@Composable
private fun WheelColumn(
    count: Int,
    selected: Int,
    itemHeight: Dp,
    visibleCount: Int,
    formatter: (Int) -> String,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val visible = if (visibleCount % 2 == 1) visibleCount else visibleCount + 1
    val half = visible / 2

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    val verticalPadding = itemHeight * half

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2f) - viewportCenter)
            }?.index ?: listState.firstVisibleItemIndex
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val clamped = idx.coerceIn(0, count - 1)
                if (clamped != selected) onSelectedChange(clamped)
            }
    }

    LaunchedEffect(selected) {
        if (centeredIndex != selected) {
            runCatching { listState.animateScrollToItem(selected) }
        }
    }

    Box(modifier = modifier.height(itemHeight * visible)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count) { index ->
                WheelItem(
                    text = formatter(index),
                    itemHeight = itemHeight,
                    index = index,
                    listState = listState,
                    itemHeightPx = itemHeightPx,
                )
            }
        }

        // 中央选中指示带
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = if (colors.isDark) {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.075f),
                                Color.White.copy(alpha = 0.075f),
                                Color.Transparent,
                            )
                        } else {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.85f),
                                Color.White.copy(alpha = 0.85f),
                                Color.Transparent,
                            )
                        },
                    )
                ),
        )

    }
}

/**
 * 单个滚轮项。
 *
 * 距离以"项数"为单位：0 表示正中。
 * 越远越小、越透明、并带轻微透视旋转，形成滚筒错觉。
 */
@Composable
private fun WheelItem(
    text: String,
    itemHeight: Dp,
    index: Int,
    listState: LazyListState,
    itemHeightPx: Float,
) {
    val colors = GlassTheme.colors

    Box(
        modifier = Modifier
            .height(itemHeight)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.textPrimary,
            fontSize = 23.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val info = listState.layoutInfo
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (itemInfo == null) {
                    alpha = 0f
                    scaleX = 0.6f
                    scaleY = 0.6f
                } else {
                    val d = ((itemInfo.offset + itemInfo.size / 2f) - viewportCenter) / itemHeightPx
                    val absD = abs(d)
                    // 只在可见范围内衰减；超过 2.6 项直接完全透明，
                    // 这样滚轮上下边缘是"内容淡出"，而不是被一个色块盖住。
                    if (absD >= 2.6f) {
                        alpha = 0f
                        scaleX = 0.5f
                        scaleY = 0.5f
                    } else {
                        val s = (1f - absD * 0.13f).coerceAtLeast(0.62f)
                        scaleX = s
                        scaleY = s
                        // 用四次方衰减，靠近中心保持清晰、靠近边缘快速淡出，
                        // 视觉上接近真实的曲面滚轮。
                        val n = (absD / 2.6f).coerceIn(0f, 1f)
                        alpha = ((1f - n * n) * (1f - n * n)).coerceIn(0f, 1f)
                        rotationX = (d * 5.5f).coerceIn(-18f, 18f)
                    }
                }
            },
        )
    }
}