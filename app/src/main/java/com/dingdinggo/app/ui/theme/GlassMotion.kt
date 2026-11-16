package com.dingdinggo.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * 全应用统一的非线性缓动规格（iOS 18 / iOS 26 减速曲线）。
 *
 * 所有状态切换统一走快起步、极缓收尾的 [IosEaseOut] 或双向平滑 [IosEaseInOut]，
 * 保证交互节奏沉稳统一、无生硬跳变。
 */
val IosEaseOut: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** 双向平滑：用于主题过渡、文字渐变、胶囊回落等两端都要柔和的运动 */
val IosEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

object GlassMotion {
    const val TAB_INDICATOR = 420
    const val DROPLET_STRETCH = 260
    const val DROPLET_RETRACT = 460
    const val GLOW_PULSE = 520
    const val PRESS_DOWN = 180
    const val PRESS_UP = 340
    const val TAB_LABEL = 380
    const val PAGE_SWITCH = 460
    const val SHEET_ENTER = 300
    const val DISCLOSURE = 280
    const val HUD_EXPAND = 520
    const val HUD_COLLAPSE = 420
    const val SEGMENT_SLIDE = 320
    const val WHEEL_SNAP = 260
    const val THEME_TRANSITION = 450

    fun easeOut(durationMs: Int) = tween<Float>(durationMs, easing = IosEaseOut)
    fun easeInOut(durationMs: Int) = tween<Float>(durationMs, easing = IosEaseInOut)
    fun easeOutColor(durationMs: Int) = tween<Color>(durationMs, easing = IosEaseOut)
    fun easeInOutColor(durationMs: Int) = tween<Color>(durationMs, easing = IosEaseInOut)
    fun easeOutIntOffset(durationMs: Int) = tween<IntOffset>(durationMs, easing = IosEaseOut)
    fun easeOutDp(durationMs: Int) = tween<Dp>(durationMs, easing = IosEaseOut)
    fun settleSpec() = tween<Float>(PRESS_UP, easing = IosEaseOut)
}
