package com.redmi.autoattend.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.runtime.snapshotFlow
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

    /** 按下放大 */
    val PRESS_MS = 180

    /** 松手回落 */
    val RELEASE_MS = 340

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

    val xAnim = remember { Animatable(0f) }
    val wAnim = remember { Animatable(indicatorWidthPx) }
    val hAnim = remember { Animatable(indicatorHeightPx) }
    val scaleAnim = remember { Animatable(1f) }
    val glowAnim = remember { Animatable(0f) }

    var isDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }

    /**
     * 本次 selectedIndex 变化是否来自**滑动/拖拽切页**。
     *
     * ## 为什么用普通 var 而不是 mutableStateOf
     *
     * 它只在 [LaunchedEffect] 内部被读一次，**不需要触发重组**。
     * 用 Snapshot 状态会引入时序竞争：
     * 写 true 后立即触发重组，但此时 selectedIndex 还没变，
     * LaunchedEffect 的 key 不变就不会重启 ——
     * 等 selectedIndex 真变了，标记可能已经被消费或覆盖。
     *
     * 普通 var 写入是同步的，LaunchedEffect 重启时读到的必然是最新值。
     */
    var pendingSwipeSettle = false

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

        settleTo(
            index = selectedIndex,
            fromX = xAnim.value,
            stretch = !drivenByPager && !byDrag,
        )
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
                    .onSizeChanged { barWidthPx = it.width },
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
                                        tween(
                                            durationMillis = PRESS_MS,
                                            easing = IosEaseOut,
                                        ),
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
                                        tween(
                                            durationMillis = RELEASE_MS,
                                            easing = IosEaseOut,
                                        ),
                                    )
                                }

                                if (nearest != currentSelected) {
                                    // 【关键】先把 xAnim 对齐到松手位置，
                                    // 这样 LaunchedEffect 重算 distance 时是 0，
                                    // 不会因为读到旧位置而多拉一次。
                                    scope.launch { xAnim.snapTo(dragX) }
                                    pendingSwipeSettle = true
                                    currentOnSelect(nearest, true)
                                } else {
                                    // 拖回原位：只做一次轻微的拉伸回弹。
                                    scope.launch { xAnim.snapTo(dragX) }
                                    settleTo(nearest, dragX)
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch {
                                    scaleAnim.animateTo(
                                        1f,
                                        tween(RELEASE_MS, easing = IosEaseOut),
                                    )
                                }
                                scope.launch { xAnim.snapTo(dragX) }
                                // 取消拖拽是回到原位，不需要拉伸。
                                settleTo(currentSelected, dragX, stretch = false)
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == selectedIndex

                    // 未选中项的颜色要**自己定**，不能直接用 textTertiary。
                    //
                    // 原因：textTertiary 的 alpha 只有 43%（深色 0x6E）/ 50%（浅色 0x80），
                    // 它原本是画在不透明底色上的；现在底栏是半透明玻璃，
                    // 再叠加一层半透明文字就几乎看不见 —— 实测「设置」直接消失。
                    //
                    // 所以这里给一个足够实的颜色：仍然弱于选中项（靠字重和色相区分），
                    // 但保证在玻璃上清晰可读。
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) {
                            if (colors.isDark) colors.textPrimary else Color(0xFF1A73E8)
                        } else {
                            if (colors.isDark) Color(0xCCD8E2F0) else Color(0xCC2A3140)
                        },
                        animationSpec = tween(COLOR_MS, easing = IosEaseInOut),
                        label = "tab-color",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight)
                            .ClickableNoRipple {
                                if (index != selectedIndex) onSelect(index, false)
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
