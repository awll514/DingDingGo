package com.redmi.autoattend.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题切换动画时长：苹果的深/浅色转场在 0.4~0.5s 区间 */
private const val THEME_TRANSITION_MS = 450

/** 数值型主题参数（透明度等）的插值 spec */
private val specFloat = tween<Float>(THEME_TRANSITION_MS, easing = IosEaseInOut)

/**
 * 应用级主题状态。
 *
 * 只支持三种模式（用户可在设置页切换）：
 * - LIGHT：强制浅色
 * - DARK：强制深色
 * - SYSTEM：跟随系统（Android 10+ 的系统深色开关）
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

/**
 * 当前生效的配色。
 *
 * 用 staticCompositionLocalOf 而不是 compositionLocalOf：
 * 主题切换频率极低，静态版本在读取时没有订阅开销，更适合高频重组的玻璃面板。
 */
val LocalGlassColors = staticCompositionLocalOf<GlassColors> { DarkGlassColors }

/** 便捷访问：在任何 Composable 里直接拿当前配色 */
object GlassTheme {
    val colors: GlassColors
        @Composable
        get() = LocalGlassColors.current
}

/**
 * 主题包装器。
 *
 * 注意：这里不用 MaterialTheme 的 colorScheme 体系——本项目的视觉完全由自定义
 * 玻璃 Modifier 主导（AGENTS.md 明确要求"不直接套用 Material 默认卡片"），
 * 只需要把 [GlassColors] 下发下去即可。
 */
@Composable
fun GlassThemeProvider(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = rememberSystemDark()

    // 【关键】用 animateColorAsState 而不是直接切换 —— 用户要求
    // 「要有丝滑符合苹果视觉的动画」。
    //
    // 主题切换不能是"啪一下变白/变黑"：那在 iOS 里是刻意的转场动画，
    // 直接闪切会显得廉价。苹果的做法是让整屏颜色在 ~0.45s 内插值过渡。
    //
    // 这里对每一个背景色做插值，于是：
    //   · 深色 → 浅色：背景、光斑、文字色一起渐变
    //   · 玻璃面板重新采样渐变中的背景，观感上"整块界面在呼吸"
    //
    // 用 tween + IosEaseInOut（双向平滑）：主题切换两头都要柔和，
    // 不适合用"快速起步"的 IosEaseOut。
    val spec = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = THEME_TRANSITION_MS,
        easing = IosEaseInOut,
    )

    val target = when (mode) {
        ThemeMode.LIGHT -> LightGlassColors
        ThemeMode.DARK -> DarkGlassColors
        ThemeMode.SYSTEM -> if (systemDark) DarkGlassColors else LightGlassColors
    }

    val deep by animateColorAsState(target.backgroundDeep, spec, label = "bgDeep")
    val mid by animateColorAsState(target.backgroundMid, spec, label = "bgMid")
    val accentBg by animateColorAsState(target.backgroundAccent, spec, label = "bgAccent")
    val blob1 by animateColorAsState(target.blobPrimary, spec, label = "blob1")
    val blob2 by animateColorAsState(target.blobSecondary, spec, label = "blob2")
    val blob3 by animateColorAsState(target.blobTertiary, spec, label = "blob3")
    val blob4 by animateColorAsState(target.blobQuartiary, spec, label = "blob4")
    val txtPrimary by animateColorAsState(target.textPrimary, spec, label = "txtP")
    val txtSecondary by animateColorAsState(target.textSecondary, spec, label = "txtS")
    val txtTertiary by animateColorAsState(target.textTertiary, spec, label = "txtT")
    val veilColor by animateColorAsState(target.backdropVeilColor, spec, label = "veil")
    val borderCol by animateColorAsState(target.borderColor, spec, label = "border")
    val glassTintC by animateColorAsState(target.glassTint, spec, label = "glassTint")
    val glassTintStrongC by animateColorAsState(target.glassTintStrong, spec, label = "glassTintStrong")

    // 数值型参数也插值，否则描边粗细/遮罩浓度会突变
    val borderAlphaAnim by animateFloatAsState(target.borderAlpha, specFloat, label = "borderA")
    val veilAnim by animateFloatAsState(target.backdropVeil, specFloat, label = "veilA")

    // isDark 决定很多分支（tint、描边、模糊倍数），不能插值 ——
    // 在动画中点之后就应当切到目标值，否则会出现"半深半浅"的错乱组合。
    val colors = target.copy(
        backgroundDeep = deep,
        backgroundMid = mid,
        backgroundAccent = accentBg,
        blobPrimary = blob1,
        blobSecondary = blob2,
        blobTertiary = blob3,
        blobQuartiary = blob4,
        textPrimary = txtPrimary,
        textSecondary = txtSecondary,
        textTertiary = txtTertiary,
        backdropVeilColor = veilColor,
        borderColor = borderCol,
        glassTint = glassTintC,
        glassTintStrong = glassTintStrongC,
        borderAlpha = borderAlphaAnim,
        backdropVeil = veilAnim,
    )

    CompositionLocalProvider(LocalGlassColors provides colors) {
        content()
    }
}

/**
 * 系统深色状态 —— 必须**可观察**，否则切换深浅色界面不刷新。
 *
 * ## 之前为什么"要切一下后台才生效"
 *
 * 旧实现直接读 `context.resources.configuration.uiMode`。
 * 那是**一次性快照**，不产生任何订阅 —— Composition 读完之后
 * 系统再切深浅色，Compose 完全不知道要重组。
 *
 * 而 AndroidManifest 里 MainActivity 声明了
 * `configChanges="orientation|screenSize|uiMode"`，
 * 意味着系统切换深浅色时 **Activity 不会重建** ——
 * 于是连"重建时重新读一次"这条路也断了。
 *
 * 用户切后台再回来时，Activity 会走 onResume + 重新测量，
 * 才碰巧读到新配置，表现为"切一下后台才能看见"。
 *
 * ## 正确做法
 *
 * 用 Application 级的 `onConfigurationChanged` 回调，
 * 配合 `mutableStateOf` 作为可观察源。这样系统一改深浅色，
 * state 立刻变化 → 重组 → 主题跟随，不需要任何"切后台"动作。
 */
@Composable
private fun rememberSystemDark(): Boolean {
    val context = LocalContext.current
    val isDark = remember {
        mutableStateOf(context.isSystemInDarkMode())
    }

    DisposableEffect(context) {
        val app = context.applicationContext as? android.app.Application
        val listener = object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                val dark = (newConfig.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (isDark.value != dark) isDark.value = dark
            }

            override fun onLowMemory() = Unit
        }
        app?.registerComponentCallbacks(listener)

        // 注册时对齐一次当前值：避免注册前系统已经切换过、
        // 而初始 remember 里读到的是旧配置。
        isDark.value = context.isSystemInDarkMode()

        onDispose { app?.unregisterComponentCallbacks(listener) }
    }

    return isDark.value
}

private fun android.content.Context.isSystemInDarkMode(): Boolean {
    val uiMode = resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * 排版尺度。
 *
 * 统一收敛字号，避免各页面随手写 sp 导致层级混乱。
 * 玻璃面板上的文字需要比普通界面再"轻"一点——因为模糊背景本身有视觉噪声，
 * 文字太重会显得脏。
 */
object GlassType {
    /** 页面主标题 */
    val pageTitle = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    )

    /** 分组标题 */
    val sectionTitle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
    )

    /** 卡片标题 */
    val cardTitle = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 正文 */
    val body = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    )

    /** 辅助说明 */
    val caption = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 15.sp,
    )

    /** 倒计时数字 */
    val countdown = TextStyle(
        fontSize = 62.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1).sp,
    )

    /** 数值（时间等） */
    val numeric = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Light,
    )
}

/**
 * 统一的圆角与间距尺度 —— 按 Apple HIG 量化规格。
 *
 * | 项目 | Apple 规格 | 本项目取值 |
 * |------|-----------|-----------|
 * | 大卡片圆角 | 16–24pt | 28dp |
 * | 中卡片圆角 | 16–24pt | 22dp |
 * | 小元素圆角 | 12–16pt | 16dp |
 * | 胶囊 | 全圆 | 999dp |
 * | 页面边距 | 16–20pt | 20dp |
 * | 卡片间距 | 12–16pt | 14dp |
 * | 卡片内边距 | 16–24pt | 20dp |
 *
 * 注意 radiusLarge 从 34dp 降到 28dp：
 * 同心圆角原则要求圆角随元素变小而递减，
 * 34dp 在大卡片上显得过于"圆润卡通"，偏离 iOS 的克制感。
 */
object GlassDimens {
    val radiusLarge = 28.dp
    val radiusMedium = 22.dp
    val radiusSmall = 16.dp
    val radiusPill = 999.dp

    /** 页面水平边距 */
    val pagePadding = 20.dp

    /** 卡片间距（Apple 12–16dp） */
    val cardGap = 14.dp

    /** 卡片内部 padding */
    val cardPadding = 20.dp

    /**
     * 内容区底部为 tab bar 预留的高度。
     *
     * 由三部分构成：
     *   tab bar 自身高度 58dp
     * + 上下 padding 各 10dp
     * + 额外呼吸空间 26dp
     * = 104dp
     *
     * 【踩过的坑】之前是 104dp，但底栏用了垂直 10dp padding 之后
     * 实际占用变成了 58 + 20 = 78dp，再加上外层与手势条的距离，
     * 内容底部的「去设置」按钮仍然被压住了一半。
     * 现在提到 124dp，多留 20dp 余量。
     */
    val bottomBarReserved = 124.dp
}