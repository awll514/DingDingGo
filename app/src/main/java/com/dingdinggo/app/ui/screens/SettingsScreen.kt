package com.dingdinggo.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings
import com.dingdinggo.app.BuildConfig
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.ChannelPreference
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchLogEntity
import com.dingdinggo.app.data.db.PunchOutcome
import com.dingdinggo.app.ui.HomeUiState
import com.dingdinggo.app.ui.components.GlassSwitch
import com.dingdinggo.app.ui.components.GlassTextButton
import com.dingdinggo.app.ui.components.PasswordInput
import com.dingdinggo.app.ui.components.PixelDingMascotPortal
import com.dingdinggo.app.ui.components.PixelStarAccent
import com.dingdinggo.app.ui.components.SegmentedSelector
import com.dingdinggo.app.ui.components.DingMascotMood
import com.dingdinggo.app.ui.components.SettingsDivider
import com.dingdinggo.app.ui.components.SettingsGroup
import com.dingdinggo.app.ui.components.SettingsRow
import com.dingdinggo.app.ui.components.WeekdaySelector
import com.dingdinggo.app.ui.glass.currentGlassPipelineLabel
import com.dingdinggo.app.ui.theme.GlassDimens
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme
import com.dingdinggo.app.ui.theme.IosEaseOut
import com.dingdinggo.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay

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
    // ---- 蓝牙触发 ----
    onBtToggle: (Boolean) -> Unit = {},
    /** 请求蓝牙扫描权限（由 Activity 发起运行时申请） */
    onRequestBtPermission: () -> Unit = {},
    onBtEarlyWindow: (Int) -> Unit = {},
    onBtLearn: ((Boolean, String) -> Unit) -> Unit = {},
    onBtClearDevice: () -> Unit = {},
    onBtResetToday: () -> Unit = {},
    // ---- 公司 Wi-Fi 触发 ----
    onWifiToggle: (Boolean) -> Unit = {},
    onRequestWifiPermission: () -> Unit = {},
    onWifiLearn: ((Boolean, String) -> Unit) -> Unit = {},
    onWifiClear: () -> Unit = {},
    /**
     * 外部请求把「锁屏密码」编辑器展开（健康度卡片的「修复」按钮用）。
     *
     * 【审计 UI#3】健康度里密码项标红时点「修复」原本是 handlePermissionFix
     * 的 `"password" -> Unit`，**点了完全没反应** —— 静默失败。
     * 现在通过这个自增信号把用户直接带到密码编辑器。
     */
    focusPasswordSignal: Int = 0,
    /** 外部请求把「打卡时间」滚动到视野内（上下班时间顺序异常时「修复」按钮用） */
    focusScheduleSignal: Int = 0,
    /** 外部请求把「公司 Wi-Fi 自动打卡」滚动到视野内 */
    focusWifiSignal: Int = 0,
    modifier: Modifier = Modifier,
) {
    var timeEditor by remember { mutableStateOf<String?>(null) }
    var showPasswordEditor by remember { mutableStateOf(false) }
    val securityBringIntoView = remember { BringIntoViewRequester() }
    val scheduleBringIntoView = remember { BringIntoViewRequester() }
    val wifiBringIntoView = remember { BringIntoViewRequester() }
    val storageDegraded by AppConfig.secureStorageDegraded.collectAsState()

    // 收到外部信号就展开密码编辑区并滚动至视野内
    androidx.compose.runtime.LaunchedEffect(focusPasswordSignal) {
        if (focusPasswordSignal > 0) {
            showPasswordEditor = true
            delay(140)
            runCatching { securityBringIntoView.bringIntoView() }
        }
    }
    // 收到外部信号就把「打卡时间」滚动到视野内
    androidx.compose.runtime.LaunchedEffect(focusScheduleSignal) {
        if (focusScheduleSignal > 0) {
            delay(100)
            runCatching { scheduleBringIntoView.bringIntoView() }
        }
    }
    androidx.compose.runtime.LaunchedEffect(focusWifiSignal) {
        if (focusWifiSignal > 0) {
            delay(100)
            runCatching { wifiBringIntoView.bringIntoView() }
        }
    }
    var showLogs by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by AppConfig.settings.collectAsState()
    val passwordLength by AppConfig.passwordLength.collectAsState()

    // 每次回到页面（ON_RESUME）重新读取系统悬浮窗权限状态，供「通知」和「调试」分组共用
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canOverlay = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlassDimens.pagePadding),
    ) {
        Spacer(Modifier.height(20.dp))

        Text(
            text = "设置",
            color = GlassTheme.colors.textPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
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

        // ---------- 打卡时间 ----------
        Column(Modifier.bringIntoViewRequester(scheduleBringIntoView)) {
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
        }

        Spacer(Modifier.height(18.dp))

        // ---------- 蓝牙自动打卡 ----------
        BtSettingsGroup(
            bt = state.bt,
            onToggle = onBtToggle,
            onEarlyWindow = onBtEarlyWindow,
            onLearn = onBtLearn,
            onClearDevice = onBtClearDevice,
            onResetToday = onBtResetToday,
            onRequestPermission = onRequestBtPermission,
        )

        Spacer(Modifier.height(18.dp))

        // ---------- 公司 Wi-Fi 自动打卡 ----------
        val wifiHealthFailed = state.health?.items?.any { it.key == "wifi_trigger" && !it.ok } == true
        val missingWifiLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            com.dingdinggo.app.ble.BtScanner.needsBackgroundLocation(context)
        WifiSettingsGroup(
            wifi = state.wifi,
            needsPermission = wifiHealthFailed && missingWifiLoc,
            onToggle = onWifiToggle,
            onLearn = onWifiLearn,
            onClear = onWifiClear,
            onRequestPermission = onRequestWifiPermission,
            modifier = Modifier.bringIntoViewRequester(wifiBringIntoView),
        )

        Spacer(Modifier.height(18.dp))

        // ---------- 安全 ----------
        Column(Modifier.bringIntoViewRequester(securityBringIntoView)) {
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

                if (storageDegraded) {
                    val colors = GlassTheme.colors
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(GlassDimens.radiusSmall))
                            .background(colors.warning.copy(alpha = if (colors.isDark) 0.16f else 0.12f))
                            .border(
                                1.dp,
                                colors.warning.copy(alpha = 0.30f),
                                RoundedCornerShape(GlassDimens.radiusSmall),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "系统加密密钥库初始化异常，当前密码存储已降级，请谨慎评估安全风险。",
                            color = colors.warning,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showPasswordEditor,
                    enter = fadeIn(GlassMotion.easeOut(200)) +
                        expandVertically(tween(GlassMotion.DISCLOSURE, easing = IosEaseOut)),
                    exit = fadeOut(GlassMotion.easeOut(160)) +
                        shrinkVertically(tween(GlassMotion.DISCLOSURE, easing = IosEaseOut)),
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
                    onClick = { com.dingdinggo.app.core.HealthCheck.openOverlaySettings(context) },
                )
            }


            SettingsDivider()

            // ---- 打卡后跳回本应用 ----
            //
            // 用户诉求：无论打卡成功还是失败，都不要把人晾在钉钉页面上，
            // 直接切回钉钉GO看结果。
            //
            // 实现上必须用 root 的 `am start`（后台 FGS 直接 startActivity
            // 会被 Android 15+ 的 BAL 限制静默拦掉，见 PunchForegroundService
            // 的 returnToAppIfRequested）。没有 root 时这个开关打开也无效，
            // 所以副标题里如实说明。
            // root 状态直接从健康度数据里取（key = "root"），
            // 不额外再 fork 一次 su 探测 —— 那会阻塞主线程 300~400ms。
            val rootOk = state.health?.items
                ?.firstOrNull { it.key == "root" }
                ?.ok == true
            var returnAfterPunch by remember {
                mutableStateOf(AppConfig.returnAfterPunch.value)
            }
            SettingsRow(
                title = "打卡后跳回本应用",
                subtitle = when {
                    !returnAfterPunch -> "打卡结束后停留在钉钉页面"
                    !rootOk -> "需要 root 才能从后台切回，当前未检测到 root"
                    else -> "成功或失败都自动切回钉钉GO，方便查看结果"
                },
                trailing = {
                    GlassSwitch(
                        checked = returnAfterPunch,
                        onCheckedChange = {
                            returnAfterPunch = it
                            AppConfig.setReturnAfterPunch(it)
                        },
                    )
                },
            )

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
                enter = fadeIn(GlassMotion.easeOut(200)) +
                    expandVertically(tween(GlassMotion.DISCLOSURE, easing = IosEaseOut)),
                exit = fadeOut(GlassMotion.easeOut(160)) +
                    shrinkVertically(tween(GlassMotion.DISCLOSURE, easing = IosEaseOut)),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PixelDingMascotPortal(
                    size = 44.dp,
                    cornerRadius = 14.dp,
                    mood = if (state.enabled) DingMascotMood.READY else DingMascotMood.PAUSED,
                    showStatusDot = false,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "钉钉GO · 像素钉钉娘",
                            color = GlassTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        PixelStarAccent(color = GlassTheme.colors.accent, size = 10.dp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "蓝白双翼晴空像素看板娘守护中",
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
            SettingsDivider()
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

        // ---------- 调试 ----------
        SettingsGroup(title = "调试") {
            SettingsRow(
                title = "预览钉钉娘悬浮窗",
                subtitle = if (canOverlay) {
                    "弹出 7 秒气泡悬浮窗，可点钉钉娘收起/展开或拖拽"
                } else {
                    "需要「显示在其他应用上层」权限，点此前往授权"
                },
                onClick = {
                    if (canOverlay) {
                        com.dingdinggo.app.exec.ControlHudOverlay.showForTest(context)
                    } else {
                        com.dingdinggo.app.core.HealthCheck.openOverlaySettings(context)
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                title = "预览迟到确认弹窗",
                subtitle = if (canOverlay) {
                    "弹出 60 秒自动关闭的安全预览确认窗（不会真打卡）"
                } else {
                    "需要「显示在其他应用上层」权限，点此前往授权"
                },
                onClick = {
                    if (canOverlay) {
                        com.dingdinggo.app.exec.LateConfirmOverlay.showPreview(context)
                    } else {
                        com.dingdinggo.app.core.HealthCheck.openOverlaySettings(context)
                    }
                },
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
                                        // 【审计 UI#2】neutral 不能画成警告色。
                                        //
                                        // HealthCheck 里用 neutral 表达"系统没提供读取接口，
                                        // 我们查不出来"（典型例子：HyperOS 的自启动状态）。
                                        // 这既不是"正常"也不是"有问题"。
                                        //
                                        // 旧实现只有 ok/warning 两种颜色，于是 neutral 项
                                        // 被画成黄点 + 显示「修复」按钮 —— 而自启动检测
                                        // 在这台机器上恒为 UNKNOWN，等于设置页常年挂着一个
                                        // 永远修不好的黄色警告，与首页"需自行确认"的中性
                                        // 措辞自相矛盾。
                                        when {
                                            item.neutral -> GlassTheme.colors.textTertiary
                                            item.ok -> GlassTheme.colors.success
                                            else -> GlassTheme.colors.warning
                                        },
                                        CircleShape,
                                    ),
                            )
                            if (!item.ok && !item.neutral && item.canFix) {
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
        Spacer(Modifier.height(GlassDimens.bottomBarReserved))
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
        com.dingdinggo.app.data.db.PunchKind.CLOCK_IN -> "上班"
        com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> "下班"
        com.dingdinggo.app.data.db.PunchKind.MANUAL -> "手动"
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
                text = log.displayTimeText() + " · " + kindText,
                color = GlassTheme.colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            val msg = log.message
            if (msg != null && msg.isNotEmpty()) {
                Text(
                    text = PunchSchedule.normalizeDurationDisplay(msg),
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
 * 蓝牙自动打卡设置组。
 *
 * ## 设计取向：把"会不会误打卡"讲清楚
 *
 * 这一组控件背后是一个会**真的去点钉钉打卡按钮**的功能。
 * 所以每一行都必须让用户能自己判断"它现在会做什么"：
 *
 * - 总开关下面写真实的当前状态（在场 / 不在场 / 未学习），
 *   而不是含糊的"已开启"；
 * - "学习当前考勤机"是一次性动作，有明确的成功/失败反馈；
 * - 早到窗口用分段控件，并说明它**只放宽早到，不放宽迟到**。
 *
 * 界面上不给任何"绝对可靠"的暗示 —— 蓝牙能否扫到受
 * HyperOS 后台管控、深度 Doze、以及考勤机广播类型的影响。
 */
@Composable
private fun BtSettingsGroup(
    bt: com.dingdinggo.app.ui.BtUiState,
    onToggle: (Boolean) -> Unit,
    onEarlyWindow: (Int) -> Unit,
    onLearn: ((Boolean, String) -> Unit) -> Unit,
    onClearDevice: () -> Unit,
    onResetToday: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var learnState by remember { mutableStateOf<LearnState>(LearnState.Idle) }

    SettingsGroup(title = "蓝牙自动打卡") {
        // ---------- 总开关 ----------
        SettingsRow(
            title = "检测到公司考勤机时自动打卡",
            subtitle = when {
                !bt.enabled -> "关闭时只按设定时间自动打卡"
                bt.present -> buildString {
                    append("当前在考勤机范围内（UUID 0xFE3C")
                    if (bt.deviceMac.isNotBlank()) {
                        append(" · ").append(bt.deviceMac)
                    }
                    append("）")
                }
                bt.deviceName.isNotBlank() -> buildString {
                    append("已学习「").append(bt.deviceName).append("」")
                    if (bt.deviceMac.isNotBlank()) {
                        append("（").append(bt.deviceMac).append("）")
                    }
                    append("，当前不在范围内")
                }
                else -> "已开启，未绑定单台设备（按广播特征 UUID 0xFE3C 匹配）"
            },
            trailing = {
                GlassSwitch(
                    checked = bt.enabled,
                    onCheckedChange = onToggle,
                )
            },
        )

        // 只有在开关打开时才展开下面的细节，避免关着的时候信息噪音
        if (bt.enabled) {
            // ---------- 缺权限时的显式告警 ----------
            //
            // 必须放在最前面：这是"功能看起来开着、实际完全扫不到"
            // 的唯一可观测信号。真机上缺定位权限就是这个表现。
            if (bt.missingPermission != null) {
                SettingsDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "⚠️ " + bt.missingPermission,
                        color = GlassTheme.colors.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "本机的 BLE 扫描需要这条权限才能返回结果。" +
                            "缺少时扫描会显示「已注册」但永远扫不到设备。",
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassTextButton(
                        text = "去授权",
                        onClick = {
                            // 先请求权限（系统弹窗），无论用户是否操作，
                            // 回到前台后都会走 onBtPermissionResult → reregister
                            onRequestPermission()
                        },
                        accent = true,
                    )
                }
            }

            SettingsDivider()

            // ---------- 学习 / 已学设备 ----------
            SettingsRow(
                title = if (bt.deviceName.isNotBlank()) "已学习的考勤机" else "学习当前考勤机",
                subtitle = when (val s = learnState) {
                    is LearnState.Running -> "正在扫描附近设备…（最多 12 秒）"
                    is LearnState.Done -> s.message
                    LearnState.Idle -> if (bt.deviceName.isNotBlank()) {
                        "名称：${bt.deviceName} · MAC：${bt.deviceMac} · 特征：UUID 0xFE3C（点此重新学习）"
                    } else {
                        "走到考勤机旁点一下，可记录该机器的 MAC 与名称特征，辅助区分同楼 0xFE3C 设备"
                    }
                },
                value = if (bt.deviceName.isNotBlank() && learnState is LearnState.Idle) {
                    "重新学习"
                } else {
                    null
                },
                onClick = {
                    if (learnState is LearnState.Running) return@SettingsRow
                    learnState = LearnState.Running
                    onLearn { ok, msg ->
                        learnState = LearnState.Done(msg, ok)
                    }
                },
            )

            if (bt.deviceName.isNotBlank()) {
                SettingsDivider()
                SettingsRow(
                    title = "忘记这台考勤机",
                    subtitle = "清除已保存的 MAC 与名称，退回按通用特征（UUID 0xFE3C）匹配",
                    danger = true,
                    onClick = {
                        learnState = LearnState.Idle
                        onClearDevice()
                    },
                )
            }

            SettingsDivider()

            // ---------- 早到窗口 ----------
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "允许提前多久算上班",
                        color = GlassTheme.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = com.dingdinggo.app.core.BtTriggerStore
                            .earlyWindowLabel(bt.earlyWindowMinutes),
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                SegmentedSelector(
                    options = com.dingdinggo.app.core.BtTriggerStore.EARLY_WINDOW_OPTIONS.map {
                        com.dingdinggo.app.core.BtTriggerStore.earlyWindowLabel(it)
                    },
                    // indexOf 在"值不在选项里"时返回 -1。直接传 -1 会让
                    // SegmentedSelector 的选中块算出一个负偏移（画到容器外）。
                    //
                    // 超出选项的情况是真实存在的：老版本可能存过 90 分钟，
                    // 或者用户从别的版本迁移过来。这里钳到最近的档位
                    // （更合理：取最接近的一项，而不是硬塞到第 0 项）。
                    selectedIndex = run {
                        val opts = com.dingdinggo.app.core.BtTriggerStore.EARLY_WINDOW_OPTIONS
                        val exact = opts.indexOf(bt.earlyWindowMinutes)
                        if (exact >= 0) {
                            exact
                        } else {
                            opts.indices.minByOrNull {
                                kotlin.math.abs(opts[it] - bt.earlyWindowMinutes)
                            } ?: 0
                        }
                    },
                    onSelect = { index ->
                        // 防御：索引越界时忽略，不让 UI 回调把 prefs 写坏
                        com.dingdinggo.app.core.BtTriggerStore.EARLY_WINDOW_OPTIONS
                            .getOrNull(index)
                            ?.let(onEarlyWindow)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "只放宽「早到」。超过上班时间 55 秒后的首次触发会弹出确认框与高优先级通知" +
                        "（默认不打迟到卡）；下班时间之前一律不自动打卡 —— 避免产生误卡记录。",
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }

            SettingsDivider()

            // ---------- 今日状态 / 重置 ----------
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "今日状态",
                    color = GlassTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        bt.missingPermission != null -> "缺权限 · 当前扫不到任何设备"
                        bt.scanRegistered && bt.present -> "扫描已注册 · 在场"
                        bt.scanRegistered -> "扫描已注册 · 等待进入范围"
                        !bt.scanInWindow -> "非打卡时间窗 · 省电休眠"
                        else -> "扫描未注册（点下方按钮重试）"
                    },
                    color = when {
                        bt.missingPermission != null -> GlassTheme.colors.error
                        bt.scanRegistered -> GlassTheme.colors.success
                        else -> GlassTheme.colors.textTertiary
                    },
                    fontSize = 11.sp,
                )
                if (bt.lastTriggerText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "上次触发：" + bt.lastTriggerText,
                        color = GlassTheme.colors.textTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextButton(
                        text = "重置今日",
                        onClick = {
                            onResetToday()
                            learnState = LearnState.Idle
                        },
                    )
                    GlassTextButton(
                        text = "授权扫描",
                        onClick = onRequestPermission,
                    )
                    GlassTextButton(
                        text = "蓝牙设置",
                        onClick = {
                            com.dingdinggo.app.core.HealthCheck
                                .openBluetoothSettings(context)
                        },
                    )
                }
            }
        }
    }
}

/** 学习动作的三态 */
private sealed interface LearnState {
    data object Idle : LearnState
    data object Running : LearnState
    data class Done(val message: String, val ok: Boolean) : LearnState
}
