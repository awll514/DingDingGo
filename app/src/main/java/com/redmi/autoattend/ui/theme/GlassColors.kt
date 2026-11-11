package com.redmi.autoattend.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 液态玻璃的配色方案。
 *
 * 色调基调：**偏蓝（nocturne blue）**。
 * 背景、光斑、强调色统一走蓝—靛—紫蓝的冷色带，
 * 只有语义色（成功/警告/失败）保留各自的色相，避免状态判断被基调染色。
 *
 * 深浅两套不是简单反色：
 * - 深色：玻璃是"提亮"的——在深背景上叠极淡白，靠模糊后的亮斑透出层次；
 * - 浅色：玻璃是"提亮 + 白描边"的——浅背景上必须用很实的白和更强的光斑，
 *   否则玻璃会完全消失（白玻璃叠白背景看不见）。
 */
data class GlassColors(
    // ---------- 背景 ----------
    val backgroundDeep: Color,
    val backgroundMid: Color,
    val backgroundAccent: Color,
    val blobPrimary: Color,
    val blobSecondary: Color,
    val blobTertiary: Color,
    val blobQuartiary: Color,
    /** 背景上下遮罩浓度 */
    val backdropVeil: Float,
    val backdropVeilColor: Color,

    // ---------- 玻璃 ----------
    val glassTint: Color,
    val glassTintStrong: Color,
    val borderAlpha: Float,
    val borderColor: Color,

    // ---------- 文字 ----------
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    // ---------- 语义色 ----------
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val stateIdle: Color,

    val isDark: Boolean,
)

/**
 * 深色主题（蓝调）。
 *
 * 底色是接近黑的深海军蓝，光斑走「天蓝 → 靛蓝 → 紫蓝」的过渡，
 * 强调色用明亮的冰蓝而不是青绿——这样玻璃透出来的光是冷蓝色的。
 */
val DarkGlassColors = GlassColors(
    backgroundDeep = Color(0xFF04070F),
    backgroundMid = Color(0xFF080F1C),
    backgroundAccent = Color(0xFF0B1830),
    // 主光斑：明亮的天蓝
    blobPrimary = Color(0xFF3D9BFF),
    // 次光斑：靛蓝
    blobSecondary = Color(0xFF5B6CF0),
    // 第三光斑：紫蓝
    blobTertiary = Color(0xFF8B5CF6),
    // 第四光斑：青蓝（只做点缀，比例小，避免整体发绿）
    blobQuartiary = Color(0xFF2DD4E8),
    backdropVeil = 0.16f,
    backdropVeilColor = Color(0xFF02040A),
    glassTint = Color(0x14FFFFFF),
    glassTintStrong = Color(0x1FFFFFFF),
    borderAlpha = 0.16f,
    borderColor = Color.White,
    textPrimary = Color(0xFFF4F7FC),
    textSecondary = Color(0xB8F4F7FC),
    textTertiary = Color(0x6EF4F7FC),
    // 强调色：冰蓝（原来是青绿，改成蓝）
    accent = Color(0xFF7CC4FF),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    stateIdle = Color(0xFF8B9AAE),
    isDark = true,
)

/**
 * 浅色主题（蓝调）。
 *
 * 底色是带冷蓝的浅灰（不是纯白），光斑用饱和的中蓝/靛蓝，
 * 玻璃用"实白 + 白描边"，强调色用深一档的蓝以保证对比度。
 */
val LightGlassColors = GlassColors(
    backgroundDeep = Color(0xFFE8F0FB),
    backgroundMid = Color(0xFFF2F7FE),
    backgroundAccent = Color(0xFFE2ECFA),
    // 【配色关键】四个光斑必须全部落在蓝—青区间（190°~220°）。
    //
    // 曾经用 #8F7BE8（紫）做主光斑之一，结果：
    //   背景模糊后整片发紫 → 玻璃采样到紫色 → vibrancy(saturation 1.5) 再放大
    //   → 首页卡片看起来是淡紫色，和设置页的蓝调完全不是一套。
    //
    // 现在靠**明度与色相微差**拉开层次，而不是靠色相跨度：
    //   天蓝 205° / 宝蓝 218° / 浅靛 214° / 青蓝 190°
    blobPrimary = Color(0xFF4DA3FF),
    blobSecondary = Color(0xFF5B8DFF),
    blobTertiary = Color(0xFF7BA8FF),
    blobQuartiary = Color(0xFF38C8E0),
    // 遮罩降到几乎不可见：浅色主题下遮罩会整体压灰，
    // 而玻璃的可读性已经由 tint + 描边保证了。
    backdropVeil = 0.02f,
    backdropVeilColor = Color(0xFF9FB0C8),
    glassTint = Color(0x8CFFFFFF),
    glassTintStrong = Color(0xBFFFFFFF),
    borderAlpha = 0.62f,
    borderColor = Color.White,
    textPrimary = Color(0xFF0B1220),
    textSecondary = Color(0xC20B1220),
    textTertiary = Color(0x800B1220),
    // 强调色：深蓝（浅底上蓝必须压深才看得清）
    accent = Color(0xFF1D6FD0),
    success = Color(0xFF128C3E),
    warning = Color(0xFFC26A05),
    error = Color(0xFFCC2222),
    stateIdle = Color(0xFF5A6878),
    isDark = false,
)