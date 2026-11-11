package com.redmi.autoattend.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.core.HealthCheck
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.ui.HomeStatus
import com.redmi.autoattend.ui.HomeUiState
import com.redmi.autoattend.ui.components.CountdownPanelContent
import com.redmi.autoattend.ui.components.ConfirmDialog
import com.redmi.autoattend.ui.components.LiquidGlassButton
import com.redmi.autoattend.ui.components.HealthCard
import com.redmi.autoattend.ui.components.PunchStatusRow
import com.redmi.autoattend.ui.components.StatusPill
import com.redmi.autoattend.ui.components.ClickableNoRipple
import com.redmi.autoattend.ui.glass.FrostedPanel
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 首页。
 *
 * 结构与视觉层级（AGENTS.md 页面结构）：
 *   1. 顶部极简标题栏（页名 + 状态徽标）
 *   2. 倒计时大玻璃面板 —— 绝对主角，占屏约 1/3
 *   3. 今日打卡玻璃卡 —— 含一条进度指示
 *   4. 保活健康度（轻磨砂）
 *   5. 手动打卡兜底按钮
 *
 * 玻璃预算：本页只用 2 块全 AGSL 面板（倒计时、状态卡），
 * 第 3 块是底部 tab bar，满足"同时活跃全玻璃面 ≤ 3"的红线。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    busy: Boolean,
    onManualPunch: () -> Unit,
    onFixHealth: (HealthCheck.Item) -> Unit,
    onSkipToday: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors

    // 两个不可逆操作都需要二次确认
    var showPunchConfirm by remember { mutableStateOf(false) }
    var showSkipConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlassDimens.pagePadding),
    ) {
        Spacer(Modifier.height(20.dp))

        // ---------- 顶部标题栏 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                // Apple Large Title：34pt Regular（Bold 才是强调），
                // 字距收紧 -0.5，视觉上更"整"。
                Text(
                    text = "钉钉GO",
                    color = colors.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.8).sp,
                )
                Spacer(Modifier.height(2.dp))
                // Apple 的副标题：15pt secondary
                Text(
                    text = if (state.enabled) "定时任务运行中" else "定时任务已暂停",
                    color = if (state.enabled) colors.textTertiary else colors.warning,
                    fontSize = 13.sp,
                    letterSpacing = (-0.1).sp,
                )
            }
            StatusPillFor(state = state, busy = busy)
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 倒计时大玻璃面板 ----------
        LiquidGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = GlassDimens.radiusLarge,
            sampleOffsetX = 0.30f,
            sampleOffsetY = 0.22f,
        ) {
            CountdownPanelContent(
                kindLabel = kindLabelOf(state),
                countdown = state.countdownText,
                targetTimeLabel = targetLabelOf(state),
                hintLabel = hintOf(state),
                active = state.enabled,
            )
        }

        Spacer(Modifier.height(GlassDimens.cardGap))

        // ---------- 今日打卡玻璃卡 ----------
        LiquidGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = GlassDimens.radiusMedium,
            sampleOffsetX = 0.72f,
            sampleOffsetY = 0.62f,
        ) {
            Column(Modifier.padding(GlassDimens.cardPadding)) {
                // 卡片标题行 + 完成度
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "今日打卡",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = completionLabel(state),
                        color = if (isAllDone(state)) colors.success else colors.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(14.dp))

                PunchStatusRow(
                    label = "上班",
                    doneTime = state.todayClockIn?.let {
                        PunchSchedule.formatTime(it.startedAt)
                    },
                    done = state.todayClockIn != null,
                )

                Spacer(Modifier.height(14.dp))

                // 进度条：显示今天完成的阶段
                TodayProgress(
                    clockInDone = state.todayClockIn != null,
                    clockOutDone = state.todayClockOut != null,
                )

                Spacer(Modifier.height(14.dp))

                PunchStatusRow(
                    label = "下班",
                    doneTime = state.todayClockOut?.let {
                        PunchSchedule.formatTime(it.startedAt)
                    },
                    done = state.todayClockOut != null,
                    onColor = colors.accent,
                )
            }
        }

        Spacer(Modifier.height(GlassDimens.cardGap))

        // ---------- 手动打卡兜底 ----------
        // 顺序调整：手动打卡放在健康度之前。
        // 理由：健康度是"诊断信息"，日常用户不需要天天看；
        // 手动打卡是"操作入口"，应该更靠上更容易够到。
        ManualPunchButton(
            busy = busy,
            enabled = !busy,
            onClick = { showPunchConfirm = true },
        )

        Spacer(Modifier.height(GlassDimens.cardGap))

        // ---------- 保活健康度 ----------
        HealthCard(report = state.health, onFix = onFixHealth)

        Spacer(Modifier.height(18.dp))

        // ---------- 跳过 / 恢复（低权重文本按钮）----------
        //
        // 刻意做成"纯文字 + 无背景"，而不是和「立即手动打卡」同级的玻璃按钮：
        //
        // 1. 它是低频操作，不该抢主操作的视觉权重；
        // 2. 真机踩过坑 —— 系统权限弹窗消失的瞬间，
        //    原本落在弹窗上的点击会穿透到下面的按钮，
        //    结果"点允许通知"变成了"跳过今天"。
        //    把它做得更小、更靠下，能显著降低这类误触。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .ClickableNoRipple(onClick = { showSkipConfirm = true })
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state.skipToday) {
                    "取消跳过" + state.skippedDayLabel
                } else {
                    "跳过" + state.nextDayLabel + "的自动打卡"
                },
                color = if (state.skipToday) colors.success else colors.textTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "手动打卡会立即执行一次完整流程，适合配置后自测。",
            color = colors.textTertiary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // 列表末尾的占位。
        //
        // 【取值依据】只需要保证"滚到底时最后一项能完整露出"，不需要整块底栏高度。
        // 底栏 52dp + 上下 padding 20dp = 72dp；这里给 74dp，
        // 最后一行刚好落在底栏上沿之上一点 —— 与 iOS 一致。
        //
        // 之前用了 124dp，导致滚到底时底部空出一大片，
        // 底栏背后什么都没有，"折射底下的字"的效果自然看不见。
        Spacer(Modifier.height(74.dp))
    }

    // ---------- 二次确认 ----------
    if (showPunchConfirm) {
        ConfirmDialog(
            title = "立即执行一次打卡？",
            message = "会现在打开钉钉并真实点击打卡按钮，可能产生一条考勤记录。" +
                "如果不是上下班时间，钉钉可能拒绝或记为异常。",
            confirmText = "立即打卡",
            onConfirm = {
                showPunchConfirm = false
                onManualPunch()
            },
            onDismiss = { showPunchConfirm = false },
        )
    }

    if (showSkipConfirm) {
        val skipping = !state.skipToday
        val dayWord = state.nextDayLabel
        ConfirmDialog(
            title = if (skipping) "跳过" + dayWord + "的所有自动打卡？" else "恢复" + dayWord + "的自动打卡？",
            message = if (skipping) {
                dayWord + "不会再自动打上班卡和下班卡，之后自动恢复。" +
                    "如果这天已经打过，已产生的记录不受影响。"
            } else {
                dayWord + "剩余的打卡会重新按计划自动执行。已经错过的时间点不会补打。"
            },
            confirmText = if (skipping) "跳过" + dayWord else "恢复打卡",
            danger = skipping,
            onConfirm = {
                showSkipConfirm = false
                onSkipToday(skipping)
            },
            onDismiss = { showSkipConfirm = false },
        )
    }
}

/** 今日完成度文案 */
private fun completionLabel(state: HomeUiState): String {
    val a = state.todayClockIn != null
    val b = state.todayClockOut != null
    return when {
        a && b -> "已全部完成"
        a -> "已完成 1 / 2"
        b -> "已完成 1 / 2"
        else -> "待开始"
    }
}

private fun isAllDone(state: HomeUiState): Boolean =
    state.todayClockIn != null && state.todayClockOut != null

/**
 * 今日进度条：两段式，第一段代表上班，第二段代表下班。
 * 这是把"两个独立状态"在视觉上连成一条时间线，比两行文字更容易一眼看完。
 */
@Composable
private fun TodayProgress(clockInDone: Boolean, clockOutDone: Boolean) {
    val colors = GlassTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(filled = clockInDone, color = colors.success, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Segment(filled = clockOutDone, color = colors.accent, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Segment(
    filled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(CircleShape)
            .background(
                if (filled) {
                    color.copy(alpha = 0.85f)
                } else {
                    colors.textTertiary.copy(alpha = 0.16f)
                }
            ),
    )
}

/** 状态徽标 */
@Composable
private fun StatusPillFor(state: HomeUiState, busy: Boolean) {
    val colors = GlassTheme.colors

    val (text, color, pulsing) = when {
        busy -> Triple("执行中", colors.accent, true)
        else -> when (val s = state.status) {
            is HomeStatus.Running -> Triple("执行中", colors.accent, true)
            is HomeStatus.Waiting -> Triple("等待执行", colors.warning, true)
            is HomeStatus.Success -> Triple("已完成", colors.success, false)
            is HomeStatus.Failed -> Triple("上次失败", colors.error, false)
            is HomeStatus.Idle -> Triple("未到时间", colors.stateIdle, false)
        }
    }
    StatusPill(text = text, color = color, pulsing = pulsing)
}

/** 倒计时面板蓝色标题：只写"下一次 · 什么时候做什么"，不带日期 */
private fun kindLabelOf(state: HomeUiState): String {
    val slot = state.nextSlot ?: return "今日已完成"
    return when (slot.kind) {
        PunchKind.CLOCK_IN -> "下一次 · 上班打卡"
        PunchKind.CLOCK_OUT -> "下一次 · 下班打卡"
        PunchKind.MANUAL -> "下一次 · 手动打卡"
    }
}

private fun targetLabelOf(state: HomeUiState): String {
    val slot = state.nextSlot
    if (slot == null) {
        // 没有下一次打卡 = 休假中。用祝福语而不是冰冷的"无计划"。
        return if (state.onVacation) "好好休息，明天再战" else "今天没有待执行的打卡"
    }
    val kindText = when (slot.kind) {
        PunchKind.CLOCK_IN -> "上班"
        PunchKind.CLOCK_OUT -> "下班"
        PunchKind.MANUAL -> "手动"
    }
    val override = if (slot.isOverride) " · 临时覆盖" else ""
    // 日期写在原来"目标"两个字的位置：
    // 「今天 09:00:00 · 上班」比「目标 09:00:00 · 上班」信息量大得多，
    // 而且用户一眼就能知道这是今天还是明天要打。
    val day = if (state.nextDayLabel.isNotEmpty()) state.nextDayLabel else "目标"
    return day + " " + PunchSchedule.formatTime(slot.targetAtMillis) + " · " + kindText + override
}

private fun hintOf(state: HomeUiState): String {
    if (state.onVacation) return "当前没有任何生效日，已自动暂停"
    if (!state.enabled) return "自动打卡已暂停，不会自动执行"
    if (!state.hasPassword) return "未设置锁屏密码，锁屏时可能无法自动进入桌面"
    return "目标时间前 75 秒开始准备"
}

/**
 * 手动打卡按钮。
 *
 * 用轻量磨砂而非全玻璃——它在页面底部，再开一块 AGSL 面就超预算了。
 * 视觉上用"描边 + 极淡填充"，比实心按钮更贴合玻璃语言。
 */
@Composable
private fun ManualPunchButton(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = GlassTheme.colors

    // 用 LiquidGlassButton：按压时会同时做四层玻璃反应
    // （缩放 / 高光下移 / 折射增强 / 触觉），而不是单纯缩放。
    LiquidGlassButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        tint = colors.accent,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                SpinnerDot()
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "正在执行",
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                )
            } else {
                Text(
                    text = "立即手动打卡",
                    color = colors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}

/**
 * 执行中的呼吸圆点。
 *
 * 用透明度呼吸代替转圈：转圈在玻璃面板上显得很"应用商店"，
 * 呼吸更安静，也更贴合液态玻璃的调性。
 */
@Composable
private fun SpinnerDot() {
    val colors = GlassTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(620),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )

    Box(
        Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(colors.accent.copy(alpha = alpha)),
    )
}