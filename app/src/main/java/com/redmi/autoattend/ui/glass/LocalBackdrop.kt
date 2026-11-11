package com.redmi.autoattend.ui.glass

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.sources.PrismalGlassLayer

/**
 * 全屏流动背景的「录制层」。
 *
 * 这是让玻璃真正"透"的关键：FlowingBackground 用
 * Modifier.prismalGlassLayer(bgLayer) 把这层内容录进一个 GraphicsLayer，
 * 玻璃面板再以它为 backdrop 采样——看到的就是**真实**在流动的背景被模糊折射，
 * 而不是一张静态色卡。
 *
 * 用 CompositionLocal 下发，避免每一层都手动透传参数。
 */
val LocalBackdropLayer = compositionLocalOf<PrismalGlassLayer?> { null }

/**
 * 【弹窗专用】跨 Window 传递的采样源。
 *
 * ## 为什么必须单独开一个
 *
 * Compose 的 `Dialog` 运行在**独立 Window** 里，
 * CompositionLocal 只在声明它的那棵 Compose 树内可见 ——
 * 弹窗里读 [LocalBackdropLayer] 永远是 null。
 *
 * 结果就是弹窗里的 LiquidGlassPanel 拿不到录制层，
 * 只能退化到静态渐变兜底图，看起来像"半透明塑料板"，
 * 而不是能折射背景的真玻璃。用户反馈的
 * 「弹窗太丑了，没有液态玻璃的质感动效」正是这个原因。
 *
 * ## 这里怎么解决
 *
 * 用一个**应用级的可观察容器**持有最新录制层，
 * MainActivity 每帧把录制层写进去，弹窗直接读它。
 * 跨 Window 共享一个 GraphicsLayer 引用是合法的 ——
 * 它只是 GPU 上的一个图层句柄，不属于任何 Window。
 */
object SharedBackdrop {
    /**
     * 录制层 A：只有流动背景。给卡片用。
     *
     * 卡片要"透过玻璃看到背景光斑在流动"，不该把别的卡片糊进来。
     */
    @Volatile
    var backgroundLayer: PrismalGlassLayer? = null

    /**
     * 录制层 B：背景 + 全部内容。给底栏、弹窗用。
     *
     * 弹窗需要它：弹窗出现时内容是静止的，采样"内容层"
     * 能得到真实的卡片与文字，折射后在玻璃后面若隐若现 ——
     * 这正是 iOS sheet 的观感。
     */
    @Volatile
    var contentLayer: PrismalGlassLayer? = null
}

/**
 * 弹窗内的采样源。
 *
 * 优先用内容层（能透出卡片文字），退化到背景层，
 * 最后才是静态渐变兜底。
 */
val LocalDialogBackdrop = staticCompositionLocalOf<PrismalBackdrop?> { null }
