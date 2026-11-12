package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.redmi.autoattend.ui.glass.LocalBackdropLayer
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
    /**
     * 请求切到某个 tab。
     *
     * @param fromDrag true 表示这次切页来自**拖拽松手**，而不是点击。
     *
     * 两者的翻页动画应该不同：
     *   · 点击 → 完整播放一次翻页动画（从当前页滑到目标页）
     *   · 拖拽 → 页面其实已经跟着手指挪了一大半，此时再播完整翻页
     *     就会"重复一遍点击的动作"（用户原话）。
     *     所以拖拽时应该**直接落到目标页**，只留一点点收敛动画。
     */
    onSelect: (index: Int, fromDrag: Boolean) -> Unit,
    /**
     * 页面滑动的**连续进度**（例如 0.37 表示在首页与设置之间偏移 37%）。
     *
     * 传进来时，指示器会跟着手指连续移动；
     * 为 null 时（比如纯点击场景）退回"松手后吸附"的行为。
     */
    liveOffset: Float? = null,
    /**
     * 按下某个 tab 时请求把它滑过来（上游用 animateScrollToPage 实现运镜）。
     *
     * ## 为什么按下就要切
     *
     * 用户要求「长按设置时指示器滑过来」。指示器位置只由 liveOffset 驱动，
     * 所以要让它在按下时滑过去，只能让 pager 滑过去。
     *
     * 这同时统一了「点击」和「长按」两条路径：按下即滑，松手不再重复触发，
     * 因而不会出现「点两次才切」或「复读一遍」。
     */
    onPressTab: (index: Int) -> Unit = {},
    /**
     * 拖拽中把手指位置**同步**告诉上游（上游用 scrollToPage 绝对定位）。
     *
     * ## 为什么用绝对位置而不是增量
     *
     * 用 dispatchRawDelta（增量）实测有问题：快速拖拽时单帧位移很大，
     * 但 pager 的 snap 机制会在事件间隙把位置吸回最近的页，
     * 累计位移被吃掉，导致「拖不过去、回到原页」。
     *
     * 用绝对位置 scrollToPage 就没有这个问题：每一帧直接指定
     * 「现在应该在哪个浮点位置」，不受历史累积影响，也不会被吸回。
     *
     * 位置用**手指 x** 换算，而不是增量累加 —— 天然无漂移。
     */
    onDragTo: (fingerXPx: Float) -> Unit = {},
    /** 底栏宽度回写（像素）。当前未使用，保留给上游做自定义换算。 */
    onBarWidth: (px: Float) -> Unit = {},
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

    // ---------- 动效规格（iOS 18 质感）----------
    //
    // 用户明确要求：「把整个动画速率放慢，现在太快了，并不优雅」。
    // iOS 的控件动效普遍在 300~600ms，且几乎不用线性。
    //
    // 这里的缓动是"快速起步、极缓收尾"的减速曲线，
    // 数学上接近 CSS 的 cubic-bezier(0.32, 0.72, 0, 1) ——
    // Apple 在 sheet、tab 切换、控制中心里反复使用同一条曲线。
    val IosEaseOut: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 双向平滑，用于"回到原位"这类需要两头都柔和的运动 */
    val IosEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 指示器位移时长：iOS 的 tab 切换约 350~450ms */
    val INDICATOR_MOVE_MS = 420

    /** 液滴拉伸（抻开）时长 */
    val STRETCH_MS = 260

    /** 拉伸收回时长 —— 比抻开慢，收得越慢越显"黏稠" */
    val SETTLE_MS = 460

    /** 发光脉冲淡出 */
    val GLOW_FADE_MS = 520

    /**
     * 按下放大。
     *
     * 180ms 在手指刚接触时就弹起来，像被电了一下。
     * 380ms 配合 IosEaseOut，才有「被指尖吸起来」的连贯感。
     */
    val PRESS_MS = 380

    /**
     * 按下后指示器「滑到手指下方」的时长。
     *
     * 比点击位移（420ms）更长：这是一次「被拎起来」的运镜，
     * 从容一点才显得是玻璃自己滑过去，而不是被硬拽。
     */
    val PRESS_SLIDE_MS = 520

    /**
     * 松手落位。
     *
     * 比按下更长：落回要「黏稠」一点，收得快会显得被丢下去。
     */
    val RELEASE_MS = 620

    /** tab 文字颜色过渡 */
    val COLOR_MS = 380

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

    /**
     * 「已经请求要切到哪个 tab」的**本地**乐观状态。
     *
     * ## 为什么必须有它（修两个真机问题）
     *
     * 上游的 selectedIndex 只在 pager 翻页动画**完全停稳后**才回写
     * （MainActivity 里 snapshotFlow { isScrollInProgress to currentPage }，
     *  !scrolling 才写 tabIndex）。而点击后的翻页动画有 620ms，
     * 这 620ms 里 selectedIndex 一直是**旧值**。
     *
     * 由此产生两个用户可感知的问题：
     *
     * 1. **「0.5 秒内点不回去」**
     *    用户在 A 页点了 B（动画开始），想立刻点回 A。
     *    此时 currentSelected 仍是 A，于是 `target == currentSelected` 成立，
     *    走 else 分支 —— 什么都不做。点击被静默吞掉。
     *
     * 2. **来回点会误判方向**
     *    同理，快速连点时"当前在哪一页"的判断依据是滞后值，
     *    fromDrag 的落位目标也会算错。
     *
     * 用请求值（而不是动画结束后的真实值）作为交互落点判断依据，
     * 点击/拖拽就永远"以用户最后的意愿为准"，不需要等任何动画。
     *
     * 注意：它**只**用于手势落点判断与位移起始点，
     * 视觉高亮仍由 smoothWeight（位置插值）决定，因此不会出现两个高亮。
     */
    var pendingTarget by remember { mutableIntStateOf(selectedIndex) }

    // selectedIndex 一旦回写（动画停稳），把它对齐 —— 但只在没有更新请求时。
    // 放在这里而不是 else 分支，避免"动画结束时刚好有人点了别的 tab"被覆盖。
    LaunchedEffect(selectedIndex) {
        if (pendingTarget != selectedIndex) {
            pendingTarget = selectedIndex
        }
    }

    val xAnim = remember { Animatable(0f) }
    val wAnim = remember { Animatable(indicatorWidthPx) }
    val hAnim = remember { Animatable(indicatorHeightPx) }
    val scaleAnim = remember { Animatable(1f) }
    val glowAnim = remember { Animatable(0f) }

    /**
     * 点击切换时**指示器自己的平移动画**（覆盖 liveOffset 抢跑的问题）。
     *
     * ## 为什么必须有它
     *
     * 点击后 620ms 里，pager 的 currentPageOffsetFraction 会从 0 → ±1，
     * liveOffset 因此立刻开始变化。但指示器**不该**跟着这一帧帧的
     * 中途值抖动 —— 尤其当用户又点回去时，pager 动画会被反向覆盖，
     * 中途值会跳来跳去。
     *
     * 用一段独立、固定时长、固定曲线的动画来承担"点击位移"，
     * 就能保证：不管 pager 中途怎么变，指示器都是一条平滑的减速运动。
     *
     * NaN = 当前没有专属平移动画。
     */
    /**
     * 点击平移的当前位置（像素）。
     *
     * ## 为什么用普通 state 而不是 Animatable
     *
     * 实测：Animatable.snapTo 是挂起函数，写在 scope.launch 里要**下一帧**才生效，
     * 而渲染端在同一帧读 slideAnim.value 拿到的是 NaN —— 于是渲染分支
     * 一次都没进过（日志里 TabBarRender 一条都没有），动画等于不存在。
     *
     * 普通 Float state 的写入是同步的：本帧写、本帧渲染，不存在时序空窗。
     * NaN = 没有正在进行的平移。
     */
    var slideX by remember { mutableFloatStateOf(Float.NaN) }

    /** 当前平移任务的句柄，重复点击时取消上一段 */
    var slideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ==================== 拖拽跟手位置（同步 state）====================
    //
    // NaN 表示「没在拖拽」，此时位置回落到 liveOffset / xAnim。
    //
    // 用普通 mutableFloatStateOf 而不是 Animatable：
    // Animatable 的所有写入都是挂起函数，在手势的受限协程作用域里必须
    // 通过 scope.launch 逃逸，而 launch 是异步的 —— 逐帧驱动必然掉帧，
    // 表现就是「拖拽卡顿一下然后瞬移」。
    //
    // 普通 state 的写入是同步的，本帧写完下一帧就用它渲染，绝不丢帧。
    var pressX by remember { mutableFloatStateOf(Float.NaN) }
    // ==================== 位置只有 liveOffset 一个来源 ====================
    //
    // 曾经引入过 pressX / isPressed / posJob，用于在按住期间独立驱动指示器。
    // 结果是无穷的交接竞态：
    //   · snapTo 是挂起函数，isPressed 先置 true 会渲染一帧旧值 → 瞬移；
    //   · 松手时 pressX 与 liveOffset 谁先谁后 → 回弹、复读、闪烁；
    //   · 共用/分离 job 都只能缓解，不能根治。
    //
    // 现在按住、拖拽、松手全部通过驱动 pager 产生位移，
    // 指示器只是 liveOffset 的纯函数 —— 结构上不可能出现交接问题。

    /**
     * 本次 selectedIndex 变化是否来自**拖拽松手**。
     *
     * 为什么用普通 var 而不是 mutableStateOf：
     * 它只在 [LaunchedEffect] 内部被读一次，**不需要触发重组**。
     * 用 Snapshot 状态会引入时序竞争：写 true 后立即触发重组，
     * 但此时 selectedIndex 还没变，LaunchedEffect 的 key 不变就不会重启 ——
     * 等 selectedIndex 真变了，标记可能已经被消费或覆盖。
     * 普通 var 写入是同步的，LaunchedEffect 重启时读到的必然是最新值。
     */
    var pendingSwipeSettle = false

    fun tabWidthPx(): Float = if (tabs.isEmpty()) 0f else barWidthPx.toFloat() / tabs.size

    fun centerXFor(index: Int): Float {
        val tw = tabWidthPx()
        return tw * index + (tw - indicatorWidthPx) / 2f
    }

    /** 浮点索引 → 指示器像素 X。手势与渲染共用，避免两套坐标。 */
    fun offsetToX(off: Float): Float {
        val lo = off.toInt()
        val frac = off - lo
        val p0 = centerXFor(lo)
        val p1 = centerXFor(lo + 1)
        return p0 + (p1 - p0) * frac
    }

    /**
     * 此刻屏幕上**真实显示**的指示器 X。
     *
     * 与渲染处的优先级完全一致，因此可以安全地作为"新一段动画的起点"：
     * 从这个值开始动，视觉上就是连续的，不会跳。
     */
    fun currentRenderX(): Float {
        if (barWidthPx == 0) return 0f
        return when {
            !pressX.isNaN() -> pressX
            !slideX.isNaN() -> slideX
            liveOffset != null -> offsetToX(liveOffset)
            else -> xAnim.value
        }
    }

    /** 当前 pager 进度换算成像素；没有 pager 时返回 null。 */
    fun liveOffsetToX(): Float? {
        val off = liveOffset ?: return null
        if (barWidthPx == 0) return null
        return offsetToX(off)
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
     * 点击切换时，让指示器从"当前显示位置"平滑滑到目标 tab。
     *
     * ## 为什么必须在**松手瞬间**调用（而不是等 selectedIndex 回写）
     *
     * 上游 selectedIndex 要等 pager 翻页动画（620ms）**停稳后**才更新。
     * 如果在那里才开始动画，指示器早已被 liveOffset 带到终点，
     * 动画的起点和终点重合 —— 视觉上就是"没有任何动画"（真机录屏实测：
     * 179 帧里指示器位置零变化，但最终确实到了目标位置）。
     *
     * 所以动画必须在点击发生的**同一帧**启动，起点取当前渲染位置。
     *
     * ## 与 liveOffset 的关系
     *
     * 动画期间 liveOffset 会同步变化（pager 在翻页），但渲染优先级里
     * slideAnim 高于 liveOffset，所以指示器走自己的曲线，不受 pager 抖动影响。
     * 动画结束后 slideAnim 复位为 NaN，控制权交还 liveOffset（两者数值一致，无跳变）。
     */
    fun slideTo(target: Int, fromX: Float) {
        val from = fromX
        val to = centerXFor(target)
        if (abs(to - from) < 1f) return
        if (barWidthPx == 0) return
        if (slideJob != null && slideJob!!.isActive) slideJob?.cancel()
        // 【关键】同步写入，本帧立刻可见。绝不放进 launch 里 await。
        slideX = from
        slideJob = scope.launch {
            try {
                withFrameNanos { }
                val start = withFrameNanos { it }
                var done = false
                while (!done) {
                    withFrameNanos { now ->
                        val t = ((now - start) / 1_000_000f / INDICATOR_MOVE_MS)
                            .coerceIn(0f, 1f)
                        slideX = from + (to - from) * IosEaseOut.transform(t)
                        if (t >= 1f) done = true
                    }
                }
            } finally {
                slideX = Float.NaN
                slideJob = null
            }
        }
    }

    /**
     * 吸附到某个 tab。
     *
     * @param stretch 是否播放液滴拉伸。
     *
     * ## 为什么需要这个开关（真机踩坑）
     *
     * 滑动切页时，指示器由 [liveOffset] **直接跟手**，
     * 而 xAnim 停在旧位置没更新。等页面吸附到位、selectedIndex
     * 被回写后，这里会用 xAnim.value（旧位置）算出一段**整宽距离**，
     * 于是明明已经停在正确的 tab 上，又额外拉伸出一次 ——
     * 用户的原话是"界面停住了，最后还会往右边伸展一下，这没意义"。
     *
     * 所以：**滑动结束只做 snapTo 归位，不播放拉伸**；
     * 只有点击切换（指示器确实要“跳”过去）才播放拉伸。
     */
    fun settleTo(index: Int, fromX: Float, stretch: Boolean = true) {
        val target = centerXFor(index)
        val distance = abs(target - fromX)

        if (!stretch) {
            // 静默归位：滑动已经把手势带到目标位置了，
            // 这里只把内部状态对齐，**不产生任何可见动画**。
            // 用 snapTo 而不是极短的 animateTo：哪怕 1 帧的动画
            // 也会被感知成"停住后又弹一下"。
            scope.launch { xAnim.snapTo(target) }
            scope.launch { wAnim.snapTo(indicatorWidthPx) }
            scope.launch { hAnim.snapTo(indicatorHeightPx) }
            scope.launch { glowAnim.snapTo(0f) }
            return
        }

        // ---------- 点击切换：iOS 18 质感的慢速位移 ----------
        //
        // 不用 spring：spring 的物理回弹在"点一下"这种**明确的
        // 目标切换**里显得毛躁。Apple 的 tab 切换用的是一条
        // 平滑减速曲线，落点稳、没有多余弹跳。
        scope.launch {
            xAnim.animateTo(
                target,
                animationSpec = tween(
                    durationMillis = INDICATOR_MOVE_MS,
                    easing = IosEaseOut,
                ),
            )
        }

        val maxExtra = indicatorWidthPx * 0.55f
        val extra = (distance * 0.26f).coerceAtMost(maxExtra)
        if (extra > 2f) {
            // 液滴拉伸：先缓慢抻开，再更缓慢地收回。
            // 整个过程比之前慢一倍多 —— 用户明确要求"放慢，要优雅"。
            scope.launch {
                wAnim.animateTo(
                    indicatorWidthPx + extra,
                    tween(STRETCH_MS, easing = IosEaseOut),
                )
                wAnim.animateTo(
                    indicatorWidthPx,
                    tween(SETTLE_MS, easing = IosEaseInOut),
                )
            }
            scope.launch {
                val squash = 1f - (extra / (indicatorWidthPx + extra)) * 0.55f
                hAnim.animateTo(
                    (indicatorHeightPx * squash).coerceAtLeast(indicatorHeightPx * 0.70f),
                    tween(STRETCH_MS, easing = IosEaseOut),
                )
                hAnim.animateTo(
                    indicatorHeightPx,
                    tween(SETTLE_MS, easing = IosEaseInOut),
                )
            }
            scope.launch {
                glowAnim.animateTo(0.9f, tween(STRETCH_MS, easing = IosEaseOut))
                glowAnim.animateTo(0f, tween(GLOW_FADE_MS, easing = IosEaseInOut))
            }
        }
    }

    LaunchedEffect(barWidthPx) {
        if (barWidthPx > 0) xAnim.snapTo(centerXFor(selectedIndex))
    }

    /**
     * 【关键】让 xAnim 持续跟随 pager 的真实位置。
     *
     * 之前 xAnim 只在 settleTo 时才动，而滑动/点击翻页期间
     * 指示器由 liveOffset 渲染 —— 两者脱节。
     *
     * 后果：翻页结束后 settleTo 用陈旧的 xAnim.value 算距离，
     * 得出一个巨大的值，于是又拉一次。
     *
     * 现在把 xAnim 当成"内部记录的位置"，每帧跟 liveOffset 对齐。
     * 这样任何后续计算里 fromX 都是真实位置，distance ≈ 0，
     * 从根上消除了"二次拉伸"的可能。
     */
    LaunchedEffect(barWidthPx) {
        if (barWidthPx == 0) return@LaunchedEffect
        // 用 snapshotFlow 而不是 LaunchedEffect(liveOffset)：
        // 后者每帧都会取消并重启协程，开销大且会打断 snapTo。
        // snapshotFlow 只在一帧一次地把新值推给 collect。
        snapshotFlow { liveOffset }
            .collect { off ->
                if (off == null) return@collect
                // 用与渲染完全相同的公式把进度换算成像素位置
                val lo = off.toInt()
                val frac = off - lo
                val p0 = centerXFor(lo)
                val p1 = centerXFor(lo + 1)
                xAnim.snapTo(p0 + (p1 - p0) * frac)
            }
    }

    // 唯一驱动指示器动画的地方（点击切换与滑动切页最终都走这里）
    LaunchedEffect(selectedIndex, barWidthPx) {
        if (barWidthPx == 0) return@LaunchedEffect

        // ========== 为什么这里几乎总是不该拉伸 ==========
        //
        // 指示器的**可见位置**有两套驱动源：
        //   A. liveOffset（pager 连续进度）→ 拖拽、点击翻页动画期间都走这条
        //   B. xAnim（settleTo 的动画目标）
        //
        // 只要 liveOffset != null，指示器就已经被 pager 连续驱动到位了。
        // 此时再让 settleTo 播放拉伸，就会在"已经到位"之后
        // **额外拉一次然后回缩** —— 正是用户反复反馈的：
        //   「指示器已经完全横移过去了，最后收尾却会突然拉伸一下再回缩」
        //
        // 早期只在拖拽时置 pendingSwipeSettle=true，
        // 点击 tab 时漏了 —— 而点击同样会启动 animateScrollToPage，
        // 期间 liveOffset 一直在动，松手后 selectedIndex 才回写，
        // 于是又触发一次拉伸。这是根因。
        //
        // 现在的规则：
        //   · liveOffset 由 pager 提供（非 null）→ 永远静默归位
        //   · 没有 pager 进度（纯本地切换）→ 才播放拉伸
        val drivenByPager = liveOffset != null
        val byDrag = pendingSwipeSettle
        pendingSwipeSettle = false

        // ============ 点击切换的收尾 ============
        //
        // 平移动画**不在这里**启动 —— 这个 LaunchedEffect 要等
        // selectedIndex 回写（620ms 翻页动画结束）才跑，
        // 那时指示器已经被 liveOffset 带到位了，再动画等于原地重播（无可见位移）。
        // 真正的启动点在手势松手处（见 pointerInput 里 slideTo()）。
        //
        // 这里只做一件事：把内部记录位置对齐到最终值，供下一次计算使用。
        if (!byDrag) {
            scope.launch { xAnim.snapTo(centerXFor(selectedIndex)) }
        }

        settleTo(
            index = selectedIndex,
            fromX = xAnim.value,
            // 一律不拉伸：拖拽已有跟手位置，点击有专属平移动画，
            // 再叠液滴拉伸会变成"移动 + 拉伸"两段动作。
            stretch = false,
        )

        // ============ 交接：pressX → liveOffset ============
        //
        // selectedIndex 已经更新（说明 pager 也到位了），此时才把
        // 拖拽用的临时位置清掉，让渲染改由 liveOffset 驱动。
        //
        // 必须在**这里**清而不是在手势松手时清：
        // 松手时 pager 还在翻页，liveOffset 仍是旧值，
        // 提前清会让指示器先跳回原页，再跟着翻过去。
        pressX = Float.NaN
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
        //   2. 对采样结果做模糊 + 折射 + 色散（由 Kyant0/AndroidLiquidGlass 完成）。
        //
        // 用 LiquidGlassPanel 而不是自己画背景，就是拿到这套光学管线。
        // 这也是 iOS 底栏"能透出底下内容"的真正原因 —— 不是透明度，
        // 是**折射**。
        // ================= 第一层：液态玻璃容器 =================
        //
        // 【第十一轮】换成 Kyant0/AndroidLiquidGlass（4600 星）。
        //
        // 之前用 PrismalAGSL 时，blurRadius 在本机完全不生效 ——
        // 14dp / 60dp / 200dp 三张截图逐像素一致，背后文字始终锐利。
        // 试过 RenderEffect 链、RenderNode 中转、Bitmap 拷贝、
        // 录制层预模糊等七八种绕过方案，全部卡在同一个物理限制上：
        // Compose 的 GraphicsLayer 无法在绘制阶段同步读成像素。
        //
        // 新库自带 Backdrop 录制 + 库内模糊/折射，不走 Compose 效果链，
        // 所以那一环终于能生效。
        val liquidBackdrop = com.redmi.autoattend.ui.glass.LocalLiquidBackdrop.current
        if (liquidBackdrop != null) {
            com.redmi.autoattend.ui.glass.LiquidGlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .onSizeChanged {
                        barWidthPx = it.width
                        // 回写给上游：拖拽换算 tab 宽度时需要底栏真实宽度
                        onBarWidth(it.width.toFloat())
                    },
                cornerRadius = barHeight / 2,
                // 模糊：28dp ≈ 91px。实测 14dp 太弱（背后文字一望即知），
                // 28dp 才能把笔画糊成团块、需要凑近辨认。
                // 官方 LiquidBottomTabs 用 8dp；弹窗用 16dp（浅）/ 8dp（深）。
                // 这个库的 blur 本来就是"轻微柔化"，不是"糊成团块" ——
                // 所有官方示例的半径都在 2–16dp 之间。
                blurRadius = if (colors.isDark) 8.dp else 16.dp,
                // 【关键】tintAlpha 决定"下层内容直接透出多少"。
                //
                // tint 留空 → 由 LiquidGlassPanel 按主题自动选：
                //   浅色 = 亮白（0xFFFAFAFA）
                //   深色 = 近黑（0xFF121212）
                //
                // 【为什么不能写死白色】
                //
                // 深色主题下，白 tint 叠在深蓝紫背景上，数学上必然趋向灰 ——
                // 底栏会变成一块灰紫色，和旁边蓝青色的卡片完全不搭。
                // 这正是"深色下风格不统一"的根因。
                //
                // 深色改用近黑 tint 后，玻璃只压暗不泛灰，
                // 色相仍由背后的内容决定，于是和卡片自然同色系。
                // tint 走默认值（深色近黑 / 浅色亮白），色相交给主题决定。
                //
                // 但 tintAlpha 要**比卡片更轻**：
                //
                // 深度上，底栏位于屏幕最下方，而流动背景是「上蓝下紫」的渐变，
                // 底部本来就是最暗最紫的一段。卡片在中部采到亮蓝，
                // 底栏在底部采到深紫 —— 同样的 0.40 叠上去，
                // 底栏会明显更黑更沉，和卡片不像一个体系。
                //
                // 把底栏的 tint 降到 0.28，让它多透出一些背景色，
                // 亮度就能和卡片拉近，同时仍压得住下层文字。
                // 深色下显式指定一个偏蓝的 tint，把底栏从背景的紫色里拉回来。
                // 背景渐变在屏幕底部本来就是紫的，只靠中性黑压不住色相。
                tint = if (colors.isDark) Color(0xFF0E1B33) else Color(0xFFFAFAFA),
                tintAlpha = if (colors.isDark) 0.45f else 0.40f,
                // 关掉边缘高光：它画在内容之上，会盖住贴边的「设置」文字。
                highlightAlpha = 0f,
                dispersionOverride = if (colors.isDark) 0.45f else 0.38f,
            ) {
                // 内容为空 —— 文字和指示器都画在它上面
                Box(Modifier.fillMaxWidth().height(barHeight))
            }
            // ================= 第三层：文字 + 手势 =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    // ============ 手势：指示器自己走，松手才交给 pager ============
                    //
                    // ## 用户要的交互
                    //
                    //   按下 → 指示器滑到手指处并放大
                    //   移动 → 指示器完全跟手
                    //   松手 → 落位到最近 tab + 缩小 + 页面切过去
                    // ============ 手势：位置只由 pager 驱动 ============
                    //
                    // ## 设计原则（本轮彻底重构）
                    //
                    // 指示器的位置**只有 liveOffset 一个来源**，它是 pager 连续进度的
                    // 纯函数。按住、拖拽、松手全部通过驱动 pager 来产生位移，
                    // 因而结构上不可能出现「两个位置源对不上」的交接问题。
                    //
                    // ## 之前所有 bug 的共同根因
                    //
                    // 我一直在试图让指示器在按住期间「自己动」（pressX），
                    // 松手时再把控制权交还给 liveOffset。凡是有交接，就有竞态：
                    //   · snapTo 是挂起函数，先置标志位会渲染一帧旧值 → 瞬移；
                    //   · 两边时长/曲线不一致 → 一个有一个没、不对称；
                    //   · 共用 job 会互相取消 → 放大被打断、动画卡住；
                    //   · 父级 pointerInput 吞掉子级 Clickable → 点击要两次。
                    //
                    // 现在没有交接：按下就把 pager 平滑推到手指位置，
                    // 拖拽就让 pager 跟手，松手就让 pager 落到最近页。
                    // 指示器始终只是「跟着 pager 走」，不需要任何状态切换。
                    //
                    // ## 点击由子级 Clickable 处理，这里绝不重复触发
                    //
                    // 这是「点击要两次」的根因：父级手势与子级 Clickable 各调一次
                    // onSelect，第一次切过去、第二次又切回来。
                    // 现在父级只在**确实发生拖拽**（超过 touchSlop）时才切页，
                    // 纯点按一律交给子级，绝不重复。
                    // ============ 手势：同步驱动 pager，位置只有 liveOffset ============
                    //
                    // ## 三个 bug 的共同根因
                    //
                    // 之前拖拽只写本地 dragFrac / 或用异步 scrollToPage，两种情况都错：
                    //   ① 只写本地 → 指示器跟手了，但 pager 没动，liveOffset 还是旧值，
                    //      松手时一交还控制权就「瞬移回首页再播一遍动画」；
                    //   ② 异步 scrollToPage → 每帧一个协程被合并/丢弃，丢失中间动画、
                    //      「长按时指示器不跟手」。
                    //
                    // ## 现在：dispatchRawDelta（同步）
                    //
                    // 它是**非挂起**函数，在手指事件里直接同步改 pager 滚动状态，
                    // 于是 pager 真正逐帧跟手 → liveOffset 连续 → 指示器（liveOffset
                    // 的纯函数）自然跟手。松手时 liveOffset 已经等于目标位置，
                    // 不需要任何交接，也就没有瞬移和复读。
                    //
                    // 点击也走同一条路径：按下就让 pager 滑过去（onPressTab），
                    // 松手不再触发第二次，因此不会「点两次才切」。
                    .pointerInput(barWidthPx, tabs.size, indicatorWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            /** 手指 x → 指示器左上角 x（中心对齐手指） */
                            fun xFor(fingerX: Float): Float {
                                val tw = tabWidthPx()
                                if (tw <= 0f) return centerXFor(currentSelected)
                                return (fingerX - indicatorWidthPx / 2f)
                                    .coerceIn(0f, (barWidthPx - indicatorWidthPx).coerceAtLeast(0f))
                            }

                            /** 手指 x → 最近的 tab 索引 */
                            fun tabFor(fingerX: Float): Int {
                                val tw = tabWidthPx()
                                if (tw <= 0f) return currentSelected
                                return (fingerX / tw).toInt().coerceIn(0, tabs.lastIndex)
                            }

                            // ============================================================
                            // 模型：拖拽期间**页面完全不动**，只有指示器跟手。
                            //        松手才切页。
                            // ============================================================
                            //
                            // ## 为什么不用「拖拽同时驱动 pager」
                            //
                            // 试过三种驱动 pager 的方式，全部失败：
                            //   · scope.launch { scrollToPage() } —— 异步，帧间被合并，
                            //     丢失中间动画、卡顿后瞬移；
                            //   · dispatchRawDelta —— 增量会被 pager 的 snap 吸回最近的页；
                            //   · 绝对位置 scrollToPage —— 仍是 launch 异步，同样卡顿。
                            //
                            // 根因：手势循环跑在**受限协程作用域**里，任何挂起调用都必须
                            // 通过 scope.launch 逃逸，而 launch 是异步调度 —— 逐帧驱动必然
                            // 掉帧。这不是参数问题，是结构问题。
                            //
                            // ## 正确模型（也是用户最初描述的交互）
                            //
                            //   按下 → 放大；
                            //   拖拽 → 指示器同步跟手（写 float state，无协程，永不掉帧）；
                            //   松手 → 指示器落位 + 页面切过去（两条动画同时开始、同时结束）。
                            //
                            // 指示器跟手用普通 state：写入是同步的，下一帧必定用新值，
                            // 不存在调度延迟，因此丝滑无卡顿。

                            // ---------- 按下：放大 ----------
                            //
                            // 同时把"按下这一刻指示器在哪"记下来。
                            // 松手时的平移动画必须从**这个位置**出发，
                            // 而不是从 currentRenderX() 再读一次 ——
                            // 那时 liveOffset 已经开始动了，读出来已接近终点。
                            val pressStartX = currentRenderX()

                            scope.launch {
                                scaleAnim.animateTo(
                                    dragScale,
                                    tween(PRESS_MS, easing = IosEaseOut),
                                )
                            }

                            // ---------- 移动：同步跟手 ----------
                            var dragged = false
                            var lastX = down.position.x
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                lastX = ch.position.x
                                if (!dragged && kotlin.math.abs(lastX - down.position.x) > slop) {
                                    dragged = true
                                }
                                if (dragged) {
                                    ch.consume()
                                    // 同步写入普通 state：本帧立即生效，绝不掉帧
                                    pressX = xFor(lastX)
                                }
                            }

                            // ---------- 松手 ----------
                            scope.launch {
                                scaleAnim.animateTo(
                                    1f,
                                    tween(RELEASE_MS, easing = IosEaseOut),
                                )
                            }

                            // 目标：拖拽看松手位置，点按看按下位置。
                            // 只由手指决定，与 pager 当前状态无关 —— 这样不会误切页。
                            val target = if (dragged) tabFor(lastX) else tabFor(down.position.x)

                            // ============ 落点判断必须用「请求值」而不是「停稳值」 ============
                            //
                            // 旧代码用 currentSelected（= 上游 selectedIndex），
                            // 而它要等翻页动画停稳（620ms）才回写。
                            // 于是动画期间点回原页会被判成"目标就是当前页"→ 静默吞掉。
                            // 这就是「刚切换的 0.5 秒内点不动、拖不动」的根因。
                            val effectiveCurrent = if (pendingTarget != currentSelected) {
                                pendingTarget
                            } else {
                                currentSelected
                            }

                            if (target != effectiveCurrent) {
                                pendingSwipeSettle = true
                                pendingTarget = target

                                // 【关键修正】不要再 pressX = centerXFor(target) 瞬间赋值。
                                //
                                // 那是「点一下就瞬移」的**直接原因**：
                                // pressX 是同步写入的普通 state，赋值当帧就渲染，
                                // 指示器位置从当前点直接跳到目标位置，中间没有任何过渡。
                                //
                                // 上一轮之所以这么写，是为了绕开"松手后 pressX 清空 →
                                // 渲染回落到 liveOffset（还是旧值）→ 先跳回原页"的问题。
                                // 但正确的解法不是瞬移到目标，而是**先清空 pressX，
                                // 再让指示器按上一轮就有的平移规则从当前位置滑过去** ——
                                // 见下面渲染处的 slideFromX。
                                pressX = Float.NaN

                                // 【关键】点击必须在**这一帧**就启动平移动画。
                                // 拖拽不需要：指示器已经在手指位置，
                                // 松手只需让 pager 翻过去，指示器静默归位即可。
                                if (!dragged) {
                                    // 起点必须是**点击之前**指示器所在的位置。
                                    //
                                    // 不能在里面再读一次 currentRenderX()：
                                    // 点击瞬间 liveOffset 已经开始向目标页滑动，
                                    // xAnim 也被那两条 LaunchedEffect 同步过去了，
                                    // 读出来 ≈ 终点 → abs(to-from) < 1 → 直接 return，
                                    // 动画根本不启动（真机录屏 179 帧零位移的真因）。
                                    //
                                    // pressStartX 是按下那一刻捕获的，稳定可靠。
                                    slideTo(target, pressStartX)
                                }
                                currentOnSelect(target, dragged)
                            } else {
                                // 目标就是当前页：直接把跟手值交还给 liveOffset。
                                pressX = Float.NaN
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == selectedIndex

                    // ============ 文字颜色：跟随指示器位置实时插值 ============
                    //
                    // ## 为什么不能再用 animateColorAsState(selected)
                    //
                    // 用户反馈：「指示器拖过去了，蓝字还要等 1 秒左右才变，而且生硬」。
                    //
                    // 根因链：
                    //   1. selected 来自 selectedIndex，只在 pager **停稳后**才回写；
                    //   2. 拖拽全程 selected 不变 → 颜色不准备变；
                    //   3. 松手后还要跑完翻页动画，selectedIndex 才更新；
                    //   4. 更新后再走 tween —— 叠加起来就是「等一秒」。
                    //
                    // ## 正确模型：颜色是位置的函数
                    //
                    // 指示器位置逐帧变化，颜色也应该逐帧跟随：
                    // 拖到 70% 就蓝 70%，与指示器**永远同相**，零延迟。
                    //
                    // 权重 = 1 - |当前位置 - 本 tab 索引|，天然连续。
                    // 与指示器**同一个来源**：liveOffset。
                    // 拖拽时 pager 跟着手指走，liveOffset 连续变化，
                    // 颜色因此连续渐变，和指示器永远同相。
                    val livePos = when {
                        // 点击平移动画进行中：颜色跟着指示器的实际位置走，
                        // 否则会出现"指示器已经滑到设置，文字还是首页蓝"。
                        !slideX.isNaN() -> {
                            val tw = tabWidthPx()
                            if (tw > 0f) {
                                val center = slideX + indicatorWidthPx / 2f
                                ((center - tw / 2f) / tw).coerceIn(0f, tabs.lastIndex.toFloat())
                            } else selectedIndex.toFloat()
                        }
                        !pressX.isNaN() -> {
                            val tw = tabWidthPx()
                            if (tw > 0f) {
                                val center = pressX + indicatorWidthPx / 2f
                                ((center - tw / 2f) / tw).coerceIn(0f, tabs.lastIndex.toFloat())
                            } else selectedIndex.toFloat()
                        }
                        liveOffset != null -> liveOffset
                        else -> selectedIndex.toFloat()
                    }
                    val colorWeight = (1f - kotlin.math.abs(livePos - index)).coerceIn(0f, 1f)

                    // 未选中项的颜色要**自己定**，不能直接用 textTertiary：
                    // 它的 alpha 只有 43%/50%，叠在半透明玻璃上几乎看不见
                    // （实测「设置」直接消失）。
                    val activeColor =
                        if (colors.isDark) colors.textPrimary else Color(0xFF1A73E8)
                    val idleColor =
                        if (colors.isDark) Color(0xCCD8E2F0) else Color(0xCC2A3140)

                    // 平滑：位置本身是连续的，再叠一层极短的补间只为消除逐帧抖动。
                    // 时长刻意很短（90ms）——它只是低通滤波，不是「变色动画」，
                    // 太长就会重现用户抱怨的「等一秒」。
                    val smoothWeight by animateFloatAsState(
                        targetValue = colorWeight,
                        animationSpec = tween(90, easing = IosEaseOut),
                        label = "tab-color-weight",
                    )
                    val contentColor =
                        androidx.compose.ui.graphics.lerp(idleColor, activeColor, smoothWeight)

                    // 字重：过半才加粗，避免中途来回跳
                    val fontWeight =
                        if (smoothWeight > 0.5f) FontWeight.SemiBold else FontWeight.Normal

                    // 【不要在这里挂 Clickable】
                    //
                    // 这是「点击要两次」的直接原因：子级 Clickable 触发一次 onSelect，
                    // 父级 pointerInput 松手时又触发一次，第一次切过去、第二次切回来。
                    //
                    // 现在所有输入统一由父级手势处理（点按与拖拽都在那里判定），
                    // 子级只负责显示文字，不接收点击。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = contentColor,
                            fontSize = 13.sp,
                            fontWeight = fontWeight,
                            letterSpacing = 0.3.sp,
                        )
                    }
                }
            }
        } else {
            // 采样源还没准备好时的兜底，避免首帧闪一下空白
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .background(
                        color = Color.White.copy(alpha = 0.10f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(barHeight / 2),
                    ),
            )
        }


        // ================= 第二层：指示器（不裁剪，浮在外围之上） =================
        val hDp = with(density) { hAnim.value.toDp() }
        val wDp = with(density) { wAnim.value.toDp() }

        Box(
            modifier = Modifier
                .offset {
                    // ============ 位置来源 ============
                    //
                    // 只有 liveOffset（pager 连续进度）。
                    // 按住、拖拽、松手落位全部通过驱动 pager 产生位移，
                    // 因此这里不需要任何「拿起态」分支 —— 也就没有了交接竞态。
                    // 优先级：
                    //   1. 拖拽中（pressX 非 NaN）→ 直接用同步值，逐帧跟手；
                    //   2. pager 有进度 → liveOffset；
                    //   3. 首帧 → xAnim。
                    // 优先级（与 currentRenderX() 必须完全一致）：
                    //   1. 拖拽中（pressX 非 NaN）→ 同步值，逐帧跟手；
                    //   2. 点击平移动画进行中（slideAnim 非 NaN）→ 自己的一段减速运动；
                    //   3. pager 有进度 → liveOffset；
                    //   4. 兜底 → xAnim。
                    //
                    // 把 2 排在 3 前面是修「点一下直接瞬移」的关键：
                    // 点击后 pager 的 offsetFraction 会立刻从 0 变到 ±1，
                    // 若让 liveOffset 抢跑，指示器就没有可感知的过渡。
                    val cx = when {
                        !pressX.isNaN() -> pressX
                        !slideX.isNaN() -> slideX
                        liveOffset != null -> offsetToX(liveOffset)
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
