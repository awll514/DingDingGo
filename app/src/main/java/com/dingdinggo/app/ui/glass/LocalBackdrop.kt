package com.dingdinggo.app.ui.glass

import androidx.compose.runtime.compositionLocalOf
import com.kyant.backdrop.Backdrop

/**
 * 液态玻璃采样源（基于 Kyant0/backdrop-android）。
 *
 * - 页面内部的主玻璃面板采样纯极光背景层，防止「卡片自采样」循环；
 * - 悬浮在内容之上的控件（底栏、弹窗等）采样 CombinedBackdrop（背景 + 内容），
 *   从而在滑动或打开弹层时透出真实移动的卡片与文字。
 */
val LocalLiquidBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 跨 Window 共享的复合采样源（供 Dialog / 弹窗 Sheet 使用）。
 *
 * Compose Dialog 运行在独立 Window 中，无法直接继承主窗口树里的动态状态，
 * 由 MainActivity 在组合中更新最新的 CombinedBackdrop 引用。
 */
object SharedBackdrop {
    @Volatile
    var sceneBackdrop: Backdrop? = null
}
