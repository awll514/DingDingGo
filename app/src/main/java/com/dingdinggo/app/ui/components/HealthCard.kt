package com.dingdinggo.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dingdinggo.app.core.HealthCheck
import com.dingdinggo.app.ui.glass.FrostedPanel
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme
import com.dingdinggo.app.ui.theme.IosEaseOut

/**
 * 首页保活健康度诊断卡片。
 *
 * - 优先突出确定的异常项并附带一键「去设置」跳转；
 * - 无法自动检测的项（neutral）清晰标注“需自行确认”，不混入失败计数；
 * - 支持轻量展开查看全部检测条目；诚实提示系统极端管控下仍可能延迟。
 */
@Composable
fun HealthCard(
    report: HealthCheck.Report?,
    onFix: (HealthCheck.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    var expanded by remember { mutableStateOf(false) }

    FrostedPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = GlassDimens.radiusMedium,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .ClickableNoRipple(enabled = report != null) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "保活健康度",
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                val okCount = report?.knownItems?.count { it.ok } ?: 0
                val total = report?.knownItems?.size ?: 0
                val pillColor = when {
                    report == null -> colors.textTertiary
                    report.allOk && report.alarmScheduled -> colors.success
                    else -> colors.warning
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(GlassDimens.radiusPill))
                            .background(pillColor.copy(alpha = if (colors.isDark) 0.16f else 0.12f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(pillColor),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = when {
                                    report == null -> "检测中"
                                    report.allOk && report.alarmScheduled -> "$okCount/$total 就绪"
                                    else -> "$okCount/$total 待关注"
                                },
                                color = pillColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (report != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (expanded) "收起" else "明细",
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (report == null) {
                Text(
                    text = "正在检测闹钟、无障碍、电源白名单与钉钉可达性…",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                )
            } else {
                val problems = report.problems
                val neutralItems = report.items.filter { it.neutral }

                if (problems.isEmpty() && report.alarmScheduled) {
                    Text(
                        text = "闹钟与核心保活条件已就绪。",
                        color = colors.textSecondary,
                        fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "HyperOS 极端后台管控下仍存在延迟唤醒可能，不承诺绝对不杀；保留多重闹钟与补打兜底。",
                        color = colors.textTertiary,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    val previewProblems = if (expanded) problems else problems.take(3)
                    previewProblems.forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        HealthRow(item = item, onFix = onFix)
                    }
                    if (!expanded && problems.size > 3) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "另有 ${problems.size - 3} 项待关注，点右上角「明细」展开",
                            color = colors.textTertiary,
                            fontSize = 10.5.sp,
                        )
                    }
                    if (!report.alarmScheduled && problems.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "当前未注册下次闹钟，请检查自动打卡开关或生效星期设置。",
                            color = colors.warning,
                            fontSize = 11.sp,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            GlassMotion.DISCLOSURE,
                            easing = IosEaseOut,
                        ),
                    ) + fadeIn(animationSpec = GlassMotion.easeOut(200)),
                    exit = shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            GlassMotion.DISCLOSURE,
                            easing = IosEaseOut,
                        ),
                    ) + fadeOut(animationSpec = GlassMotion.easeOut(160)),
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        val passed = report.knownItems.filter { it.ok }
                        if (passed.isNotEmpty()) {
                            SettingsDivider()
                            Spacer(Modifier.height(10.dp))
                            passed.forEachIndexed { index, item ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                HealthRow(item = item, onFix = onFix)
                            }
                        }
                        if (neutralItems.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            SettingsDivider()
                            Spacer(Modifier.height(10.dp))
                            neutralItems.forEachIndexed { index, item ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                NeutralRow(item = item, onFix = onFix)
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.stateIdle),
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
                fontSize = 10.5.sp,
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
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (item.ok) colors.success else colors.warning),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.label,
                    color = if (item.ok) colors.textPrimary else colors.textSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.detail,
                color = colors.textTertiary,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (!item.ok && item.canFix) {
            Spacer(Modifier.width(10.dp))
            GlassTextButton(text = "去处理", onClick = { onFix(item) }, accent = true)
        }
    }
}

/** 平面通透微玻璃胶囊按钮（无立体倒角高光，轻量融入液态玻璃平面层级） */
@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    tintColor: Color? = null,
) {
    val colors = GlassTheme.colors
    val effectiveTint = tintColor ?: if (accent) colors.accent else colors.textSecondary
    val useTintedLook = accent || tintColor != null
    val shape = RoundedCornerShape(GlassDimens.radiusPill)

    val flatFill = when {
        useTintedLook -> effectiveTint.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
        colors.isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.42f)
    }
    val flatBorder = when {
        useTintedLook -> effectiveTint.copy(alpha = if (colors.isDark) 0.28f else 0.20f)
        colors.isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color(0xFF2B3A4A).copy(alpha = 0.10f)
    }

    Box(
        modifier = modifier
            .ClickableNoRipple(onClick = onClick)
            .clip(shape)
            .background(flatFill)
            .border(0.75.dp, flatBorder, shape)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = effectiveTint,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.15.sp,
        )
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    ClickableNoRipple(onClick = onClick)
