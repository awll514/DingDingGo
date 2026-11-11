package com.redmi.autoattend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.core.HealthCheck
import com.redmi.autoattend.ui.glass.FrostedPanel
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 保活健康度卡片。
 *
 * 诚实原则：只陈述"检测到什么"，不承诺"绝对不死"。
 * 全部正常时不占用列表空间，只留一行结论 + 一行风险提示。
 */
@Composable
fun HealthCard(
    report: HealthCheck.Report?,
    onFix: (HealthCheck.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors

    // ============ 材质与配色的统一（真机实测驱动）============
    //
    // 用户反馈：时间卡片/打卡卡片偏青，健康度卡片偏蓝，要求同步。
    //
    // 试过把本卡片也改成 LiquidGlassPanel，结果是：
    //   · 布局塌陷（两者内容尺寸计算方式不同）
    //   · 倒计时数字出现 AGSL 自引用残影
    // 所以维持 FrostedPanel —— 它是列表项的正确选择，
    // 问题只是**配色没对齐**。
    //
    // 真机取样（h7.png，1280x2772）：
    //   时间卡片中心  R=145 G=204 B=241
    //   打卡卡片中心  R=134 G=203 B=248
    //   健康度卡片    R=155 G=213 B=251
    //
    // 三者本该接近，但 FrostedPanel 的颜色完全由 tint 决定、
    // 不采样背景，所以固定偏蓝。
    //
    // ## 为什么改 tint 的 RGB 而几乎无效
    //
    // FrostedPanel 在浅色主题下会把 tint 的 alpha 再乘 0.42 / 0.24：
    //     base.copy(alpha = base.alpha * 0.42f)
    //
    // 我传的 0x16 是 0.086，再乘 0.42 → 只剩 3.6%，几乎完全透明。
    // 所以无论 RGB 怎么改，在最终画面上都看不出来。
    //
    // 正确做法：**不传 tint，而是给一个明显更高的 alpha**，
    // 让它在乘完 0.42 之后仍有可见的存在感。
    //
    // 实测目标色（时间卡片中心）：R=144 G=203 B=241。
    // 用 R 低于 G、G 低于 B 的冷青白，配合较高 alpha，
    // 让合成结果落在同一色域。
    FrostedPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = GlassDimens.radiusMedium,
        tint = Color(0x4DEAF4FF),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "保活健康度",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                // 计数只统计"能确定"的项，无法检测的（neutral）不计入分母，
                // 否则用户会看到一个永远凑不满的 6/8。
                val okCount = report?.knownItems?.count { it.ok } ?: 0
                val total = report?.knownItems?.size ?: 0

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (report != null) {
                        Box(
                            Modifier
                                .width(6.dp)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (report.allOk) colors.success else colors.warning
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = if (report == null) "检测中" else okCount.toString() + "/" + total,
                        color = when {
                            report == null -> colors.textTertiary
                            report.allOk -> colors.success
                            else -> colors.warning
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (report == null) {
                Text(
                    text = "正在检测权限与保活状态…",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                )
            } else {
                val problems = report.problems
                val neutralItems = report.items.filter { it.neutral }
                if (problems.isEmpty() && report.alarmScheduled) {
                    Text(
                        text = "权限与保活检查全部通过。",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "系统仍可能在极端情况下延迟唤醒。本应用用多重闹钟兜底，但不保证 100% 准时。",
                        color = colors.textTertiary,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    problems.take(3).forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        HealthRow(item = item, onFix = onFix)
                    }
                    if (problems.size > 3) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "另有 " + (problems.size - 3) + " 项待处理，见设置页",
                            color = colors.textTertiary,
                            fontSize = 10.sp,
                        )
                    }

                    // 无法自动检测的项单独列出，明确标注"需自行确认"，
                    // 不和"确实失败"混在一起。
                    neutralItems.take(2).forEach { item ->
                        Spacer(Modifier.height(10.dp))
                        NeutralRow(item = item, onFix = onFix)
                    }
                    if (!report.alarmScheduled && problems.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "当前没有已注册的闹钟，请确认定时打卡是否已关闭。",
                            color = colors.warning,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 无法自动检测的项。
 *
 * 视觉上和"确定失败"刻意区分：灰点 + 灰字，并且标题里就写明"无法自动检测"。
 * 目的是让用户知道"这不是我的错，也不是 App 在报错，而是系统不给读"。
 */
@Composable
private fun NeutralRow(
    item: HealthCheck.Item,
    onFix: (HealthCheck.Item) -> Unit,
) {
    val colors = GlassTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(colors.stateIdle)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.label,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.detail,
                color = colors.textTertiary.copy(alpha = 0.85f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (item.canFix) {
            Spacer(Modifier.width(10.dp))
            GlassTextButton(text = "去设置", onClick = { onFix(item) }, accent = false)
        }
    }
}

@Composable
private fun HealthRow(
    item: HealthCheck.Item,
    onFix: (HealthCheck.Item) -> Unit,
) {
    val colors = GlassTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (item.ok) colors.success else colors.warning)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.label,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.detail,
                color = colors.textTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (item.canFix) {
            Spacer(Modifier.width(10.dp))
            GlassTextButton(text = "去设置", onClick = { onFix(item) }, accent = true)
        }
    }
}

/** 极简玻璃文字按钮 */
@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = GlassTheme.colors

    FrostedPanel(
        modifier = modifier.ClickableNoRipple(onClick = onClick),
        cornerRadius = GlassDimens.radiusPill,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
        tint = if (accent) colors.accent.copy(alpha = 0.16f) else colors.glassTint,
        borderAlpha = if (accent) 0.30f else null,
    ) {
        Text(
            text = text,
            color = if (accent) colors.accent else colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 无涟漪点击（兼容旧引用名）。
 *
 * 保留这个别名是为了不改动已有调用点；内部直接转给 ClickableNoRipple，
 * 所以自动获得按压缩放 + 触觉反馈。
 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    ClickableNoRipple(onClick = onClick)