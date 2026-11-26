package com.dingdinggo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.exec.LateConfirmCoordinator
import com.dingdinggo.app.exec.PunchForegroundService
import com.dingdinggo.app.ui.MainViewModel
import com.dingdinggo.app.ui.components.ConfirmDialog
import com.dingdinggo.app.ui.components.GlassTabBar
import com.dingdinggo.app.ui.components.LaunchOverlay
import com.dingdinggo.app.ui.glass.FlowingBackground
import com.dingdinggo.app.ui.glass.GlassModalHostLayer
import com.dingdinggo.app.ui.glass.GlassModalHostState
import com.dingdinggo.app.ui.glass.GlassPerfGuard
import com.dingdinggo.app.ui.glass.LocalGlassModalHost
import com.dingdinggo.app.ui.glass.LocalLiquidBackdrop
import com.dingdinggo.app.ui.glass.SharedBackdrop
import com.dingdinggo.app.ui.screens.HomeScreen
import com.dingdinggo.app.ui.screens.SettingsScreen
import com.dingdinggo.app.ui.theme.GlassTheme
import com.dingdinggo.app.ui.theme.GlassThemeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主 Activity。
 *
 * 关键设计：
 * - 全屏沉浸，状态栏/导航栏透明，让流动极光背景铺满整个屏幕；
 * - 锁屏唤醒场景下用 setShowWhenLocked + turnScreenOn（Manifest 已声明）；
 * - 底部两块 tab（首页/设置），使用 Kyant0/backdrop-android 统一液态玻璃管线；
 * - 主题由 GlassThemeProvider 下发，支持浅色/深色/跟随系统。
 */
class MainActivity : ComponentActivity() {

    /**
     * 首页 ViewModel。
     *
     * 这里用 Activity 的 ViewModelStore 取实例，和下面 AppRoot 里
     * Compose 的 `viewModel()` **拿到的是同一个对象**
     * （LocalViewModelStoreOwner 默认就是宿主 Activity），
     * 所以在这里调 setVisible() 确实作用到首页那个 ticker 上。
     */
    private val vm: MainViewModel by viewModels()

    private var pendingBgFromUserClick = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val bgLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        android.util.Log.i("MainActivity", "后台定位权限申请结果 granted=" + granted)
        vm.refreshBtState()
        vm.refreshHealth()
        if (granted) {
            vm.repairBluetoothScan { }
        }
    }

    private val wifiLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        android.util.Log.i("MainActivity", "Wi-Fi 定位权限申请结果 granted=" + granted)
        vm.refreshBtState()
        vm.refreshHealth()
        if (granted) {
            requestBackgroundLocationStep(userTriggered = true)
        }
    }

    /**
     * 「附近的设备」权限申请器。
     *
     * BLUETOOTH_SCAN 是 Android 12+ 的运行时权限，不申请就扫描不到任何东西。
     * 结果回来后立刻重新同步扫描注册 —— 否则用户刚授权，扫描仍处于
     * "未注册"状态，要等下次冷启动才生效（那是最容易被误判成
     * "功能坏了"的体验）。
     */
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        android.util.Log.i("MainActivity", "蓝牙权限申请结果 granted=" + granted)
        val remaining = com.dingdinggo.app.ble.BtScanner.missingPermissions(this)
        val fromClick = pendingBgFromUserClick
        pendingBgFromUserClick = false
        if (!granted && remaining.isNotEmpty()) {
            val canAskAgain = remaining.any { perm ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            }
            if (!canAskAgain) {
                android.widget.Toast.makeText(
                    this,
                    "权限已被系统拒绝，请在应用权限页手动开启蓝牙与位置权限",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                com.dingdinggo.app.core.HealthCheck.openAppDetails(this)
            }
        }
        vm.onBtPermissionResult(granted)
        if (remaining.isEmpty()) {
            requestBackgroundLocationStep(userTriggered = fromClick)
        }
    }

    /**
     * 【幽灵点击防护】丢弃"没有对应 DOWN 的孤立 UP"。
     *
     * ## 现象
     *
     * 点桌面图标打开/切回应用，刚进界面，
     * 底栏「设置」和首页「跳过今天」就各被触发一次 —— 像有鬼在点。
     *
     * ## 精确原理
     *
     * 一次真实点击在系统里是成对事件，且**必须落在同一个窗口**：
     * ```
     *   t=0ms    ACTION_DOWN  → 送到桌面图标（Launcher 窗口）
     *   t=0ms    系统启动/前置我们的 Activity
     *   t=80ms   ACTION_UP    → Launcher 已不可见，事件被投给**新窗口**
     * ```
     * 结果我们的窗口收到一个**只有 UP、没有 DOWN** 的孤立事件。
     *
     * Compose 的 `clickable` 走 `detectTapGestures`，它的 upOrCancel 分支
     * 在有些路径上并不严格要求先收到 DOWN，于是把这个孤立 UP 当成一次点击，
     * 按图标所在的屏幕坐标（中下部）命中底栏和底部按钮。
     *
     * ## 为什么用"孤立 UP"判定，而不是时间窗口
     *
     * 之前试过 `now < onCreate + 700ms` 的时间窗，有两个问题：
     *   1. **测不准** —— 冷启动慢的时候（本机实测 ~1.9s），
     *      幽灵 UP 可能在时间窗之后才到，被漏掉；
     *   2. **误伤** —— 启动快的时候，用户 700ms 内的合法点击被吃掉。
     *
     * 而"孤立 UP"是**事件本身的属性**，与时间无关：
     *   · 真实点击永远有配套的 DOWN → 不受影响；
     *   · 幽灵点击永远是孤立 UP → 必定被拦。
     *
     * 这是精确判定，不是启发式猜测。
     *
     * ## 为什么必须重写 dispatchTouchEvent
     *
     * 试过 `decorView.setOnTouchListener`，**无效**：
     * ViewGroup.dispatchTouchEvent 先分发给子 View，最后才调自身 onTouch，
     * 子 View 早就把点击处理完了。
     * Activity.dispatchTouchEvent 是整条链路最入口，在这里拦才有效。
     *
     * 不影响无障碍/root 注入：那些用 injectInputEvent 注入成对的 DOWN+UP，
     * 不会被误判为孤立事件。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                sawTouchDown = true
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> {
                val tracked = sawTouchDown
                sawTouchDown = false
                if (!tracked) {
                    // 孤立 UP/CANCEL：DROP。
                    // 只对"切窗口导致的事件迁移"生效——
                    // 真实点击永远成对，不受影响。
                    //
                    // 注意：这不拦自动点击工具（如 GKD）注入的
                    // 完整 DOWN+UP，那种事件从窗口视角看完全合法。
                    // 曾在真机上被 GKD 的"跳过"规则误触过「跳过今天」，
                    // 最终通过在 GKD 侧排除本应用解决。
                    Log.i("GhostGuard", "已丢弃孤立触摸事件 action=" + ev.actionMasked)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 界面是否处于可见态（onStart~onStop）：用于暂停极光背景动画，满足后台零渲染红线 */
    private var isUiVisible by mutableStateOf(true)

    /** 当前手势是否先收到过 ACTION_DOWN */
    private var sawTouchDown = false

    /** 是否已至少暂停离开过一次（从系统设置/权限弹窗回来时立刻刷新健康度） */
    private var hasPausedOnce = false




    override fun onCreate(savedInstanceState: Bundle?) {
        // 【启动速度】不要 setTheme 到透明主题再切回来——那会多一次重启 Activity。
        // 正确做法是让 Window 背景在 Manifest 主题里就设成和首页接近的颜色，
        // 这样从点图标到首帧之间用户看到的是"接近成品"的底色，而不是白/黑屏。
        super.onCreate(savedInstanceState)

        // 用 WindowCompat 做沉浸式，而不是 FLAG_LAYOUT_NO_LIMITS。
        // 后者会让 WindowInsets.statusBars 报告 0，导致内容顶到状态栏下面。
        // setDecorFitsSystemWindows(false) 会正常下发 inset，Compose 端才能正确避让。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // 锁屏场景：允许显示在锁屏之上并点亮屏幕。
        // minSdk 是 31，早已高于 O_MR1(27)，所以直接用新 API。
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // 【系统级实时模糊】让 Window 之后的壁纸也变糊。
        //
        // 这和 AGSL 玻璃面板是**互补**关系，不是二选一：
        // - 系统模糊 → 糊 Window 之外（壁纸、其他 App）；
        // - AGSL 面板 → 糊 Window 之内（我们自己画的流动光斑）+ 边缘折射。
        //
        // 只开 AGSL 时，App 四周偶尔露出的壁纸是清晰的，会显得"玻璃没糊透"。
        // 加一层窗口模糊后整体沉浸感明显更好，而且系统模糊由 SurfaceFlinger
        // 分块缓存，GPU 成本几乎为零（实测你这台 PassBlur 只有 320x42 这类小块）。
        enableWindowBlur();

        requestNotificationPermission()
        requestBtPermissionIfNeeded()
        handleLaunchIntent(intent)

        // 启动常驻保活服务：挂一条"正在守护"通知，并每 15 分钟自检闹钟是否还在。
        // 这是用户要求的可见 + 自愈能力。
        com.dingdinggo.app.exec.KeepAliveService.start(this)

        // 安装后台自拉起机制：滑掉后台 10 分钟后会自动复活。
        // 这是"不应该一直冷启动"的核心 —— 用户滑掉后不需要重新点图标。
        com.dingdinggo.app.exec.ResurrectionService.install(this)

        setContent {
            AppRoot(
                // 设置页的按钮 = 无条件申请（用户主动点击时不看"是否问过"）
                onRequestBtPermission = { forceRequestBtPermissions() },
                onRequestWifiPermission = { requestWifiLocationPermission() },
                backgroundPaused = !isUiVisible,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val openLate = intent?.getBooleanExtra(
            LateConfirmCoordinator.EXTRA_OPEN_LATE_CONFIRM,
            false,
        ) == true
        if (openLate) {
            intent.removeExtra(LateConfirmCoordinator.EXTRA_OPEN_LATE_CONFIRM)
            vm.reloadLateConfirm(forceReopen = true)
        }
    }

    /**
     * 回到前台时复位按下状态。
     *
     * 边界场景：用户按着屏幕时切到后台，系统不会再补发 UP，
     * [sawTouchDown] 会永久停在 true。此后回来后收到的第一个
     * 孤立 UP 就会被误判为合法手势 —— 必须复位。
     */
    override fun onResume() {
        super.onResume()
        sawTouchDown = false
        vm.reloadLateConfirm(forceReopen = false)
        if (hasPausedOnce) {
            vm.refreshBtState()
            vm.refreshHealth()
        }
    }

    override fun onPause() {
        hasPausedOnce = true
        super.onPause()
    }

    /**
     * 【后台零消耗】倒计时 ticker 只跟着"界面是否可见"跑。
     *
     * ## 为什么是 onStart/onStop，而不是 onResume/onPause
     *
     * 用 onResume 的话，本应用弹一个 Dialog（时间选择器、确认框）也会触发
     * onPause，ticker 被停掉又立刻重启 —— 界面明明还在屏幕上，倒计时却
     * 出现不必要的抖动。onStart/onStop 的语义正好是"用户还能不能看到这个界面"。
     *
     * 对打卡场景尤其重要：打卡执行时我们的 Activity 在钉钉背后，
     * 已经 onStop —— 此时停掉 ticker 正是我们要的。
     *
     * ## 停掉之后为什么还能恢复
     *
     * setVisible(true) 会把查库节流时间戳清零，回到前台的第一帧就重新
     * 查一次真实的打卡记录并重算下一次打卡，不会先显示切后台之前的旧数据。
     */
    override fun onStart() {
        super.onStart()
        isUiVisible = true
        vm.setVisible(true)
    }

    override fun onStop() {
        // 先停 ticker 与背景帧循环再走父类流程，避免后台继续调度与渲染
        isUiVisible = false
        vm.setVisible(false)
        super.onStop()
    }

    /**
     * 开启窗口级背景模糊（HyperOS / Android 12+）。
     *
     * 用 FLAG_BLUR_BEHIND + setBackgroundBlurRadius（API 31+）。
     * 系统不支持或用户关闭了模糊时会静默跳过——不能因为拿不到模糊就崩。
     */
    private fun enableWindowBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!com.dingdinggo.app.ui.glass.SystemBlur.isSupported) return

        runCatching {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // 28px 约等于 HyperOS 控制中心那档模糊强度，观感自然不糊成一片
            window.attributes = window.attributes.apply {
                blurBehindRadius = 28
            }
            android.util.Log.i("MainActivity", "window blur enabled")
        }.onFailure {
            android.util.Log.w("MainActivity", "window blur failed: " + it.javaClass.simpleName)
        }
    }

    /**
     * 申请通知权限 —— **每个应用生命周期只主动弹一次**。
     *
     * ## 之前为什么会反复弹
     *
     * 原实现只有"当前有没有权限"这一个判断，每次 onCreate 发现没有就弹。
     * 但它没有区分两种情况：
     *
     * 1. **从未询问过** —— 该弹；
     * 2. **用户已经明确拒绝** —— 再弹是骚扰，Android 也会直接静默返回，
     *    用户看到的就是"怎么每次开都弹，点了又没用"。
     *
     * ## 现在的策略
     *
     * - 用 [shouldShowRequestPermissionRationale] 区分上面两种状态：
     *   它为 true 说明用户拒绝过（系统仍允许再问），为 false 且没权限
     *   说明是首次（或用户勾了"不再询问"）；
     * - 只有在**首次**或**用户还没决定**时才主动弹；
     * - 用户拒绝过之后不再打扰，改为在设置页给一个"去开启通知"的入口，
     *   由用户主动触发。
     *
     * 这样自动打卡的通知仍然能到（用户开了就有），但不会每次启动都糊一脸弹窗。
     */
    /**
     * 申请蓝牙扫描权限。
     *
     * 只在"蓝牙触发已开启 + 还没有权限"时才申请 —— 用户没开这个功能，
     * 我们不该一启动就弹一个他没要求的权限框。
     *
     * 申请哪几条：
     * · API 31+：BLUETOOTH_SCAN（必需）。同时带上 BLUETOOTH_CONNECT，
     *   因为读取设备名 / 已配对列表在某些路径上需要它；
     * · API ≤ 30：ACCESS_FINE_LOCATION（老模型下扫描必须）。
     *
     * 注意这里**不**申请 ACCESS_BACKGROUND_LOCATION：本应用不在后台
     * 读位置，只读蓝牙广播。
     */
    fun requestBtPermissionIfNeeded() {
        // 【必须先同步加载配置】
        //
        // BtTriggerStore 的 StateFlow 通常在 MainViewModel.init 的
        // IO 协程里装载，而请求权限发生在 setContent{} 之前 ——
        // 此时 enabled 往往还是初始值 false，导致"用户早已开启该功能、
        // 却永远等不到权限弹窗"。
        //
        // 这里同步初始化一次（幂等，只读 prefs），保证判断依据是真实的。
        runCatching { com.dingdinggo.app.core.BtTriggerStore.init(this) }
        if (!com.dingdinggo.app.core.BtTriggerStore.enabled.value) return

        // 【真机踩坑，2026-11-13】定位权限必须一起申请。
        //
        // K90 / HyperOS 上实测：只给 BLUETOOTH_SCAN、不给定位权限时，
        // startScan() 注册成功、扫描在跑，但结果**永远为 0**
        // （dumpsys 里连续几帧都是 "Filter 0 results"）。
        // 补上定位权限后同一扫描立刻出结果。
        //
        // 本应用不用扫码结果推导位置（不做虚拟定位），但平台不给我们
        // 选择权 —— 这条权限是"能不能扫到"的硬门槛，不是可选项。
        // 缺失时必须主动申请，否则功能会以"看起来正常、实际失效"的
        // 方式静默不工作。
        val needed = com.dingdinggo.app.ble.BtScanner.missingPermissions(this)
        if (needed.isEmpty()) {
            requestBackgroundLocationStep(userTriggered = false)
            return
        }

        // 已经明确拒绝过的权限不再自动弹窗打扰（与通知权限同一策略），
        // 由设置页的「授权扫描」按钮显式触发。
        val permanentlyDenied = needed.any { perm ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
        }
        android.util.Log.i(
            "MainActivity",
            "申请蓝牙权限 " + needed + " (曾拒绝=" + permanentlyDenied + ")",
        )

        pendingBgFromUserClick = false
        runCatching { btPermissionLauncher.launch(needed.toTypedArray()) }
            .onFailure { android.util.Log.w("MainActivity", "申请蓝牙权限失败: " + it.javaClass.simpleName) }
    }

    /**
     * 设置页「授权扫描」按钮的入口：**无条件**申请。
     *
     * 与冷启动的自动申请区分开：用户主动点了按钮，哪怕系统认为
     * "已经问过"，也要再弹一次（用户可能刚在系统设置里撤销了）。
     */
    fun forceRequestBtPermissions() {
        val needed = com.dingdinggo.app.ble.BtScanner.missingPermissions(this)
        if (needed.isEmpty()) {
            if (com.dingdinggo.app.ble.BtScanner.needsBackgroundLocation(this)) {
                requestBackgroundLocationStep(userTriggered = true)
                return
            }
            android.util.Log.i("MainActivity", "蓝牙权限已齐全，检查蓝牙状态并尝试重注册扫描")
            if (!com.dingdinggo.app.ble.BtScanner.isBluetoothOn(this) ||
                com.dingdinggo.app.ble.BtScanner.blocker(this) != null
            ) {
                com.dingdinggo.app.core.HealthCheck.openBluetoothSettings(this)
                return
            }
            vm.repairBluetoothScan { ok ->
                android.widget.Toast.makeText(
                    this,
                    if (ok) "蓝牙权限已就绪，扫描已重新注册" else "蓝牙扫描注册失败，请检查蓝牙或系统权限",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        pendingBgFromUserClick = true
        runCatching { btPermissionLauncher.launch(needed.toTypedArray()) }
            .onFailure {
                pendingBgFromUserClick = false
                android.util.Log.w("MainActivity", "强制申请蓝牙权限失败: " + it.javaClass.simpleName)
            }
    }

    /**
     * Android 10+ 后台定位权限分步申请：先尝试 Root 静默授权，失败再由用户授权。
     * 绝不把 ACCESS_BACKGROUND_LOCATION 混在首次前后台权限请求组里，防止系统静默吞掉弹窗。
     */
    private fun requestBackgroundLocationStep(userTriggered: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!com.dingdinggo.app.ble.BtScanner.needsBackgroundLocation(this)) return

        vm.tryGrantBackgroundLocationViaRoot { grantedByRoot ->
            if (grantedByRoot) {
                vm.refreshBtState()
                vm.refreshHealth()
                if (userTriggered) {
                    vm.repairBluetoothScan { ok ->
                        android.widget.Toast.makeText(
                            this,
                            if (ok) "已通过 Root 授予始终允许定位，扫描已重新注册"
                            else "已通过 Root 授予后台定位，但扫描注册失败",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                return@tryGrantBackgroundLocationViaRoot
            }

            // Android 11+ 调用 ACCESS_BACKGROUND_LOCATION 会直接跳转系统位置设置页，
            // 冷启动静默检查时不打扰用户，由用户点击「去授权」或开启开关时主动引导。
            if (!userTriggered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.i("MainActivity", "后台定位未授权（始终允许），等待用户主动点击授权")
                return@tryGrantBackgroundLocationViaRoot
            }

            runCatching {
                bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }.onFailure {
                if (userTriggered) {
                    com.dingdinggo.app.core.HealthCheck.openAppDetails(this)
                }
            }
        }
    }

    fun requestWifiLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            runCatching {
                wifiLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }.onFailure {
                com.dingdinggo.app.core.HealthCheck.openAppDetails(this)
            }
            return
        }
        if (com.dingdinggo.app.ble.BtScanner.needsBackgroundLocation(this)) {
            requestBackgroundLocationStep(userTriggered = true)
            return
        }
        android.widget.Toast.makeText(
            this,
            "Wi-Fi 所需定位权限已就绪",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        // 用户此前明确拒绝过 → 不再自动弹，交给设置页的手动入口
        val rejectedBefore = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (rejectedBefore) {
            android.util.Log.i("MainActivity", "通知权限曾被拒绝，不再自动申请")
            return
        }

        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * App 根组件。
 *
 * 层次（自下而上）：
 *   1. FlowingBackground —— 全屏流动光斑，是所有玻璃的"被采样内容"
 *   2. 页面内容（首页 / 设置）
 *   3. 底部玻璃 tab bar
 */
@Composable
private fun AppRoot(
    /** 请求蓝牙扫描权限（运行时申请必须由 Activity 发起） */
    onRequestBtPermission: () -> Unit = {},
    /** 请求 Wi-Fi 所需定位权限（运行时申请必须由 Activity 发起） */
    onRequestWifiPermission: () -> Unit = {},
    backgroundPaused: Boolean = false,
) {
    val vm: MainViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext

    val busy by vm.busy.collectAsStateWithLifecycle()

    /**
     * 启动遮罩是否还存在。
     *
     * 逻辑：进入 Compose 后先等 ~90ms（让首页完成第一次测量和布局），
     * 然后置 true 触发淡出动画。
     *
     * 这个 90ms 是**并行等待**，不是串行延迟：
     * 首页的 ViewModel 数据在 onCreate 时就开始加载了，
     * 遮罩只是在等"渲染管线准备好"，不阻塞任何 IO。
     */
    var bootReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 不再使用固定延时猜测首帧是否绘制完成，
        // 用 withFrameNanos 等待真实两帧上屏。
        // bootReady 只翻转一次、之后再也不读，不存在被覆盖的竞争。
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        bootReady = true
    }

    var tabIndex by remember { mutableIntStateOf(0) }

    // pager 状态提到 AppRoot 作用域：
    // 内容区要靠它翻页，底栏指示器也要跟着它实时移动。
    // 放在下面的 Box 里的话，底栏就拿不到滑动进度了。
    // rememberPagerState 内部用 rememberSaveable，
    // 所以**进程被系统回收后重建**时会恢复上次的页 —— 这是合理的。
    //
    // 但用户点图标冷启动时期望看到首页，而不是上次停留的页。
    // 这里不做强制重置（那会破坏系统回收后的正常恢复），
    // 因为 force-stop 后进程是全新的，rememberSaveable 自然从 0 开始。
    //
    // 唯一例外：Activity 被系统以 savedInstanceState 重建时会恢复，
    // 这是正确的系统行为，不应干预。
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { 2 },
    )

    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()

    /**
     * 「锁屏密码」健康项「修复」用的自增信号。
     *
     * 【审计 UI#3】原先 handlePermissionFix 里 `"password" -> Unit`，
     * 点「修复」什么都不发生（静默失败）。现在改成一个信号：
     * 点一下就切到设置页并展开密码编辑器。
     */
    var focusPasswordSignal by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    var focusScheduleSignal by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    var focusWifiSignal by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }

    /** 切到设置页（tab index 1），供健康项跳转复用 */
    val gotoSettings: () -> Unit = {
        pagerScope.launch { runCatching { pagerState.animateScrollToPage(1) } }
    }

    // 切换 tab 时刷新健康度。
    //
    // 【启动性能】必须跳过**首次组合**触发的那一次。
    //
    // LaunchedEffect 在进入组合时就会跑一次，于是冷启动时这里会立刻调用
    // refreshHealth() → HealthCheck.check() → RootShell.isAvailable()
    // → fork 一个 su 进程（实测 300~400ms 主线程阻塞）。
    // 即使 ViewModel 的 init 已经把首轮检查推迟了，这里又会把它提前触发。
    //
    // 首次的健康度检查交给 ViewModel init 里那条延迟任务，这里只管"用户切页"。
    var firstTabEffect by remember { mutableStateOf(true) }
    LaunchedEffect(tabIndex) {
        if (firstTabEffect) {
            firstTabEffect = false
            return@LaunchedEffect
        }
        vm.refreshHealth()
    }

    // 启动帧率监测：卡顿时自动把玻璃面板降级为轻量磨砂
    GlassPerfGuard.StartMonitoring()

    GlassThemeProvider(mode = state.themeMode) {
        val colors = GlassTheme.colors

        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !colors.isDark
                controller.isAppearanceLightNavigationBars = !colors.isDark
            }
        }

        // 纯极光背景采样层：供给页面内部玻璃卡片，避免自采样递归
        val bgBackdrop = rememberLayerBackdrop {
            drawRect(colors.backgroundDeep)
            drawContent()
        }
        // 页面内容录制层：供悬浮在内容之上的底栏采样
        val contentBackdrop = rememberLayerBackdrop {
            drawContent()
        }
        // 完整屏幕（背景 + 内容 + 底栏）录制层：供全屏弹窗背板（含状态栏）与弹窗液态玻璃面板采样
        val modalSceneBackdrop = rememberLayerBackdrop {
            drawContent()
        }
        val combinedBackdrop = rememberCombinedBackdrop(bgBackdrop, contentBackdrop)
        val modalHostState = remember { GlassModalHostState() }

        LaunchedEffect(combinedBackdrop) {
            SharedBackdrop.sceneBackdrop = combinedBackdrop
        }

        CompositionLocalProvider(
            LocalLiquidBackdrop provides bgBackdrop,
            LocalGlassModalHost provides modalHostState,
        ) {
            Box(Modifier.fillMaxSize()) {
                // 完整主视图场景（背景 + 内容 + 悬浮底栏），由 modalSceneBackdrop 统一录制
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(modalSceneBackdrop),
                ) {
                    // 第 1 层：流动极光背景（退后台 onStop 后停止步进，满足后台零渲染红线）
                    FlowingBackground(
                        colors = colors,
                        paused = backgroundPaused,
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(bgBackdrop),
                    )

                    // 第 2 层：页面内容（延伸到底栏背后，实现滚过悬浮底栏的透明折射感）
                    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(contentBackdrop)
                            .padding(top = statusBarTop),
                    ) {
                    // 【页面切换】用 HorizontalPager 而不是 AnimatedContent。
                    //
                    // AnimatedContent 只支持"点了才切"，无法跟手；
                    // 而 iOS 的 tab 页是**左右滑动可切换**的，手指拖到一半松手
                    // 会根据位置和速度决定翻页还是回弹。
                    //
                    // HorizontalPager 原生支持这些行为，且动画与指示器可以共用
                    // 同一个 pagerState.currentPage，避免状态不同步。
                    // 【单向数据流】
                    //
                    // 之前是双向同步：tabIndex -> pager、pager -> tabIndex。
                    // 这两条 LaunchedEffect 会互相触发，而且 pager 首帧的内部状态
                    // 可能短暂落在别的页，导致启动时 tabIndex 被错误改写 ——
                    // 实测启动 655ms 后 tabIndex 从 0 变成 1，界面停在设置页。
                    //
                    // 现在只保留**一个**真相来源 pagerState：
                    //   · 底栏点击 -> 直接 animateScrollToPage（下面 onSelect 里）
                    //   · pager 稳定后 -> 回写 tabIndex（仅用于高亮）
                    // tabIndex 不再反向驱动 pager，杜绝回环。

                    // 【不做双向同步】
                    //
                    // 之前的做法是 snapshotFlow { settledPage }.collect { tabIndex = it }，
                    // 意图是"滑到哪页就把底栏高亮同步到哪页"。
                    //
                    // 实测有问题：Compose Pager 在某些布局下，首帧的 settledPage
                    // 会短暂报告成 1（可以复现：启动 655ms 后 tabIndex 从 0 变 1），
                    // 导致每次冷启动都停在设置页。
                    //
                    // 现在改为：**用户的滑动本身**就是唯一信号，
                    // 在 HorizontalPager 的 onPageChanged 里回写 tabIndex。
                    // 这样只有真实翻页才会改状态，首帧不会误触发。
                    // 只有**用户拖动结束后**才回写 tabIndex。
                    //
                    // isScrollInProgress 为 true 表示正在滑（手指按着或惯性未停），
                    // 为 false 且发生过滑动，才是"真的翻页了"。
                    // 首帧不会进这个分支，杜绝启动误同步。
                    // ============ tabIndex 回写时机（修「0.5 秒点不动」）============
                    //
                    // ## 旧实现的问题
                    //
                    // 只在 `!isScrollInProgress`（动画完全停稳）时回写 tabIndex。
                    // 而点击后的翻页动画是 620ms —— 这 620ms 里 tabIndex 一直
                    // 是**旧值**，底栏拿它判断"目标页是不是当前页"：
                    //
                    //     用户点 B（动画开始）→ 想立刻点回 A
                    //     → tabIndex 还是 A → target(A) == currentSelected(A)
                    //     → 走进 else 分支，什么都不做 → **点击被静默吞掉**
                    //
                    // 这就是用户说的「刚切换界面 0.5 秒内点击/拖拽底栏无效」。
                    //
                    // ## 现在
                    //
                    // 翻页**意图**一确定就回写：动画进行中 currentPage 已经等于
                    // 目标页（pager 在动画期把 currentPage 设为最近的目标），
                    // 所以一旦它的值变了就同步，不必等停稳。
                    //
                    // 同时保留"停稳后再对齐一次"的兜底，处理用户中途反悔
                    // （动画被反向打断、currentPage 又变回去）的情况。
                    //
                    // 首帧保护不变：只在发生过真实翻页后才写，避免启动误同步。
                    androidx.compose.runtime.LaunchedEffect(pagerState) {
                        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
                            .collect { page ->
                                if (page != tabIndex) {
                                    tabIndex = page
                                }
                            }
                    }

                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        // ============ 预渲染相邻页（修「点击卡顿」的关键） ============
                        //
                        // 之前是 0，理由是「首帧少构建一整个页面，冷启动快」。
                        // 用 atrace 实测后，这个权衡算错了方向：
                        //
                        //     冷进程首次 首页->设置 : measureAndLayout 147.4ms
                        //     同进程第 2 次          : 4.5ms
                        //     同进程第 3 次          : 1.7ms
                        //     反向 设置->首页        : 16ms
                        //
                        // 147ms 里没有一行是玻璃渲染 —— 全是
                        // Compose:recompose 39ms + applyChanges 33ms
                        // + 74 个 Text 的测量 28ms。
                        //
                        // 更关键：这段组合跑在 AndroidOwner:onTouch 里，
                        // 即输入事件分发路径上**同步执行**。
                        // Choreographer 的下一帧回调排不进去 ——
                        // 真机日志抓到动画前两帧间隔 154ms，指示器直接蹦到中途。
                        //
                        // 改成 1 后设置页在启动时就组合完毕，
                        // 那 147ms 被 LaunchOverlay 遮住。
                        // 代价：冷启动 +150ms、设置页节点树常驻（Java Heap 约 +2MB）。
                        beyondViewportPageCount = 1,
                    ) { index ->
                        when (index) {
                            0 -> HomeScreen(
                                state = state,
                                busy = busy,
                                onManualPunch = { allowLate -> vm.manualPunch(allowLate) },
                                onFixHealth = { item ->
                                    handlePermissionFix(
                                        vm = vm,
                                        key = item.key,
                                        onNeedPasswordEditor = {
                                            focusPasswordSignal++
                                            gotoSettings()
                                        },
                                        onNeedBtPermission = onRequestBtPermission,
                                        onNeedWifiPermission = onRequestWifiPermission,
                                        onNeedWifiSettings = {
                                            focusWifiSignal++
                                            gotoSettings()
                                        },
                                        onNeedScheduleSettings = {
                                            focusScheduleSignal++
                                            gotoSettings()
                                        },
                                    )
                                },
                                onSkipToday = { vm.setSkipToday(it) },
                                onRequestBtPermission = onRequestBtPermission,
                                onRequestWifiPermission = onRequestWifiPermission,
                                backgroundPaused = backgroundPaused,
                            )

                            else -> SettingsScreen(
                                state = state,
                                logs = logs,
                                onScheduleChange = { inMin, outMin, days ->
                                    vm.updateSchedule(inMin, outMin, days)
                                },
                                onToggleEnabled = { vm.setEnabled(it) },
                                onChannelChange = { vm.setChannel(it) },
                                onSavePassword = { pwd -> vm.savePassword(pwd) },
                                onClearPassword = { vm.clearPassword() },
                                onFixPermission = { key ->
                                    handlePermissionFix(
                                        vm = vm,
                                        key = key,
                                        onNeedPasswordEditor = {
                                            focusPasswordSignal++
                                        },
                                        onNeedBtPermission = onRequestBtPermission,
                                        onNeedWifiPermission = onRequestWifiPermission,
                                        onNeedWifiSettings = {
                                            focusWifiSignal++
                                        },
                                        onNeedScheduleSettings = {
                                            focusScheduleSignal++
                                        },
                                    )
                                },
                                onRecheckRoot = { vm.recheckRoot() },
                                onClearLogs = { vm.clearLogs() },
                                onThemeChange = { vm.setThemeMode(it) },
                                onRefreshPersistent = {
                                    com.dingdinggo.app.exec.KeepAliveService.refresh(appCtx)
                                },
                                // ---- 蓝牙自动打卡 ----
                                onBtToggle = { enabled ->
                                    vm.setBtEnabled(enabled)
                                    // 打开时立刻申请权限：没权限扫描注册不上，
                                    // 让用户在同一个动作里就把权限给掉
                                    if (enabled) onRequestBtPermission()
                                },
                                onRequestBtPermission = onRequestBtPermission,
                                onBtEarlyWindow = { vm.setBtEarlyWindow(it) },
                                onBtLearn = { cb -> vm.learnBtDevice(cb) },
                                onBtClearDevice = { vm.clearBtDevice() },
                                onBtResetToday = { vm.resetBtToday() },
                                // ---- 公司 Wi-Fi 自动打卡 ----
                                onWifiToggle = { enabled ->
                                    vm.setWifiEnabled(enabled)
                                },
                                onRequestWifiPermission = onRequestWifiPermission,
                                onWifiLearn = { cb -> vm.learnWifiNetwork(cb) },
                                onWifiClear = { vm.clearWifiNetwork() },
                                focusPasswordSignal = focusPasswordSignal,
                                focusScheduleSignal = focusScheduleSignal,
                                focusWifiSignal = focusWifiSignal,
                            )
                        }
                    }
                }

                // 第 3 层：悬浮液态玻璃底栏（避开系统导航条）
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = navBottom),
                ) {
                    CompositionLocalProvider(
                        LocalLiquidBackdrop provides combinedBackdrop,
                    ) {
                        GlassTabBar(
                            tabs = listOf("首页", "设置"),
                            selectedIndex = tabIndex,
                            onSelect = { target, _ ->
                                pagerScope.launch {
                                    runCatching { pagerState.scrollToPage(target) }
                                }
                            },
                            liveOffsetProvider = {
                                pagerState.currentPage + pagerState.currentPageOffsetFraction
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                }

                val pendingLate = state.pendingLateConfirm
                if (pendingLate != null) {
                    var remainingSeconds by remember(pendingLate.promptedAt) {
                        mutableIntStateOf(
                            (com.dingdinggo.app.core.LateConfirmRule.OVERLAY_AUTO_DISMISS_MS / 1000L).toInt(),
                        )
                    }
                    LaunchedEffect(pendingLate.promptedAt) {
                        while (remainingSeconds > 0) {
                            delay(1_000L)
                            remainingSeconds--
                        }
                        // 超时默认不打迟到卡，仅收起弹窗并保留通知操作入口
                        vm.dismissPendingLatePromptLocally()
                    }
                    val targetText = PunchSchedule.formatTime(pendingLate.plannedTargetAt)
                    val lateMinutes = (pendingLate.lateSeconds / 60L).coerceAtLeast(1L)
                    val sourceLabel = when (pendingLate.source) {
                        PunchForegroundService.SOURCE_WIFI -> "公司 Wi-Fi"
                        PunchForegroundService.SOURCE_BLUETOOTH -> "考勤机蓝牙"
                        else -> "定时闹钟"
                    }
                    ConfirmDialog(
                        title = "是否打迟到上班卡？",
                        message = "已由「$sourceLabel」首次触发，当前比预定上班时间（$targetText）晚约 ${lateMinutes} 分钟（已迟到 ${pendingLate.lateSeconds} 秒）。\n\n确认后将立即解锁并打开钉钉打卡；${remainingSeconds} 秒后自动关闭（默认不打卡）。",
                        confirmText = "确认打迟到卡",
                        dismissText = "今天不打",
                        danger = true,
                        onConfirm = { vm.confirmPendingLatePunch() },
                        onDismiss = { vm.declinePendingLatePunch() },
                    )
                }

                // 第 4 层：窗口内全屏液态玻璃弹窗宿主（含状态栏实时模糊背板与真实光学折射）
                CompositionLocalProvider(
                    LocalLiquidBackdrop provides modalSceneBackdrop,
                ) {
                    GlassModalHostLayer(modalHostState)
                }

                // 启动遮罩：放在根 Box 的最后一个子节点，盖在所有内容之上。
                LaunchOverlay(visible = bootReady)
            }
        }
    }
}

/**
 * 权限与健康项修复分发。
 */
private fun handlePermissionFix(
    vm: MainViewModel,
    key: String,
    onNeedPasswordEditor: () -> Unit = {},
    onNeedBtPermission: () -> Unit = {},
    onNeedWifiPermission: () -> Unit = {},
    onNeedWifiSettings: () -> Unit = {},
    onNeedScheduleSettings: () -> Unit = {},
) {
    val ctx = vm.getApplication<android.app.Application>()
    when (key) {
        "exact_alarm" -> com.dingdinggo.app.core.HealthCheck.openExactAlarmSettings(ctx)
        "notification" -> com.dingdinggo.app.core.HealthCheck.openNotificationSettings(ctx)
        "accessibility" -> com.dingdinggo.app.core.HealthCheck.openAccessibilitySettings(ctx)
        "battery" -> {
            vm.repairBatteryWhitelist { rootApplied ->
                if (rootApplied) {
                    android.widget.Toast.makeText(
                        ctx,
                        "已通过 Root 加入电池优化白名单",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    com.dingdinggo.app.core.HealthCheck.requestIgnoreBatteryOptimizations(ctx)
                }
            }
            return
        }

        "autostart" -> com.dingdinggo.app.core.HealthCheck.openAutoStartSettings(ctx)
        "root" -> com.dingdinggo.app.core.HealthCheck.openAppDetails(ctx)
        "password" -> onNeedPasswordEditor()
        "bluetooth" -> {
            onNeedBtPermission()
            return
        }
        "wifi_trigger" -> {
            val rootOk = vm.ui.value.health?.items?.any { it.key == "root" && it.ok } == true
            val wifiEnabled = runCatching {
                (ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager)?.isWifiEnabled == true
            }.getOrDefault(false)
            if (!wifiEnabled) {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return
            }
            val hasFine = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val needsBg = com.dingdinggo.app.ble.BtScanner.needsBackgroundLocation(ctx)
            if ((!hasFine || needsBg) && !rootOk) {
                onNeedWifiPermission()
                return
            }
            onNeedWifiSettings()
        }
        "schedule_order" -> onNeedScheduleSettings()
        "alarm_registered" -> {
            vm.repairAlarm { scheduled ->
                if (!scheduled) {
                    android.widget.Toast.makeText(
                        ctx,
                        "当前暂无可注册的打卡闹钟，请检查自动打卡开关与生效日",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }
        "overlay" -> com.dingdinggo.app.core.HealthCheck.openOverlaySettings(ctx)
        else -> com.dingdinggo.app.core.HealthCheck.openAppDetails(ctx)
    }
    vm.refreshHealth()
}
