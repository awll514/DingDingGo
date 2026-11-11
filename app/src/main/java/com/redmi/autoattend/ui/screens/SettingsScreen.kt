package com.redmi.autoattend.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings
import com.redmi.autoattend.BuildConfig
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.ChannelPreference
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.PunchLogEntity
import com.redmi.autoattend.data.db.PunchOutcome
import com.redmi.autoattend.ui.HomeUiState
import com.redmi.autoattend.ui.components.GlassSwitch
import com.redmi.autoattend.ui.components.GlassTextButton
import com.redmi.autoattend.ui.components.PasswordInput
import com.redmi.autoattend.ui.components.SegmentedSelector
import com.redmi.autoattend.ui.components.SettingsDivider
import com.redmi.autoattend.ui.components.SettingsGroup
import com.redmi.autoattend.ui.components.SettingsRow
import com.redmi.autoattend.ui.components.WeekdaySelector
import com.redmi.autoattend.ui.glass.currentGlassPipelineLabel
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme
import com.redmi.autoattend.ui.theme.ThemeMode

/**
 * 设置页。
 *
 * 分组：自动打卡总开关、打卡时间、安全、权限与保活、通知、日志、关于。
 *
 * 全部用轻量磨砂，不开 AGSL —— 这里分组多，开全管线必然掉帧。
 */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    logs: List<PunchLogEntity>,
    onScheduleChange: (Int, Int, Set<Int>) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onChannelChange: (ChannelPreference) -> Unit,
    onSavePassword: (String) -> Result<Unit>,
    onClearPassword: () -> Unit,
    onFixPermission: (String) -> Unit,

    onRecheckRoot: () -> Unit,
    onClearLogs: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onRefreshPersistent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var timeEditor by remember { mutableStateOf<String?>(null) }
    var showPasswordEditor by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = AppConfig.settings.value
    val passwordLength = AppConfig.passwordLength.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        Text(
            text = "设置",
            color = GlassTheme.colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(18.dp))

        // ---------- 总开关 ----------
        SettingsGroup(title = "自动打卡") {
            SettingsRow(
                title = "启用定时打卡",
                subtitle = "关闭后不再触发任何闹钟",
                trailing = {
                    GlassSwitch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled,
                    )
                },
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 外观 ----------
        SettingsGroup(title = "外观") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "主题",
                        color = GlassTheme.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when (state.themeMode) {
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                            ThemeMode.SYSTEM -> "跟随系统"
                        },
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                SegmentedSelector(
                    options = listOf("浅色", "深色", "跟随系统"),
                    selectedIndex = when (state.themeMode) {
                        ThemeMode.LIGHT -> 0
                        ThemeMode.DARK -> 1
                        ThemeMode.SYSTEM -> 2
                    },
                    onSelect = { index ->
                        onThemeChange(
                            when (index) {
                                0 -> ThemeMode.LIGHT
                                1 -> ThemeMode.DARK
                                else -> ThemeMode.SYSTEM
                            }
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "跟随系统会读取 HyperOS 的深色模式开关。",
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 时间 ----------
        SettingsGroup(title = "打卡时间") {
            SettingsRow(
                title = "上班时间",
                value = PunchSchedule.formatMinute(settings.clockInMinute),
                onClick = { timeEditor = "in" },
            )
            SettingsDivider()
            SettingsRow(
                title = "下班时间",
                value = PunchSchedule.formatMinute(settings.clockOutMinute),
                onClick = { timeEditor = "out" },
            )
            SettingsDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "生效日",
                    color = GlassTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                WeekdaySelector(
                    selected = settings.activeDays,
                    onToggle = { day ->
                        val next = settings.activeDays.toMutableSet()
                        if (day in next) next.remove(day) else next.add(day)
                        if (next.isNotEmpty()) {
                            onScheduleChange(
                                settings.clockInMinute,
                                settings.clockOutMinute,
                                next,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "至少保留一天。需要临时改某一天，请在首页用手动打卡。",
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 安全 ----------
        SettingsGroup(title = "安全") {
            SettingsRow(
                title = "锁屏密码",
                subtitle = if (state.hasPassword) {
                    "已设置 " + passwordLength + " 位数字密码"
                } else {
                    "未设置 —— 锁屏状态下无法自动进入桌面"
                },
                value = if (state.hasPassword) "已配置" else "未配置",
                onClick = { showPasswordEditor = !showPasswordEditor },
            )

            AnimatedVisibility(
                visible = showPasswordEditor,
                enter = fadeIn(tween(220)) + expandVertically(tween(280)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    PasswordInput(
                        length = 6,
                        onComplete = { pwd ->
                            val result = onSavePassword(pwd)
                            passwordError = result.exceptionOrNull()?.message
                            if (result.isSuccess) showPasswordEditor = false
                        },
                    )
                    if (passwordError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = passwordError ?: "",
                            color = GlassTheme.colors.error,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "密码经 EncryptedSharedPreferences 加密存储，" +
                            "不会写入日志、数据库或任何网络请求。",
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 10.sp,
                    )
                    if (state.hasPassword) {
                        Spacer(Modifier.height(10.dp))
                        GlassTextButton(
                            text = "清除已保存的密码",
                            onClick = { onClearPassword() },
                        )
                    }
                }
            }

            SettingsDivider()

            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "执行后端",
                    color = GlassTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自动：无障碍优先，连续失败后自动降级到 root",
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(10.dp))
                SegmentedSelector(
                    options = listOf("自动", "仅无障碍", "仅 Root"),
                    selectedIndex = when (state.channel) {
                        ChannelPreference.AUTO -> 0
                        ChannelPreference.ACCESSIBILITY_ONLY -> 1
                        ChannelPreference.ROOT_ONLY -> 2
                    },
                    onSelect = { index ->
                        onChannelChange(
                            when (index) {
                                0 -> ChannelPreference.AUTO
                                1 -> ChannelPreference.ACCESSIBILITY_ONLY
                                else -> ChannelPreference.ROOT_ONLY
                            }
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 通知 ----------
        SettingsGroup(title = "通知") {
            // ---- 屏幕接管悬浮窗 ----
            //
            // 这里要给出两样东西：
            //   1. 开关（要不要在打卡时显示）
            //   2. 权限入口（系统悬浮窗需要用户手动授权，否则开关开了也不显示）
            var showHud by remember {
                mutableStateOf(AppConfig.showControlHud.value)
            }
            val canOverlay = remember { Settings.canDrawOverlays(context) }

            SettingsRow(
                title = "屏幕接管提示",
                subtitle = when {
                    !canOverlay -> "需要「显示在其他应用上层」权限才能生效"
                    showHud -> "打卡时会浮出提示条，可随时暂停或取消"
                    else -> "打卡时不显示提示，静默执行"
                },
                trailing = {
                    GlassSwitch(
                        checked = showHud,
                        onCheckedChange = {
                            showHud = it
                            AppConfig.setShowControlHud(it)
                        },
                    )
                },
            )

            // 没授权时高亮一个跳转入口 —— 光有开关没有权限是空转的。
            if (!canOverlay) {
                SettingsDivider()
                SettingsRow(
                    title = "授予悬浮窗权限",
                    subtitle = "没有这个权限，上面的开关不会生效",
                    danger = false,
                    onClick = { openOverlaySettings(context) },
                )
            }


            // 常驻通知开关：
            // 关掉后保活服务仍然运行，只是通知栏不留那一行。
            var showPersistent by remember {
                mutableStateOf(AppConfig.showPersistent.value)
            }
            SettingsRow(
                title = "常驻保活通知",
                subtitle = if (showPersistent) {
                    "通知栏显示「正在守护」，可一眼确认还在运行"
                } else {
                    "通知栏不显示，但后台保活仍在工作"
                },
                trailing = {
                    GlassSwitch(
                        checked = showPersistent,
                        onCheckedChange = {
                            showPersistent = it
                            AppConfig.setShowPersistent(it)
                            // 立即刷新通知内容
                            onRefreshPersistent()
                        },
                    )
                },
            )

        }

        Spacer(Modifier.height(18.dp))

        // ---------- 日志 ----------
        SettingsGroup(title = "打卡日志") {
            SettingsRow(
                title = "最近记录",
                subtitle = "共 " + logs.size + " 条，保留 30 天",
                onClick = { showLogs = !showLogs },
            )
            AnimatedVisibility(
                visible = showLogs,
                enter = fadeIn(tween(220)) + expandVertically(tween(280)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "还没有打卡记录",
                            color = GlassTheme.colors.textTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else {
                        logs.take(30).forEach { log ->
                            LogRow(log)
                        }
                    }
                    if (logs.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        GlassTextButton(
                            text = "清空全部日志",
                            onClick = onClearLogs,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 关于 ----------
        SettingsGroup(title = "关于") {
            SettingsRow(
                title = "渲染管线",
                subtitle = "当前设备的液态玻璃实现等级",
                value = currentGlassPipelineLabel(),
            )
            SettingsDivider()
            SettingsRow(
                title = "已知限制",
                subtitle = "HyperOS 的后台管控可能导致闹钟延迟或被清理。" +
                    "本应用用多重闹钟兜底提高成功率，但不保证 100% 准时。",
            )
            SettingsDivider()
            SettingsRow(
                title = "版本",
                value = BuildConfig.VERSION_NAME,
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 权限与保活 ----------
        //
        // 【间距修正】这里原本**没有**前置 Spacer。
        //
        // 上面每一个分组都是「Spacer(18dp) + SettingsGroup(...)」的固定节奏，
        // 只有这一组直接从上一个分组的底边开始，于是它的小标题
        // 「权限与保活」紧紧贴在上一个分组卡片的下边缘上，比其他分组窄了 18dp。
        //
        // 用户反馈"权限与保活上边距不对"指的就是这个 ——
        // 不是卡片内部的内边距问题，而是分组之间的节奏断了。
        SettingsGroup(title = "权限与保活") {
            val items = state.health?.items ?: emptyList()
            items.forEachIndexed { index, item ->
                SettingsRow(
                    title = item.label,
                    subtitle = item.detail,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .height(7.dp)
                                    .background(
                                        if (item.ok) GlassTheme.colors.success else GlassTheme.colors.warning,
                                        CircleShape,
                                    ),
                            )
                            if (!item.ok) {
                                Spacer(Modifier.width(10.dp))
                                GlassTextButton(
                                    text = "修复",
                                    accent = true,
                                    onClick = { onFixPermission(item.key) },
                                )
                            }
                        }
                    },
                )
                if (index != items.lastIndex) SettingsDivider()
            }

            SettingsDivider()
            SettingsRow(
                title = "重新检测 Root",
                subtitle = "刚授权过的话点这里刷新状态",
                onClick = onRecheckRoot,
            )
        }

        Spacer(Modifier.height(18.dp))

        // 与首页一致：只留刚好让最后一行露出底栏的高度（底栏 52 + padding 20 ≈ 72dp）
        Spacer(Modifier.height(74.dp))
    }

    // ---------- 时间编辑弹层 ----------
    val editorTarget = timeEditor
    if (editorTarget != null) {
        val isIn = editorTarget == "in"
        val initial = if (isIn) settings.clockInMinute else settings.clockOutMinute

        TimeEditorSheet(
            title = if (isIn) "上班时间" else "下班时间",
            initialMinute = initial,
            onDismiss = { timeEditor = null },
            onConfirm = { minute ->
                onScheduleChange(
                    if (isIn) minute else settings.clockInMinute,
                    if (isIn) settings.clockOutMinute else minute,
                    settings.activeDays,
                )
                timeEditor = null
            },
        )
    }
}

/** 单条日志 */
@Composable
private fun LogRow(log: PunchLogEntity) {
    val color = if (log.outcome == PunchOutcome.SUCCESS) {
        GlassTheme.colors.success
    } else {
        GlassTheme.colors.error
    }

    val kindText = when (log.kind) {
        com.redmi.autoattend.data.db.PunchKind.CLOCK_IN -> "上班"
        com.redmi.autoattend.data.db.PunchKind.CLOCK_OUT -> "下班"
        com.redmi.autoattend.data.db.PunchKind.MANUAL -> "手动"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(6.dp)
                .height(6.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = PunchSchedule.formatTime(log.startedAt) + " · " + kindText,
                color = GlassTheme.colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            val msg = log.message
            if (msg != null && msg.isNotEmpty()) {
                Text(
                    text = msg,
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                )
            }
            val evidence = log.evidence
            if (evidence != null && evidence.isNotEmpty()) {
                Text(
                    text = "判定依据：" + evidence,
                    color = GlassTheme.colors.success.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                )
            }
            val failed = log.failedStage
            if (failed != null && failed.isNotEmpty()) {
                Text(
                    text = "失败环节：" + failed,
                    color = GlassTheme.colors.error.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * 跳转到系统的「显示在其他应用上层」授权页。
 *
 * 不同 ROM 的入口不一样，这里按优先级逐个尝试：
 *   1. 本应用专属的悬浮窗授权页（最直接，能直接看到开关）；
 *   2. 通用悬浮窗列表；
 *   3. 应用详情页（兜底）。
 *
 * 全部失败时静默忽略 —— 用户仍可以自己去设置里找。
 */
private fun openOverlaySettings(context: android.content.Context) {
    val candidates = listOf(
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + context.packageName),
        ),
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName),
        ),
    )
    for (intent in candidates) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}