package com.redmi.autoattend.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween

/**
 * 全应用统一的动效规格（iOS 18 标准）。
 *
 * ## 为什么集中在一处
 *
 * AGENTS.md 明确要求：**全应用禁用 spring()，一律使用「非线性慢速」动画**。
 * 理由是 spring 的物理回弹适合"拖拽跟手"这类无明确终点的交互，
 * 但在**明确的目标切换**（点按钮、开弹层）里会显得毛躁、廉价。
 * iOS 18 在这些场景用的是一条快速起步、极缓收尾的减速曲线，落点稳、无多余弹跳。
 *
 * 在这之前，曲线和时长散落在 GlassTabBar / TimeEditorSheet / MainActivity /
 * SettingsComponents / PressEffect / LiquidGlassButton 六个文件里，
 * 其中两处还残留着 spring()。同一个"按下回弹"在两个组件里表现不同，
 * 违反了 HIG 的 Consistency 原则。
 *
 * 现在所有动效参数只从这里取，改一处即全局生效。
 */

/** 主曲线：接近 CSS cubic-bezier(0.32, 0.72, 0, 1)，Apple 在 sheet / tab 切换里反复使用 */
val IosEaseOut: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** 双向平滑：用于「回到原位」这类两头都要柔和的运动 */
val IosEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * 动效时长表（毫秒）。
 *
 * 数值来自 AGENTS.md 的强制规格，不要随手改成"看着差不多"的值 ——
 * 慢速是刻意的：用户原话是「把整个动画速率放慢，现在太快了，并不优雅」。
 */
object GlassMotion {

    /** 底栏指示器位移（点击切换） */
    const val TAB_INDICATOR = 420

    /** 液滴拉伸（抻开） */
    const val DROPLET_STRETCH = 260

    /** 液滴拉伸（收回）—— 收得比抻开慢，显"黏稠" */
    const val DROPLET_RETRACT = 460

    /** 发光脉冲淡出 */
    const val GLOW_PULSE = 520

    /** 按下放大 / 缩小 */
    const val PRESS_DOWN = 180

    /** 松手回落 */
    const val PRESS_UP = 340

    /** tab 文字变色 */
    const val TAB_LABEL = 380

    /** 页面切换 */
    const val PAGE_SWITCH = 460

    /** 弹层入场 */
    const val SHEET_ENTER = 280

    /** 悬浮提示展开（不要更快） */
    const val HUD_EXPAND = 520

    /** 悬浮提示收回 */
    const val HUD_COLLAPSE = 420

    // ---- 便捷构造器：避免每个调用点重复写 easing ----

    /** 主曲线的 tween */
    fun easeOut(durationMs: Int) = tween<Float>(durationMs, easing = IosEaseOut)

    /** 双向平滑的 tween */
    fun easeInOut(durationMs: Int) = tween<Float>(durationMs, easing = IosEaseInOut)

    /**
     * 松手回落用的 spec。
     *
     * 这里**不用 spring**：回弹的过冲在明确的"按下→抬起"闭环里属于多余动作。
     * 用一条 340ms 的主曲线 tween，落点稳、无二次抖动。
     */
    fun settleSpec() = tween<Float>(PRESS_UP, easing = IosEaseOut)
}
