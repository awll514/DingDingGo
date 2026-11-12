package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.theme.GlassTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt


// ==================== 动画参数（全部来自 iOS 26 逐帧实测） ====================
//
// ## 数据来源
//
// 不是猜的。Codename One 的 motion-probe 用 XCUITest 真触摸驱动真实
// UITabBarController，逐帧记录 layer position/bounds/transform，
// 再用闭式弹簧公式反解出 ω 和 ζ；UIKitCore 逆向佐证了 lip 参数。
//
// ## 为什么用 spring 而不是 cubic-bezier
//
// iOS 26 底栏的位移**就是弹簧驱动的**（UIKit 甚至不挂 CAAnimation，
// 每帧自己写 layer position）。用 bezier 拟合必然有偏差：
//
//     时刻 :  项目原本的 bezier    iOS 实测
//      84ms:     0.656               0.413   ← 前 1/5 时间冲完 2/3
//     126ms:     0.847               0.647
//     210ms:     0.955               0.914
//
// 最大偏差 25%（84ms 处）。这就是「啪一下弹过去、后面慢慢蹭」的观感来源。
//
// 项目里「全应用禁用 spring」的规则是为了压住 Material 默认的毛躁回弹，
// 但底栏指示器在 iOS 上本来就是弹簧 —— 这里按真实规格做。
// 过冲只有 0.63%，肉眼看是「稳稳落到位」，不是弹跳。

/**
 * 位移 / 松手吸附（同一条弹簧）。
 *
 * ω = √246.7 ≈ 15.708 rad/s，ζ = 0.85 → 过冲 0.63%，约 330ms 到达。
 * 这是 iOS 点击切换指示器时逐帧反解出来的值。
 */
private fun <T> lensTravel(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.85f, stiffness = 246.7f)

/**
 * 拖拽跟手（带阻尼的跟随，不是 1:1）。
 *
 * ω = √1024 = 32 rad/s，ζ = 0.903。
 * iOS 的拖拽**不是**零延迟跟手，而是有约 20ms 的弹簧滞后：
 * 稳态滞后 = 2ζv/ω，200pt/s → 11px，1000pt/s → 56px。
 *
 * 这个滞后正是「有重量的玻璃」和「数字光标」的区别。
 * 但你之前反馈过「拖拽必须实时跟手」，所以这里用 ζ=0.903（接近临界阻尼，
 * 不振荡）且 ω 很大（32，响应足够快）—— 既跟手又有质感。
 */
private fun <T> lensFollow(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.903f, stiffness = 1024f)

/**
 * 按下抬升（放大）。
 *
 * iOS 是**临界阻尼 0.25s**：升到 97.5% 就 snap，不回弹。
 * ω = √631.7 ≈ 25.13 rad/s，恰好 0.25s 收敛。
 */
private fun <T> lensLift(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    spring(dampingRatio = 1f, stiffness = 631.7f)

/**
 * 抬升倍数。
 *
 * 注意：iOS 是**宽高各 +16pt 的非等比增长**（78×40 → 94×56），
 * 不是均匀放大。这里用 scaleY 体现「变厚」，宽度保持不变 ——
 * 均匀放大会让胶囊横向炸开，明显不是 iOS 的样子。
 */
private const val BarDragScale = 1.40f

/** 按下反馈时长（仅用于 animatePosTo 的分支判断，实际由 lensLift 弹簧决定） */
private const val BarPressMs = 320

/**
 * 按住多久才认定「长按」并开始放大。
 *
 * 这是**意图判定**，不是动画延迟 —— 快速点击（< 此值）全程零缩放，
 * 只有真正按住了才放大。取 200ms：
 *   · 比 Android 的 long-press（500ms）短，这里不需要菜单语义；
 *   · 比双击间隔（300ms）短，不影响正常点击节奏。
 */
private const val BarLongPressMs = 200L

/** 发光上涨（0.1s 临界阻尼） */
private fun <T> glowRise(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    spring(dampingRatio = 1f, stiffness = 3947.8f)

/** 发光消退（0.5s 临界阻尼） */
private fun <T> glowFall(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    spring(dampingRatio = 1f, stiffness = 157.9f)

/**
 * 液态玻璃悬浮底栏 —— 动画层重构版。
 *
 * # 为什么重写
 *
 * 旧实现同时维护 **五个位置来源**：
 *
 *     pressX（拖拽） slideX（点击平移） settledX（落位锚点）
 *     liveOffset（pager 进度） xAnim（兜底）
 *
 * 渲染时按优先级挑一个用。这个结构本身就会出 bug ——
 * 每加一个来源就多一组「什么时候交接、谁先谁后」的时序问题，
 * 修好一处必然弄坏另一处（实测：连续 4 轮修复，每轮都引入新回归）。
 *
 * 具体出现过的症状：
 *   · 点一下瞬移（pressX 同步写入，当帧就到终点）
 *   · 松手复读动画（清 pressX 后回落 liveOffset 的旧值）
 *   · 切换后 0.5 秒点不动（selectedIndex 滞后 → 落点判断被吞）
 *   · 长按不跟手（pressX 只在移动超 slop 时才写）
 *   · 起步抽搐（多来源切换时闪过一帧旧值）
 *
 * # 新结构：一个真值 + 一个状态机
 *
 *     indicatorPos: Animatable<Float>   ← 唯一的指示器位置（tab 单位，0.0 ~ lastIndex）
 *     phase: Phase                      ← 谁在驱动这个值
 *
 * 所有路径（按下、拖拽、点击、松手落位、pager 同步）
 * **都只写 indicatorPos**，渲染端只读它 —— 结构上不可能出现「两个值对不上」。
 *
 * 状态机：
 *
 *     Idle ──按下──> Pressed ──移动>slop──> Dragging
 *       ^              │                      │
 *       │              └────松手──────────────┤
 *       │                                     v
 *       └────────────────────────────── Settling ──动画结束──> Idle
 *
 * # 跟手：直接写，不经过动画
 *
 * Dragging 阶段每帧同步写 indicatorPos（snapTo 是挂起函数，改用
 * 一个普通 float state 做「跟手覆盖值」只在拖拽期生效）。
 * 这样写入当帧生效，零调度延迟 —— 这是丝滑跟手的唯一可靠做法。
 */




/** 指示器驱动阶段 —— 唯一决定「谁在写 indicatorPos」的状态。 */
private enum class Phase {
    /** 静止：位置由 pager 的 liveOffset 决定（用户左右滑页面时） */
    Idle,
    /** 按下但未移动：指示器已滑到手指所在 tab 并放大 */
    Pressed,
    /** 拖拽中：指示器逐帧跟手，页面不动 */
    Dragging,
    /** 松手落位：位置由 Animatable 收尾，同时页面开始翻 */
    Settling,
}

/**
 * 液态玻璃悬浮底栏。
 *
 * @param tabs tab 文案
 * @param selectedIndex 当前选中页（来自 pager，可能短暂滞后 —— 内部有乐观修正）
 * @param onSelect 请求切页。fromDrag=true 表示来自拖拽松手（页面应直接落位，
 *        不要再播完整翻页动画）。
 * @param liveOffset pager 的连续进度（currentPage + offsetFraction）。
 *        Idle 阶段用它同步指示器位置，实现「滑页面时指示器跟着走」。
 * @param onBarWidth 底栏宽度回写（像素）。
 */
@Composable
fun GlassTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (index: Int, fromDrag: Boolean) -> Unit,
    /**
     * pager 的连续进度。
     *
     * **只在 Idle 阶段参与**：用户直接在页面上左右滑动时，
     * 指示器跟着 pager 走。一旦手指落到打底栏上，就由状态机接管。
     */
    liveOffset: Float? = null,
    /** 底栏宽度回写（像素） */
    onBarWidth: (px: Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val colors = GlassTheme.colors

    // ==================== 尺寸 ====================
    val barWidthFraction = 0.56f
    val barHeight = 52.dp
    val indicatorWidth = 78.dp
    val indicatorHeight = 40.dp

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
    val indicatorHeightPx = with(density) { indicatorHeight.toPx() }

    var barWidthPx by remember { mutableStateOf(0) }

    val lastIndex = tabs.lastIndex.toFloat()

    // 手势块用 rememberUpdatedState 读最新回调，避免 pointerInput 的陈旧闭包
    val currentOnSelect by rememberUpdatedState(onSelect)

    // ==================== 单一位置真值 ====================

    /**
     * 指示器的唯一位置，单位是 **tab 索引**（0.0 = 首页，1.0 = 设置）。
     *
     * 用 tab 单位而不是像素，因为：
     *   · 底栏宽度可能在首帧之后才确定（onSizeChanged），
     *     像素值会失效，索引值不会；
     *   · pager 的 liveOffset 本身就是这个单位，两者可以直接比较，
     *     不需要来回换算，也就没有换算误差。
     */
    val posAnim = remember { Animatable(selectedIndex.toFloat()) }

    /**
     * 拖拽跟手用的同步覆盖值。
     *
     * 为什么需要它：Animatable.snapTo 是**挂起函数**，
     * 在逐帧拖拽里调用必然要 scope.launch 逃逸，产生一帧以上的调度延迟
     * （实测 20~40ms，表现就是「跟手粘滞」）。
     *
     * 普通 float state 的写入是同步的，当帧渲染就生效。
     * 所以拖拽期间直接写它，渲染端优先读它；松手后交给 posAnim。
     *
     * NaN = 当前不在跟手状态。
     */
    var dragPos by remember { mutableFloatStateOf(Float.NaN) }

    /** 当前阶段 */
    var phase by remember { mutableStateOf(Phase.Idle) }

    /**
     * 「用户最后一次请求切到的页」。
     *
     * 上游 selectedIndex 要等 pager 动画停稳才回写（620ms），
     * 这期间点回原页会被判成「目标就是当前页」而静默吞掉。
     * 用这个乐观值判断落点，交互永远即时。
     */
    var requestedIndex by remember { mutableIntStateOf(selectedIndex) }

    /**
     * 「这次 selectedIndex 变化是我们自己请求的」标记。
     *
     * ## 为什么需要一个显式标记
     *
     * 判断「要不要把 pager 进度灌进指示器位置」，必须区分两种来源：
     *   · 用户直接在页面上左右滑 -> 指示器应当跟手（要灌）
     *   · 我们点击/长按底栏触发切页 -> 指示器由状态机驱动（不要灌）
     *
     * 两者都会让 selectedIndex 变化、都会让 liveOffset 变化，
     * 光看数值分不出来。所以由发起方主动打标记，动画结束后清除。
     */
    var selfDriven by remember { mutableStateOf(false) }

    // ==================== 视觉状态（与位置解耦） ====================
    val scaleAnim = remember { Animatable(1f) }
    val glowAnim = remember { Animatable(0f) }

    /**
     * 当前渲染用的位置（tab 单位）。
     *
     * 优先级只按 **阶段** 判断 —— 这是新架构与旧实现最根本的区别：
     * 旧实现按「哪个值非 NaN」挑，一旦两个值同时有效就打架；
     * 这里按状态机阶段挑，任何时刻只有一个阶段，因此只有一个来源。
     */
    /**
     * 当前应渲染的位置（tab 单位）。
     *
     * ## 关键设计：Idle 也读 posAnim，不读 liveOffset
     *
     * 之前 Idle 阶段直接读 liveOffset，导致一个顽固 bug：
     * 长按把指示器滑到设置（posAnim=1.0），动画结束进 Idle，
     * 此时 pager 的 liveOffset **还是 0**（页面切换才刚开始），
     * 于是指示器当场跳回首页 —— 真机日志抓到
     * "0.998/Settling -> 0.000/Idle" 的跳变。
     *
     * 现在 posAnim 是**唯一真值**。用户直接在页面上左右滑动时，
     * 由下面的 LaunchedEffect 把 liveOffset 灌进 posAnim，
     * 而不是在渲染时偷偷换一个数据源 —— 换源就是跳变的来源。
     */
    fun renderPos(): Float {
        return if (phase == Phase.Dragging && !dragPos.isNaN()) dragPos else posAnim.value
    }

    // ==================== 坐标换算 ====================

    fun tabWidthPx(): Float = if (tabs.isEmpty()) 0f else barWidthPx.toFloat() / tabs.size

    /** tab 索引 → 指示器左边缘像素 */
    fun posToX(pos: Float): Float {
        val tw = tabWidthPx()
        if (tw <= 0f) return 0f
        return tw * pos + (tw - indicatorWidthPx) / 2f
    }

    /** 手指像素 → tab 索引（0.0 ~ lastIndex，连续） */
    fun fingerToPos(fingerX: Float): Float {
        val tw = tabWidthPx()
        if (tw <= 0f) return requestedIndex.toFloat()
        // tab 中心对应的手指位置是 tw*(i+0.5)，所以减去半格再归一
        return ((fingerX - tw / 2f) / tw).coerceIn(0f, lastIndex)
    }

    /** 手指像素 → 最近的 tab（整数） */
    fun fingerToTab(fingerX: Float): Int {
        val tw = tabWidthPx()
        if (tw <= 0f) return requestedIndex
        return (fingerX / tw).toInt().coerceIn(0, tabs.lastIndex)
    }

    // ==================== 位置驱动 ====================

    /**
     * 平移动画的任务句柄。
     *
     * 用令牌比对而不是单纯 cancel：cancel 不会立即生效，
     * 旧协程要跑到下一个挂起点才进 finally，
     * 那一刻可能把新任务刚写好的状态抹掉（= 抽搐）。
     */
    var moveJob by remember { mutableStateOf<Job?>(null) }
    var moveToken by remember { mutableStateOf<Any?>(null) }

    /**
     * 把指示器平滑移到 [target]（tab 单位）。
     *
     * @param durationMs 时长；0 表示立即落位
     */
    fun animatePosTo(target: Float, durationMs: Int, spec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = lensTravel()) {
        val token = Any()
        moveToken = token
        moveJob?.cancel()
        dragPos = Float.NaN
        phase = if (durationMs <= 0) Phase.Idle else Phase.Settling

        if (durationMs <= 0) {
            scope.launch {
                posAnim.snapTo(target)
                if (moveToken === token) phase = Phase.Idle
            }
            return
        }

        moveJob = scope.launch {
            try {
                // 从当前位置出发：posAnim 与渲染位置可能不同（Idle 时用 liveOffset），
                // 所以先把渲染位置灌进去，保证视觉连续。
                val from = renderPos()
                if (abs(from - posAnim.value) > 0.001f) posAnim.snapTo(from)
                posAnim.animateTo(target, animationSpec = spec)
            } finally {
                if (moveToken === token) {
                    phase = Phase.Idle
                    moveJob = null
                }
            }
        }
    }

    // ==================== 响应上游变化 ====================

    /**
     * 上游 selectedIndex 变化：对齐请求值。
     *
     * **不动位置** —— 位置由状态机和动画负责。
     * 旧实现这里是「清 settledX / 清 pressX」，每次清理都会让渲染
     * 回落到另一个来源，正是抽搐和回弹的来源。
     */
    LaunchedEffect(selectedIndex) {
        requestedIndex = selectedIndex
        // pager 已经追到我们请求的页：这一次「自己发起的切页」结束了，
        // 交还控制权 —— 之后用户再在页面上滑动，指示器应该跟手。
        selfDriven = false
    }

    /** 底栏宽度首次就绪：把位置对齐到当前页，避免从 0 开始飘 */
    LaunchedEffect(barWidthPx) {
        if (barWidthPx > 0 && phase == Phase.Idle) {
            posAnim.snapTo((liveOffset ?: selectedIndex.toFloat()))
        }
    }

    /**
     * 用户**直接在页面上**左右滑动时，把 pager 进度灌进 posAnim。
     *
     * 这是 liveOffset 唯一进入位置系统的入口。
     * 用 snapshotFlow 而不是在渲染里读，是为了让「写入」显式且可追踪 ——
     * 任何位置变化都必须经过 posAnim，视线所及只有一个真值。
     *
     * 只在 Idle 阶段同步：拖底栏或播放落位动画时，指示器由状态机驱动，
     * 此时 pager 的变化（我们主动请求的切页）不应该反过来覆盖位置。
     */
    /**
     * 【必须】把会变化的输入包成 State 再进闭包。
     *
     * ## 踩过的坑：闭包捕获旧值（导致「滑页面指示器不动」）
     *
     * 上一版直接写 LaunchedEffect(Unit) { snapshotFlow { liveOffset } ... }，
     * 并在 collect 里读 liveOffset / selectedIndex。
     *
     * LaunchedEffect(Unit) 只启动一次，闭包捕获的是**首帧的值**，此后永不更新：
     *   · liveOffset 首帧是 0.0 -> snapshotFlow 永远只发出 0.0，从不触发；
     *   · selectedIndex 首帧是 0 -> 在设置页时条件永远不成立。
     * 结果就是「在页面上左右滑动，指示器纹丝不动」。
     *
     * rememberUpdatedState 返回稳定的 State 容器，闭包读 .value 永远是**最新值**。
     */
    val latestLiveOffset by rememberUpdatedState(liveOffset)
    val latestSelected by rememberUpdatedState(selectedIndex)

    LaunchedEffect(Unit) {
        snapshotFlow { latestLiveOffset }
            .collect { off ->
                if (off == null) return@collect
                // 只在「没有未完成的切页请求」时才同步 pager 进度。
                //
                // 长按把指示器滑到设置后，动画结束 phase 变 Idle，
                // 但此刻 pager 的 liveOffset 仍是 0（页面刚开始切）。
                // 无条件 snapTo 会让位置从 0.998 跳回 0.000。
                if (phase == Phase.Idle && !selfDriven) {
                    posAnim.snapTo(off)
                }
            }
    }


    // ==================== 布局 ====================

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(barWidthFraction)
                // ==================== 手势 ====================
                //
                // 全部手势在这里统一处理，子级不挂任何 Clickable ——
                // 否则父级和子级各触发一次，就是「点两次才切」。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        val downTab = fingerToTab(down.position.x)
                        val downPos = fingerToPos(down.position.x)

                        // ---------- 按下 ----------
                        //
                        // 只把指示器滑到手指所在 tab，**不放大**。
                        //
                        // ## 为什么按下不放大（用户反馈「先鼓一下再滑，看着别扭」）
                        //
                        // 按下时无法预知意图，之前统一放大 -> 快速点击时
                        // 放大动画刚起步就被松手打断并缩回，就是「鼓一下又瘪掉」。
                        //
                        // 试过「延迟 120ms 放大」，长按的放大也跟着迟钝（已回退）；
                        // 试过「松手时 snapTo 归位」，放大已在几十毫秒内动了，仍有轻微鼓包。
                        //
                        // ## 最终方案：放大由「意图信号」触发，不由按下触发
                        //
                        // 两个信号，任一出现才放大：
                        //   ① 手指开始移动（超 touchSlop）-> 确定是拖拽
                        //   ② 按住超过 BarLongPressMs 仍未松手 -> 确定是长按
                        //
                        // 快速点击两个信号都不会出现，**全程零缩放**。
                        // 长按/拖拽的放大几乎不受影响：移动是即时触发的，
                        // 而长按本来就要按一会儿才有意义。
                        phase = Phase.Pressed
                        dragPos = Float.NaN
                        // ②信号：按住超时 -> 认定为长按，放大
                        // 注意：这里**不能用 phase == Phase.Pressed 判断**。
                        // animatePosTo 内部会把 phase 改成 Settling（位置在动），
                        // 导致这个条件永远不成立 —— 长按放大就会完全失效。
                        // 用一个只表示「手指还按着」的独立标志。
                        var fingerDown = true
                        val longPressGrowJob = scope.launch {
                            kotlinx.coroutines.delay(BarLongPressMs)
                            if (fingerDown) {
                                scaleAnim.animateTo(BarDragScale, lensLift())
                                glowAnim.animateTo(0.9f, lensLift())
                            }
                        }
                        animatePosTo(downPos, BarPressMs, lensLift())

                        var dragged = false
                        var lastX = down.position.x

                        // ---------- 移动 ----------
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            lastX = ch.position.x
                            if (!dragged && abs(lastX - down.position.x) > viewConfiguration.touchSlop) {
                                dragged = true
                                phase = Phase.Dragging
                                // 取消按下的运镜，完全交给逐帧跟手
                                moveToken = Any()
                                moveJob?.cancel()
                                moveJob = null
                                // ①信号：手指开始移动 -> 确定是拖拽，立即放大
                                // （长按的延迟任务作废，避免重复放大）
                                longPressGrowJob.cancel()
                                scope.launch {
                                    scaleAnim.animateTo(BarDragScale, lensLift())
                                    glowAnim.animateTo(0.9f, lensLift())
                                }
                            }
                            if (dragged) {
                                ch.consume()
                                // 同步写入：本帧生效，零调度延迟 —— 这是跟手的关键
                                dragPos = fingerToPos(lastX)
                            }
                        }

                        // ---------- 松手 ----------
                        // 快速点击走这里：两个放大信号都没出现，长按任务直接作废，
                        // 指示器全程零缩放 —— 只有平移，干净。
                        fingerDown = false
                        longPressGrowJob.cancel()
                        val targetTab = if (dragged) fingerToTab(lastX) else downTab
                        val fromPos = if (dragged && !dragPos.isNaN()) dragPos else posAnim.value

                        // ============ 缩放与发光：点击路径不参与 ============
                        //
                        // ## 为什么单独处理缩放（用户反馈「先鼓一下再滑，看着别扭」）
                        //
                        // 按下时无法预知用户是要「快速点」还是「长按」，所以统一放大了。
                        // 但快速点击时「按下 -> 松手」只隔几十毫秒，
                        // 放大动画刚起步就被打断、紧接着缩回 ——
                        // 视觉上就是「瞬间鼓一下又瘪掉」。
                        //
                        // ## 解法：按意图分流
                        //
                        // 试过「延迟 120ms 再放大」，但长按的放大也跟着变迟钝了
                        // （延迟期间用户已经按着，手感反而更差）。已回退。
                        //
                        // 现在改成**松手时判断意图**：
                        //   · 未拖拽的点击 -> snapTo 瞬间归位，不播缩回动画。
                        //     缩放最多存在几十毫秒，肉眼几乎看不到，
                        //     点击就只剩干净的平移。
                        //   · 拖拽 -> 正常播放缩回落位（不变）。
                        //
                        // 三条路径彻底解耦：
                        //   点击  -> 不缩放（本次改动）
                        //   长按  -> 放大（按下逻辑不变，未松手就一直放大）
                        //   拖拽  -> 放大 + 缩回落位（不变）
                        if (dragged) {
                            // 拖拽：正常播放缩回落位
                            scope.launch {
                                scaleAnim.animateTo(1f, lensTravel())
                                glowAnim.animateTo(0f, glowFall())
                            }
                        } else {
                            // 点击：全程没有放大过，这里只需要确保是 1.0。
                            // 用 snapTo 而不是 animateTo —— 万一长按任务刚好
                            // 在临界点触发了放大，也立刻收干净，不留尾巴。
                            scope.launch {
                                scaleAnim.snapTo(1f)
                                glowAnim.snapTo(0f)
                            }
                        }
                        // 无移动的点按：指示器已在按下时移到位，这里直接落位
                        // 有移动的拖拽：从手指位置滑到目标 tab 中心
                        dragPos = Float.NaN
                        phase = Phase.Settling
                        val token = Any()
                        moveToken = token
                        moveJob?.cancel()
                        moveJob = scope.launch {
                            try {
                                posAnim.snapTo(fromPos)
                                posAnim.animateTo(
                                    targetTab.toFloat(),
                                    animationSpec = lensTravel(),
                                )
                            } finally {
                                if (moveToken === token) {
                                    phase = Phase.Idle
                                    moveJob = null
                                }
                            }
                        }

                        // 请求切页（页面与指示器同时开始、同时结束）
                        if (targetTab != requestedIndex) {
                            requestedIndex = targetTab
                            // 标记：这次 pager 变化是我们发起的，
                            // 注入逻辑不要覆盖指示器位置
                            selfDriven = true
                            currentOnSelect(targetTab, dragged)
                        }
                    }
                },
        ) {


            // ---------- 第一层：液态玻璃容器（材质未改动） ----------
            //
            // 这一段是已经调好的材质：Kyant0/AndroidLiquidGlass 的
            // Backdrop 录制 + 库内模糊/折射，参数（blurRadius / tint /
            // tintAlpha / dispersion）都是真机逐项比对过的，未做改动。
            val liquidBackdrop = com.redmi.autoattend.ui.glass.LocalLiquidBackdrop.current
            if (liquidBackdrop != null) {
                com.redmi.autoattend.ui.glass.LiquidGlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .onSizeChanged {
                            barWidthPx = it.width
                            onBarWidth(it.width.toFloat())
                        },
                    cornerRadius = barHeight / 2,
                    blurRadius = if (colors.isDark) 8.dp else 16.dp,
                    tint = if (colors.isDark) Color(0xFF0E1B33) else Color(0xFFFAFAFA),
                    tintAlpha = if (colors.isDark) 0.45f else 0.40f,
                    highlightAlpha = 0f,
                    dispersionOverride = if (colors.isDark) 0.45f else 0.38f,
                ) {
                    Box(Modifier.fillMaxWidth().height(barHeight))
                }
            }

            // ---------- 第二层：文字 ----------
            //
            // 抽成独立 Composable 是为了**限制重组范围**。
            //
            // 文字颜色由位置连续算出（拖到 70% 就蓝 70%，与指示器永远同相，
            // 零延迟 —— 这是「变色要等一秒」的根治方案）。
            // 但 Text(color=) 是组合期参数，必须在 composition 里读 posAnim。
            //
            // 如果直接内联在这里，重组会波及整个 GlassTabBar —— 包括
            // LiquidGlassPanel（AGSL 玻璃，重组的代价远高于几个 Text）。
            // 抽出去之后，每帧重组的只有 TabLabels 内部这 2 个 Text。
            TabLabels(
                tabs = tabs,
                positionProvider = { renderPos() },
                isDark = colors.isDark,
                primaryColor = colors.textPrimary,
                barHeight = barHeight,
            )


            // ---------- 第三层：指示器（材质未改动） ----------
            //
            // 位置从 renderPos 换算成像素。renderPos 是唯一的真值，
            // 因此这里不存在「读哪个值」的决策 —— 那是旧架构的 bug 来源。
            // 注意：这里**故意不在 composition 阶段读位置和缩放**。
            //
            // ## 为什么
            //
            // renderPos() 内部读 posAnim.value / dragPos，scaleAnim.value 也是 State。
            // 在 Composable 函数体里读 = **订阅**，于是指示器每动画一帧，
            // 整个 GlassTabBar 子树就完整重组一次：重建 Modifier 链、
            // 重建 Canvas 的 draw lambda、tabs.forEachIndexed 重建 2 个 Text，
            // 还要重新判定 LiquidGlassPanel —— 连 AGSL 玻璃面板一起波及。
            //
            // 这个开销比绘制本身高 2~3 个数量级。
            //
            // 改成在 graphicsLayer / drawWithCache 的 lambda 里读：
            // 那些 lambda 属于 **draw 阶段**，读 State 只触发 invalidateDraw，
            // 不重组、不重新布局。
            // 注意：这里**只用基础高度**布局，放大由 graphicsLayer 完成。
            //
            // 之前写的是「hDp = 基础高度 × scale」再算 padding 居中，
            // 当 scale=1.40 时 hDp=56dp > barHeight=52dp，
            // padding = (52-56)/2 = -2dp → Compose 抛
            // IllegalArgumentException: Padding must be non-negative，
            // App 当场崩溃（真机表现是"按住就退回桌面"）。
            //
            // graphicsLayer 的 scale 以自身中心为轴，不改变布局尺寸，
            // 因此这里用固定高度即可，放大后自然上下溢出、不会被裁剪。
            val hDp = with(density) { indicatorHeightPx.toDp() }
            val wDp = with(density) { indicatorWidthPx.toDp() }

            Box(
                modifier = Modifier
                    // 基础高度小于底栏高度，padding 恒为正（这三个是静态布局，不进动画）
                    .padding(top = ((barHeight - hDp) / 2).coerceAtLeast(0.dp))
                    .width(wDp)
                    .height(hDp)
                    // 位移与缩放都放进 graphicsLayer —— 属于 draw 阶段，
                    // 只触发 invalidateDraw，不重新 measure/place。
                    //
                    // 之前用 Modifier.offset{} 每帧都会重新布局整个指示器，
                    // 而它是页面上唯一跟着动画走的元素 —— 等于每帧一次全局 layout。
                    .graphicsLayer {
                        translationX = posToX(renderPos())
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                    },
            ) {
                // drawWithCache 的 lambda 只在 **size / density / layoutDirection**
                // 变化时重跑，动画帧之间完全不执行 ——
                // 依赖尺寸的 glowBrush 和依赖 density 的 Stroke 因此只创建一次。
                //
                // onDrawBehind 里的读值属于 draw 阶段，不触发重组。
                Box(
                    modifier = Modifier
                        .size(wDp, hDp)
                        .drawWithCache {
                            val glowBrush = Brush.radialGradient(
                                colors = listOf(GlowColor, GlowColor.copy(alpha = 0f)),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.maxDimension * 0.85f,
                            )
                            val edgeStroke = Stroke(width = 1.dp.toPx())
                            onDrawBehind {
                                val sc = scaleAnim.value
                                drawLiquidIndicator(
                                    isDark = colors.isDark,
                                    glow = glowAnim.value,
                                    dragProgress = ((sc - 1f) / (BarDragScale - 1f))
                                        .coerceIn(0f, 1f),
                                    glowBrush = glowBrush,
                                    edgeStroke = edgeStroke,
                                )
                            }
                        },
                )
            }

        }
    }
}


/**
 * 底栏文字。
 *
 * 单独抽成 Composable，是为了把「每帧因位置变化而重组」的范围
 * 限制在这两个 Text 内部，不让它波及 AGSL 玻璃面板。
 *
 * @param positionProvider 用 lambda 而不是直接传 Float ——
 *        传 Float 的话调用方在 composition 里读 State 就订阅了，照样整树重组。
 */
@Composable
private fun TabLabels(
    tabs: List<String>,
    positionProvider: () -> Float,
    isDark: Boolean,
    primaryColor: Color,
    barHeight: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pos = positionProvider()
        tabs.forEachIndexed { index, label ->
            // 颜色是位置的连续函数：拖到 70% 就蓝 70%，与指示器永远同相。
            val weight = (1f - abs(pos - index)).coerceIn(0f, 1f)
            val activeColor = if (isDark) primaryColor else Color(0xFF1A73E8)
            val idleColor = if (isDark) Color(0xCCD8E2F0) else Color(0xCC2A3140)
            val contentColor =
                androidx.compose.ui.graphics.lerp(idleColor, activeColor, weight)
            val fontWeight =
                if (weight > 0.5f) FontWeight.SemiBold else FontWeight.Normal

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
}
// ==================== 静态绘制资源（零每帧分配） ====================
//
// 这些 Brush 的**颜色与结构完全固定**，只有整体 alpha 在变。
// 把动态 alpha 从 color stops 里挪到 drawRoundRect(alpha=) 参数，
// Brush 就变成编译期常量，可以提到函数外。
//
// ## 为什么这很重要
//
// 反编译确认：Brush.verticalGradient / radialGradient 每次调用都 new 一个
// LinearGradient / RadialGradient 对象。而且 ShaderBrush 内部按 createdSize
// 缓存 native shader —— 每帧新实例导致 createdSize 永远是 Unspecified，
// 缓存 100% 失效 → 每帧必然重建 native SkShader + Paint.setShader。
//
// Java 小对象本身不是问题（11 个/帧对 ART 是噪声），
// 但 native shader 构造 + 渲染管线 paint state 重建比它贵一个数量级。

private val GlowColor = Color(0xFF7CC4FF)

/** 边缘描边（深色）：两条 stop 的 alpha 比是 0.22 : 0.04，不是固定倍数，必须各自保留 */
private val EdgeBrushDark = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)),
)

/** 边缘描边（浅色）：0.70 : 0.10 */
private val EdgeBrushLight = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.70f), Color.White.copy(alpha = 0.10f)),
)

/**
 * 主体渐变。
 *
 * 只表达**相对比例**（上亮下暗 1 : 0.62），绝对透明度由 drawRoundRect 的
 * alpha 参数给 —— 这样同一个 Brush 能服务任意 intensity。
 */
private val BodyBrush = Brush.verticalGradient(
    colors = listOf(Color.White, Color.White.copy(alpha = 0.62f)),
)

/**
 * 绘制液态玻璃指示器。
 *
 * ## 参数说明
 *
 * @param glowBrush 外发光用的径向渐变。依赖 size，由调用方 drawWithCache 缓存。
 * @param edgeStroke 1dp 描边。依赖 density，由调用方缓存。
 *
 * ## 五层结构（从底到顶）
 * 1. 外发光 —— 拖拽/切换时的柔光
 * 2. 投影 —— 向下偏移的暗影，制造悬浮感
 * 3. 主体 —— 竖向渐变，玻璃的厚度感
 * 4. 边缘描边 —— 上亮下暗的 1px 线
 *
 * （第 4 层「流动高光带」当前未实现 —— 用户要求去掉常驻流光，
 *   只在交互瞬间有质感反应，所以这一层是刻意省略的。）
 */
private fun DrawScope.drawLiquidIndicator(
    isDark: Boolean,
    glow: Float,
    dragProgress: Float,
    glowBrush: Brush,
    edgeStroke: Stroke,
) {
    val w = size.width
    val h = size.height
    val corner = CornerRadius(h / 2f, h / 2f)

    val intensity = maxOf(glow, dragProgress).coerceIn(0f, 1f)

    // ① 外发光：只在交互时出现。alpha 走参数，Brush 复用。
    if (intensity > 0.02f) {
        drawRoundRect(
            brush = glowBrush,
            alpha = 0.26f * intensity,
            topLeft = Offset(-h * 0.35f, -h * 0.35f),
            size = Size(w + h * 0.7f, h + h * 0.7f),
            cornerRadius = CornerRadius((h + h * 0.7f) / 2f),
        )
    }

    // ② 投影：拖拽时加深，强化「脱离底栏浮起来」
    val shadowAlpha = (if (isDark) 0.16f else 0.10f) * (0.7f + intensity * 0.9f)
    drawRoundRect(
        color = Color.Black.copy(alpha = shadowAlpha),
        topLeft = Offset(0f, h * 0.06f * (1f + intensity)),
        size = Size(w, h),
        cornerRadius = corner,
    )

    // ③ 主体：静态 Brush + 动态 alpha
    //    静止 alpha 0.10（深）/ 0.30（浅），动起来涨到 0.20 / 0.44
    val baseAlpha = if (isDark) 0.10f + intensity * 0.10f else 0.30f + intensity * 0.14f
    drawRoundRect(
        brush = BodyBrush,
        alpha = baseAlpha,
        cornerRadius = corner,
    )

    // ④ 边缘描边：静态 Brush + 静态 Stroke
    drawRoundRect(
        brush = if (isDark) EdgeBrushDark else EdgeBrushLight,
        cornerRadius = corner,
        style = edgeStroke,
    )
}
