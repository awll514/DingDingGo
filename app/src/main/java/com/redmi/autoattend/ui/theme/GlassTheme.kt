package com.redmi.autoattend.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkGlassColors else LightGlassColors

    CompositionLocalProvider(LocalGlassColors provides colors) {
        content()
    }
}

/** 读取系统是否深色 */
@Composable
private fun isSystemInDarkTheme(): Boolean {
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode and
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