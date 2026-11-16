package com.dingdinggo.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 液态玻璃配色体系（冷蓝极光基调 · 深浅两套独立光学调校）。
 *
 * 语义色（success / warning / error）保持独立色相，确保状态判断始终直观。
 */
data class GlassColors(
    val backgroundDeep: Color,
    val backgroundMid: Color,
    val backgroundAccent: Color,
    val blobPrimary: Color,
    val blobSecondary: Color,
    val blobTertiary: Color,
    val blobQuartiary: Color,
    val backdropVeil: Float,
    val backdropVeilColor: Color,
    val glassTint: Color,
    val glassTintStrong: Color,
    val borderAlpha: Float,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val stateIdle: Color,
    val isDark: Boolean,
)

val DarkGlassColors = GlassColors(
    backgroundDeep = Color(0xFF030712),
    backgroundMid = Color(0xFF071124),
    backgroundAccent = Color(0xFF0B1C38),
    blobPrimary = Color(0xFF3898FF),
    blobSecondary = Color(0xFF5A6CF6),
    blobTertiary = Color(0xFF7D5BF8),
    blobQuartiary = Color(0xFF22D3EE),
    backdropVeil = 0.14f,
    backdropVeilColor = Color(0xFF02040B),
    glassTint = Color(0x280B162C),
    glassTintStrong = Color(0x4010203E),
    borderAlpha = 0.22f,
    borderColor = Color(0xFFEAF2FF),
    textPrimary = Color(0xFFF5F8FF),
    textSecondary = Color(0xBDF0F5FF),
    textTertiary = Color(0x78E6EEFF),
    accent = Color(0xFF6BB8FF),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    stateIdle = Color(0xFF8CA0BA),
    isDark = true,
)

val LightGlassColors = GlassColors(
    backgroundDeep = Color(0xFFE5EEFA),
    backgroundMid = Color(0xFFF1F6FE),
    backgroundAccent = Color(0xFFDBE8FB),
    blobPrimary = Color(0xFF3E9BFF),
    blobSecondary = Color(0xFF5A8CFF),
    blobTertiary = Color(0xFF7DA9FF),
    blobQuartiary = Color(0xFF2CC9E4),
    backdropVeil = 0.02f,
    backdropVeilColor = Color(0xFF9DB2CE),
    glassTint = Color(0x7AF7FAFF),
    glassTintStrong = Color(0xA6FFFFFF),
    borderAlpha = 0.72f,
    borderColor = Color.White,
    textPrimary = Color(0xFF0B1424),
    textSecondary = Color(0xBF0E1A2E),
    textTertiary = Color(0x80162742),
    accent = Color(0xFF1565D8),
    success = Color(0xFF0E8F47),
    warning = Color(0xFFC76A08),
    error = Color(0xFFD02626),
    stateIdle = Color(0xFF59697D),
    isDark = false,
)
