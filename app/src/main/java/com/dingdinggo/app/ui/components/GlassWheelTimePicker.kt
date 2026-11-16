package com.dingdinggo.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dingdinggo.app.ui.theme.GlassTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 玻璃风格的滚轮时间选择器。
 *
 * 关键实现细节（消除跨列串扰与惯性打断）：
 * 1. [currentHour] / [currentMinute] / [latestOnMinuteChange] 均通过 `rememberUpdatedState`
 *    绑定最新值，避免 `LaunchedEffect(listState)` 闭包的旧状态覆盖另一列的当前值；
 * 2. 滚轮对齐仅在 `!listState.isScrollInProgress` 且外部参数变化时发生，
 *    手指快速甩动时绝不被 `animateScrollToItem` 中途打断；
 * 3. `snapshotFlow` 使用 `drop(1)` 过滤初始发射，只在真正过档时给出极轻的刻度触觉反馈。
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
    val clampedTotal = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    var hourState by remember { mutableIntStateOf(clampedTotal / 60) }
    var minuteState by remember { mutableIntStateOf(clampedTotal % 60) }
    val latestOnMinuteChange by rememberUpdatedState(onMinuteChange)

    LaunchedEffect(clampedTotal) {
        val currentTotal = hourState * 60 + minuteState
        if (clampedTotal != currentTotal) {
            hourState = clampedTotal / 60
            minuteState = clampedTotal % 60
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 中央焦点玻璃选区条，增强滚轮立体景深
        Box(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .height(itemHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (colors.isDark) Color.White.copy(alpha = 0.08f)
                    else colors.accent.copy(alpha = 0.08f),
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                count = 24,
                selected = hourState,
                itemHeight = itemHeight,
                visibleCount = visibleCount,
                formatter = { "%02d".format(it) },
                onSelectedChange = { newHour ->
                    hourState = newHour
                    latestOnMinuteChange(newHour * 60 + minuteState)
                },
                modifier = Modifier.width(88.dp),
            )

            Text(
                text = ":",
                color = colors.textTertiary.copy(alpha = 0.65f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            WheelColumn(
                count = 60,
                selected = minuteState,
                itemHeight = itemHeight,
                visibleCount = visibleCount,
                formatter = { "%02d".format(it) },
                onSelectedChange = { newMinute ->
                    minuteState = newMinute
                    latestOnMinuteChange(hourState * 60 + newMinute)
                },
                modifier = Modifier.width(88.dp),
            )
        }
    }
}

private const val WHEEL_LOOP_CYCLES = 2_000

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

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

    val totalCount = count * WHEEL_LOOP_CYCLES
    val baseOffset = (WHEEL_LOOP_CYCLES / 2) * count
    val initialVirtualIndex = baseOffset + selected.floorMod(count)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialVirtualIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val verticalPadding = itemHeight * half
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val latestSelected by rememberUpdatedState(selected.floorMod(count))
    val latestOnSelectedChange by rememberUpdatedState(onSelectedChange)

    val centeredVirtualIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2f) - viewportCenter)
            }?.index ?: listState.firstVisibleItemIndex
        }
    }
    val centeredValue by remember(count) {
        derivedStateOf { centeredVirtualIndex.floorMod(count) }
    }

    // 实时同步居中值与轻刻度反馈（通过 latest* 读取最新值，绝不回滚另一列）
    LaunchedEffect(listState, count) {
        snapshotFlow { centeredValue }
            .distinctUntilChanged()
            .drop(1)
            .collect { value ->
                runCatching {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (value != latestSelected) {
                    latestOnSelectedChange(value)
                }
            }
    }

    // 极端连续滚动接近虚拟边界时，静止后无感拉回中央循环区
    LaunchedEffect(listState, count) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val idx = centeredVirtualIndex
                    if (idx < count * 50 || idx > totalCount - count * 50) {
                        runCatching {
                            listState.scrollToItem(baseOffset + idx.floorMod(count))
                        }
                    }
                }
            }
    }

    // 仅在外部传入的 selected 主动变化且当前未处于手动/惯性滚动中时才补对齐，并走最近环向路径
    LaunchedEffect(selected) {
        val targetValue = selected.floorMod(count)
        if (!listState.isScrollInProgress && centeredValue != targetValue) {
            val currentIdx = centeredVirtualIndex
            val currentValue = currentIdx.floorMod(count)
            val rawDiff = (targetValue - currentValue).floorMod(count)
            val shortestDelta = if (rawDiff > count / 2) rawDiff - count else rawDiff
            val targetVirtual = (currentIdx + shortestDelta).coerceIn(0, totalCount - 1)
            runCatching { listState.animateScrollToItem(targetVirtual) }
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
            items(totalCount, key = { it }) { virtualIndex ->
                val value = virtualIndex % count
                WheelItem(
                    text = formatter(value),
                    itemHeight = itemHeight,
                    index = virtualIndex,
                    listState = listState,
                    itemHeightPx = itemHeightPx,
                    onClick = {
                        scope.launch {
                            runCatching { listState.animateScrollToItem(virtualIndex) }
                        }
                    },
                )
            }
        }

        // 中央选中指示带。
        //
        // 【真机修正】浅色主题原来用 Color.White.copy(alpha = 0.85f)，
        // 在玻璃面板上变成一条几乎不透明的白带 —— 而选中项文字是
        // textPrimary（接近黑），对比度被白带冲掉，实际观感是
        // "白底浅字"，几乎读不出数值。
        //
        // 苹果的 wheel picker 指示带是**极淡的一层**，靠边缘两条细线
        // 定义区间，而不是一块实色。这里照做：
        //   · 填充降到 0.10~0.14，只做轻微提亮
        //   · 上下各加一条 1.5dp 的 accent 细线定界
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (colors.isDark) {
                                    Color.White.copy(alpha = 0.09f)
                                } else {
                                    colors.accent.copy(alpha = 0.10f)
                                },
                                if (colors.isDark) {
                                    Color.White.copy(alpha = 0.09f)
                                } else {
                                    colors.accent.copy(alpha = 0.10f)
                                },
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // 上下定界线：让"选中区间"有明确边界，而不是一块渐隐色块
            val lineColor = colors.accent.copy(alpha = if (colors.isDark) 0.70f else 0.55f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                lineColor,
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                lineColor,
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

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
    onClick: () -> Unit,
) {
    val colors = GlassTheme.colors

    Box(
        modifier = Modifier
            .height(itemHeight)
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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