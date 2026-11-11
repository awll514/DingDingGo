package com.redmi.autoattend.ui.glass

import androidx.compose.runtime.compositionLocalOf
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
