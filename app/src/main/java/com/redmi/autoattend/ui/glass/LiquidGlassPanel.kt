package com.redmi.autoattend.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.isAGSLShaderSupported
import com.styropyr0.prismal.sources.rememberPrismalCanvasSource
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 全 AGSL 液态玻璃面板 —— 对齐 Apple Liquid Glass 的光学配方。
 *
 * ## 苹果玻璃的六层构成（缺一层就不像）
 *
 * 1. **背景模糊**：把背后内容明显模糊，但**不能糊成一团色块**。
 *    糊太狠会丢掉背景结构，玻璃就显得"脏"。
 *
 * 2. **饱和度提升（Vibrancy）**：最容易被忽略、却最关键的一步。
 *    真实玻璃让透过的颜色更"纯"。PrismalGlassSurface 内部固定走 vibrancy()
 *    （saturation 1.5 的色彩矩阵），所以不需要额外传参。
 *
 * 3. **边缘折射（Lens）**：玻璃边缘像凸透镜，把紧贴边缘的背景**向外拉伸**。
 *    这是"厚玻璃"错觉的来源，也是液态玻璃最标志性的特征。
 *
 * 4. **色散**：边缘出现极淡的红/蓝色边。强度必须很低，高了就像劣质 3D。
 *
 * 5. **镜面高光（Specular）← 之前缺的就是这层**：
 *    苹果玻璃上边缘有一条明显更亮的白线，下边缘则暗一些。
 *    这是光源从上方打在玻璃边缘的物理表现。没有它，玻璃就"平"在背景上。
 *
 * 6. **tintAlpha ≈ 0**：玻璃本身几乎不着色，颜色全部来自被模糊的背景。
 *    tintAlpha 一旦超过 0.05，面板就退化成"半透明塑料板"。
 *
 * ## 性能
 *
 * 每个面板 = 1 次图层录制 + 1 次 RenderEffect 链（blur→colorMatrix→AGSL）
 * + 1 次 border 绘制（极便宜）。AGSL 那段只在 API 33+ 跑。
 * 本应用严格限制同时活跃 3 个。
 */
@Composable
fun LiquidGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDimens.radiusLarge,
    /**
     * 模糊半径。
     *
     * 默认 30dp —— 这是「磨砂」的门槛值：
     * 低于 24dp 时背后文字仍能辨认出形状，看起来像亚克力而不是磨砂玻璃。
     * 30dp 以上才能真正把内容柔化成色块。
     */
    blurRadius: Dp = 30.dp,
    tint: Color? = null,
    /** 覆盖默认着色强度；null 用主题默认值 */
    tintAlpha: Float? = null,
    /** 覆盖折射高度（边缘透镜区的厚度）；null 用默认 38dp */
    refractionHeightOverride: Dp? = null,
    /** 覆盖折射强度（背景被外推的像素量）；null 用默认 52dp */
    refractionAmountOverride: Dp? = null,
    /** 覆盖色散（边缘红蓝分离）；null 用主题默认值 */
    dispersionOverride: Float? = null,
    onClick: (() -> Unit)? = null,
    sampleOffsetX: Float = 0.35f,
    sampleOffsetY: Float = 0.25f,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val density = LocalDensity.current

    // ==================== 采样源优先级（真机诊断后修正）====================
    //
    // ## 之前错在哪
    //
    // 原顺序是 LocalBackdropLayer 优先。但 Compose 的 Dialog 是
    // **subcomposition**，它会继承父级的 CompositionLocal ——
    // 所以弹窗里 LocalBackdropLayer.current 不是 null，
    // 而是主窗口的 backdropLayer。
    //
    // 那层只录了 FlowingBackground（纯光斑），没有任何页面内容。
    // 于是弹窗玻璃采样到的是「一片蓝色渐变」，
    // 看起来就是没有质感的半透明板 —— 这正是用户反馈的现象。
    //
    // ==================== 采样源优先级（最终版）====================
    //
    // ## 血泪教训：为什么不能简单把 contentLayer 提到最前
    //
    // 试过一次把 SharedBackdrop.contentLayer 放在最优先，结果**必崩**：
    // 主界面的卡片也去采样 contentLayer，而 contentLayer 正是
    // 正在录制的主界面内容层本身 —— 形成自我引用：
    //
    //   内容层录制 → 卡片采样内容层 → 卡片渲染进内容层 → 重新录制 → ...
    //
    // 无终止递归，直接栈溢出/渲染死循环。
    //
    // ## 正确做法：按场景区分，而不是统一调优先级
    //
    //   · 主界面卡片/底栏 → 必须用 LocalBackdropLayer（只含背景），
    //     绝不能采样自己所在的那一层。
    //   · 弹窗 → 用 LocalDialogBackdrop 显式传入 contentLayer。
    //     弹窗不在内容层里，采样它不会递归。
    //
    // 两者由调用方决定，这里只按顺序取值，不改语义。
    val dialogBackdrop = LocalDialogBackdrop.current
    val recordedLayer = LocalBackdropLayer.current
        ?: SharedBackdrop.backgroundLayer

    // 兜底采样源（弹层在独立 Window 里时采样不到主窗口）
    val fallbackBackdrop: PrismalBackdrop = rememberPrismalCanvasSource {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.backgroundMid,
                    colors.backgroundAccent,
                    colors.backgroundDeep,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.blobPrimary.copy(alpha = 0.38f), Color.Transparent),
                center = Offset(size.width * sampleOffsetX, size.height * sampleOffsetY),
                radius = size.minDimension * 0.9f,
            ),
            radius = size.minDimension * 0.9f,
            center = Offset(size.width * sampleOffsetX, size.height * sampleOffsetY),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.blobSecondary.copy(alpha = 0.32f), Color.Transparent),
                center = Offset(size.width * (1f - sampleOffsetX), size.height * 0.8f),
                radius = size.minDimension * 0.8f,
            ),
            radius = size.minDimension * 0.8f,
            center = Offset(size.width * (1f - sampleOffsetX), size.height * 0.8f),
        )
    }

    val backdrop: PrismalBackdrop = dialogBackdrop ?: recordedLayer ?: fallbackBackdrop

    /**
     * 【冷启动优化】首帧不跑 AGSL，稳定后再切。
     *
     * 交接文档 §4.1 记录：冷启动 ~1936ms，主因是 AGSL 玻璃面板的
     * 首帧图层录制 + RenderEffect 链成本。用户看到的是更长的一段等待。
     *
     * 策略：第一帧用轻量磨砂（FrostedPanel，纯 Canvas 绘制，几乎零成本），
     * 等第一帧真正上屏后再切到 AGSL 全管线。用户感知到的启动变快，
     * 而稳态视觉完全不受影响。
     *
     * 为什么用 2 帧而不是固定延时：
     * 固定延时（如 100ms）在慢设备上仍会掉帧，在快设备上又白等；
     * 等"首帧已绘制"是真实条件，设备快慢都自适应。
     */
    // 【真机修正】首帧降级只能用于主界面的首次启动。
    //
    // 之前无条件对每个面板都等 2 帧，结果是**弹窗永远走降级路径**：
    // Dialog 是独立 Composition，agslReady 初始 false，
    // 而弹窗的帧回调时机与主界面不同，日志时刻它仍是 false ——
    // 于是弹窗一直渲染成"纯半透明填充"，没有任何模糊。
    //
    // 这解释了为什么把 blurRadius 从 32dp 调到 52dp 完全没变化：
    // 参数根本没被用上。
    //
    // 现在：只有当这个面板**处于主窗口**（有 tree-local 采样层，
    // 且不是弹窗）时才做首帧降级。弹窗直接跑 AGSL。
    // ============ 直接启用 AGSL（移除首帧降级）============
    //
    // ## 为什么删掉首帧降级
    //
    // 之前为了优化冷启动，让面板先渲染磨砂、等两帧后再切 AGSL。
    // 真机实测这个机制**从未成功切换到 AGSL**：
    //
    //   GlassDiag: path=FROSTED降级 | agslSupport=true degrade=false ready=false
    //
    // 所有面板（卡片、底栏）都永久停在降级路径 ——
    // 而 FrostedPanel 只有半透明填充，没有模糊也没有折射，
    // 于是卡片颜色发蓝、底栏几乎透明。
    //
    // 根因：LaunchedEffect(Unit) 里的 withFrameNanos 在面板频繁重组时
    // 会被反复取消重启，agslReady 永远来不及置 true。
    //
    // 权衡：冷启动多花约 30-50ms（实测 1921ms vs 1966ms 的差异），
    // 换来**整个界面正确的玻璃质感**。这个取舍是明确的 ——
    // 用户要的是好看的玻璃，不是快 30ms 的白板。
    val agsl = remember { isAGSLShaderSupported() } && !GlassPerfGuard.shouldDegrade



    if (!agsl) {
        // 降级路径必须和全管线路径**布局完全一致**，否则会在降级瞬间
        // 出现尺寸跳变（之前就踩过：FrostedPanel 默认带 20dp 内边距，
        // 导致底栏变高、内容区被顶）。
        // 所以这里显式传 contentPadding = 0，只提供视觉背景，不参与尺寸。
        FrostedPanel(
            modifier = modifier,
            cornerRadius = cornerRadius,
            tint = tint,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            borderAlpha = if (colors.isDark) 0.20f else 0.70f,
            content = content,
        )
        return
    }

    // ============ 苹果配方的参数选择 ============

    /**
     * tintAlpha：几乎不着色，这是"通透感"的决定性参数。
     *
     * 数值参考 Apple HIG / iOS 26 Liquid Glass：
     * - 玻璃面「20–40% 透明度」，即不透明度 60–80%；
     * - 但那是**最终合成结果**，还要叠加 blur 与折射。
     *   AGSL 本身已经做了模糊+折射，所以纯 tint 必须压得很低，
     *   否则会变成"磨砂实心板"而不是玻璃。
     *
     * 之前浅色用 0.070 偏实（在白底上像半透明白卡）。
     * 压到 0.030 后，背后光斑与卡片能明显透出来。
     */
    /**
     * 白玻璃的"提亮"量。
     *
     * 【实测驱动的修正】
     * 真机采样发现：背景 RGB(167,189,224)，卡片内 RGB(169,187,213) ——
     * 蓝色通道掉了 11，红色反而涨了 2。
     * 也就是说玻璃在**去饱和**：vibrancy 把饱和的颜色混成灰蓝，
     * 而 tint 太低（0.030）不足以把亮度提回来。
     *
     * 视觉上就是"卡片发暗、发灰"。
     *
     * 修正思路（这也是 Apple Glass 的物理直觉）：
     * 真实玻璃会**散射并提亮**背后的光。
     * 所以浅色主题的 tint 要从 0.030 提到 0.11，
     * 并且用带一点冷调的白（而不是纯白），让提亮后不发黄。
     *
     * 深色主题保持极低 —— 深色下玻璃靠"透"而不是"亮"。
     */
    /**
     * 白玻璃的提亮量。
     *
     * 迭代记录：
     * - 0.030 → 卡片发灰发暗（vibrancy 去饱和后没有足够白度补回来）；
     * - 0.110 → 提亮过头，蓝色通道飙到 249，卡片比背景还蓝，很跳；
     * - 0.058 → 当前值。提亮刚好抵消去饱和，卡片与背景同色系但不刺眼。
     *
     * 目标是"和其他卡片差不多"：玻璃之间应该有细微差异（各自采样位置不同），
     * 但不该有谁明显更蓝、更亮。
     */
    // ============ 全局磨砂质感（统一到弹窗的标准）============
    //
    // 用户要求「所有的卡片也走同样的方式，整个界面全部走这样的方式」。
    //
    // ## 为什么不能直接套用系统模糊
    //
    // `Window.setBackgroundBlurRadius()` 是 **Window 级**能力，
    // 只能模糊「这个 Window 之后」的内容（壁纸、其他应用）。
    // 而卡片要模糊的是**同一 Window 内部**它背后的内容 ——
    // 系统模糊做不到，必须用 AGSL。
    //
    // ## 统一的目标质感
    //
    // 弹窗现在的观感是：背后内容完全柔化成色块，只留色彩倾向，
    // 文字绝不可辨认；玻璃面自身有一层薄材质。
    //
    // 卡片要达到同样观感，需要：
    //   1. 模糊显著加强（16dp → 32dp 量级）—— 这是关键；
    //   2. tint 用中性灰白而非纯白，避免提亮过度发白；
    //   3. 保留折射与色散 —— 那是「玻璃厚度」的来源，不能丢。
    //
    // 之前卡片偏"清透"的原因就是模糊只有 ~14dp（16 × 0.85），
    // 背后内容还能辨认出形状，不像磨砂而像亚克力。
    // ============ tintAlpha：对齐设置页卡片的实测值（第四轮）============
    //
    // ## 用户的原话
    //
    // 「你去参考一下设置界面的卡片，那边的颜色才是对的感觉」
    //
    // ## 为什么设置页的卡片好看
    //
    // 取色实测（set.png）：
    //   启用打卡卡   RGB(160,198,247)
    //   主题卡       RGB(127,176,246)
    //   打卡时间卡   RGB(130,186,229)
    //   锁屏密码卡   RGB(206,220,249)
    //   卡片外背景   RGB(172,205,250)
    //
    // 关键特征：**卡片与背景的色差很小**，整体是浅淡的蓝。
    // 而首页 AGSL 卡片之前是 RGB(154,186,249) —— 比背景暗得多、浓得多。
    //
    // ## 根本差异在于 tint 的强度
    //
    // 设置页用 FrostedPanel，它的浅色主题 glassTint = 0x8CFFFFFF：
    //   **白色，alpha 0.55** —— 大量白色把背景洗淡了，所以显得柔和。
    //
    // 而 AGSL 面板之前 tintAlpha 只有 0.036，几乎不洗，
    // 采样到的背景经 vibrancy 增强后就是浓蓝。
    //
    // ## 结论：把 tintAlpha 提到与 FrostedPanel 同一量级
    //
    // 用 0.42 —— 略低于 FrostedPanel 的 0.55，因为 AGSL 自己还有模糊
    // 和折射在做提亮，叠加起来已经是同等效果。
    // tint 用纯白：既然目标是「被白色洗淡」，就不要额外偏色。
    // ============ 最终取值：AGSL 面板的可用区间 ============
    //
    // ## 完整实测序列（浅色主题，白 tint）
    //
    //   tintAlpha  结果 RGB           色相   判定
    //   --------   -------------    ----   ----
    //   0.036      154,186,249      220    太浓（比背景暗 28）
    //   0.20       176,193,250      ~230   偏紫（R≈B>G）
    //   0.23       197,197,197      -      发灰
    //   0.42       211,211,211      -      全灰
    //
    // **AGSL 面板对白色 tint 的响应与 FrostedPanel 完全不同**：
    //   · FrostedPanel 是 Canvas 半透明叠加，白色不会稀释底下的蓝
    //   · PrismalGlassSurface 是采样图层再合成，白色会平均掉采样值
    //
    // 所以不能用同一个 alpha 去匹配 —— 这条路已确认走不通。
    //
    // ## 选择：保持在 AGSL 能稳定的范围
    //
    // 用户选择方案 B：保留 AGSL 的模糊+折射+色散，
    // 接受它与设置页卡片在颜色上不完全一致。
    //
    // 取 0.09 —— 在「太浓」与「偏紫」之间，
    // 既能提亮到接近背景，又不至于把蓝色洗掉。
    val surfaceTintAlpha = tintAlpha ?: if (colors.isDark) 0.032f else 0.09f

    // 模糊：统一到磨砂量级。
    // 深色主题额外 ×1.15 —— 深色下模糊不足会让背景噪点透出来，很脏。
    val effectiveBlur = if (colors.isDark) blurRadius * 1.05f else blurRadius

    /**
     * 折射：边缘透镜区，苹果"厚玻璃"感的来源。
     *
     * 默认 38dp / 52dp 是给**大卡片**调的 —— 卡片面积大，
     * 38dp 的透镜带只占边缘一小圈，看起来自然。
     *
     * 但底栏是 52dp 高的**细长胶囊**：38dp 的透镜带几乎吞掉整条，
     * 结果要么全糊、要么看不出边缘，折射感完全丢失。
     * 所以底栏需要单独的、更小的值（由调用方 override）。
     */
    // 折射在磨砂质感里更重要：模糊抹掉了所有细节，
    // 边缘的透镜形变就成了「这是一块玻璃」的唯一证据。
    val refractionHeight = with(density) {
        (refractionHeightOverride ?: 36.dp).toPx()
    }
    val refractionAmount = with(density) {
        (refractionAmountOverride ?: 52.dp).toPx()
    }

    // 色散：真实玻璃边缘红蓝分离很微弱。
    // 但"微弱"不等于"没有" —— 底栏要能看出玻璃感，就必须给足色散，
    // 否则只是一块半透明磨砂贴片。
    // 色散：磨砂面的色散比清透玻璃更明显（散射造成色边）。
    val dispersion = dispersionOverride ?: if (colors.isDark) 0.16f else 0.09f

    /**
     * 镜面高光描边。
     *
     * 这是苹果玻璃最关键、也最常被漏掉的一层：
     * 上边缘一条明亮的白线（光源在上方），左右两侧渐弱，下边缘几乎无光。
     * 用 1px 的线性渐变 border 实现，成本几乎为零。
     *
     * 深色主题需要更亮的描边（对比环境暗），浅色主题用更柔的白。
     */
    // 【真机修正】描边强度必须跟着 tint 走。
    //
    // 之前浅色主题固定 0.95/0.55/0.30 的纯白渐变，
    // 用在低 tint（0.030）的透明玻璃上会**压过背景内容** ——
    // 视觉上整块面板发白，像蒙了一层雾，而不是"透"。
    //
    // 苹果的高光只发生在**边缘一圈**（约 1px），不是整面渐变。
    // 所以：降不透明度 + 收窄梯度范围，让高光成为"一条线"而不是"一层白"。
    val specularBrush = Brush.verticalGradient(
        colors = if (colors.isDark) {
            listOf(
                Color.White.copy(alpha = 0.30f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.03f),
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.08f),
            )
        },
    )

    // 注意布局方式：
    //
    // 不能用 Box(modifier) + 内部 matchParentSize() —— 那会让 Box 的尺寸
    // 依赖自身子节点，而子节点又要 match parent，形成循环度量，
    // 结果是面板塌成 0 高（或只剩 padding 高度）。
    //
    // 正确做法：把 modifier 交给玻璃面板自己（它由 backdrop 驱动内容尺寸），
    // 高光描边用 matchParentSize 叠在它上面——此时父级尺寸已经由玻璃面板确定。
    Box {
        PrismalGlassSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = { shape },
            onClick = onClick,
            blurRadius = effectiveBlur,
            // 浅色主题用带冷调的白：纯白会把蓝紫背景洗成灰。
            // 用中性偏暖的白（不是冷白）：冷白会把背景的蓝进一步推高，
            // 导致卡片比背景更蓝。中性白只提亮不偏色。
            // 【配色一致性】AGSL 面板与磨砂面板的差异修正。
            //
            // 实测对比（浅色主题）：
            //   设置页（FrostedPanel）  RGB(168,203,248)  蓝调 ✓
            //   首页  （AGSL 面板）     RGB(179,179,249)  偏紫 ✗
            //
            // 原因：PrismalGlassSurface 内部固定跑 vibrancy（saturation 1.5），
            // 高饱和把蓝紫色背景的 R/G 通道拉平 —— R=G 就是紫色的特征。
            // FrostedPanel 完全不做饱和度增强，所以颜色是准的。
            //
            // ============ tint 的第三轮修正（色相已对，是过饱和）============
            //
            // ## 实测数据（1280x2772，浅色主题）
            //
            //   位置        RGB              色相   亮度
            //   左上背景    182,210,248      215°   高
            //   右上背景    214,227,249      218°   高
            //   左中背景    216,226,240      215°   高
            //   底部背景    191,206,237      220°   高
            //   --------
            //   时间卡      154,186,249      220°   低 ★
            //   打卡卡      152,185,249      220°   低 ★
            //
            // ## 结论：色相已经正确（220° 对上了）
            //
            // 前几轮一直在追色相，现在色相没问题了。
            // 真正的问题是**饱和度过高 + 亮度偏低**：
            //   · 卡片 R 通道只有 152，而背景是 182~216，低了 30~60
            //   · R 被压低 → 蓝的相对浓度上升 → 视觉上"太蓝"
            //
            // ## 为什么 R 被压低
            //
            // PrismalGlassSurface 内部固定跑 vibrancy（饱和度 ×1.5）。
            // 它放大的是"通道差值"：采样的背景本身已经偏蓝，
            // 再放大 1.5 倍，R 与 B 的差距从 ~66 变成 ~95。
            //
            // ## 修正：把 tint 调**更亮、更中性**，抵消 vibrancy 的过度饱和
            //
            // 用接近白但 B 略高的暖冷平衡色：R=248, G=250, B=255
            // 它的 R 通道比之前高 14、G 低 2 —— 提高 R 就是提高整体亮度，
            // 让 vibrancy 放大后的结果仍然落在"淡雅的蓝"而不是"浓蓝"。
            // ============ tint 必须带蓝（关键修正）============
            //
            // 之前用 colors.glassTint（0x8CFFFFFF，纯白），
            // 实测无论 alpha 取 0.09 / 0.20 / 0.23 / 0.42，
            // 结果都是灰色 RGB(185~211, 恒等, 恒等)：
            //
            //   alpha=0.09 → RGB(187,187,187)
            //   alpha=0.20 → RGB(176,193,250)
            //   alpha=0.23 → RGB(197,197,197)
            //   alpha=0.42 → RGB(211,211,211)
            //
            // **白色 tint 的物理作用就是把颜色朝灰的方向平均**，
            // 无论 alpha 多少 —— 低 alpha 时弱一些但仍会把 R/G 拉平。
            //
            // 要保留蓝色，tint 本身必须是蓝的。
            // 用 0x6E80C4FF：R=80 G=196 B=255，明显偏蓝，alpha 0.43。
            // 它在合成时给蓝通道加分，抵消 vibrancy 的去饱和。
            tint = tint ?: if (colors.isDark) {
                colors.glassTint.copy(alpha = 1f)
            } else {
                // 淡蓝：B 通道满值，R 压低，专门用来维持蓝调
                Color(0xFFA0CFFF)
            },
            tintAlpha = surfaceTintAlpha,
            refractionHeightPx = refractionHeight,
            refractionAmountPx = refractionAmount,
            chromaticAberration = dispersion,
            // 注意：PrismalGlassSurface 内部固定走 vibrancy() 色彩矩阵（saturation 1.5），
            // 传进去的 saturation/brightness 是不生效的（见 PrismalGlassEffects.kt
            // 的 useVibrancy 分支），所以这里不传，避免误以为在调整它们。
        ) {
            // 内容直接铺在玻璃上
            content()
        }

        // 镜面高光：叠一层 1px 描边在玻璃之上。
        //
        // 【必须加 pointerInput 透传】这一层 matchParentSize 铺满整个面板、
        // 且位于玻璃内容之上，会参与命中测试。如果不主动透传事件，
        // 它会把整个面板区域的点击"吃掉"——
        // 表现就是面板里的按钮看得见但点不动（真机上踩了很久）。
        //
        // clearAndPassThrough 让这一层不拦截任何触摸，
        // 事件继续向下传给真正的玻璃面板与内容。
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(width = 1.dp, brush = specularBrush, shape = shape)
        )
    }
}

/**
 * 当前 GPU 管线等级，用于设置页的"如实告知"——不夸大效果。
 */
fun currentGlassPipelineLabel(): String =
    if (isAGSLShaderSupported()) {
        "AGSL 全管线（Android 13+）"
    } else {
        "RenderEffect 模糊（降级渲染）"
    }