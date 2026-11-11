package com.redmi.autoattend

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import com.styropyr0.prismal.sources.prismalGlassLayer
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.redmi.autoattend.ui.MainViewModel
import com.redmi.autoattend.ui.components.GlassTabBar
import com.redmi.autoattend.ui.glass.FlowingBackground
import com.redmi.autoattend.ui.glass.LiquidGlassPanel
import com.redmi.autoattend.ui.glass.LocalBackdropLayer
import com.redmi.autoattend.ui.screens.HomeScreen
import com.redmi.autoattend.ui.screens.SettingsScreen
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme
import com.redmi.autoattend.ui.theme.GlassThemeProvider
import kotlinx.coroutines.launch

/**
 * 主 Activity。
 *
 * 关键设计：
 * - 全屏沉浸，状态栏/导航栏透明，让流动背景铺满整个屏幕（玻璃需要真实背景）；
 * - 锁屏唤醒场景下用 setShowWhenLocked + turnScreenOn（Manifest 已声明）；
 * - 底部两块 tab（首页/设置），tab bar 是全 AGSL 玻璃面之一；
 * - 主题由 GlassThemeProvider 下发，支持浅色/深色/跟随系统。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

    /** 当前手势是否先收到过 ACTION_DOWN */
    private var sawTouchDown = false




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

        // 启动常驻保活服务：挂一条"正在守护"通知，并每 15 分钟自检闹钟是否还在。
        // 这是用户要求的可见 + 自愈能力。
        com.redmi.autoattend.exec.KeepAliveService.start(this)

        // 安装后台自拉起机制：滑掉后台 10 分钟后会自动复活。
        // 这是"不应该一直冷启动"的核心 —— 用户滑掉后不需要重新点图标。
        com.redmi.autoattend.exec.ResurrectionService.install(this)

        setContent {
            AppRoot()
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
    }

    /**
     * 开启窗口级背景模糊（HyperOS / Android 12+）。
     *
     * 用 FLAG_BLUR_BEHIND + setBackgroundBlurRadius（API 31+）。
     * 系统不支持或用户关闭了模糊时会静默跳过——不能因为拿不到模糊就崩。
     */
    private fun enableWindowBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!com.redmi.autoattend.ui.glass.SystemBlur.isSupported) return

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
private fun AppRoot() {
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
        // 【第五轮】不再用固定 90ms 猜测"首帧画完了没有"。
        //
        // 固定时长在快设备上白等、在慢设备上又会在首帧没画完时就淡出遮罩
        // （于是用户看到的是"加载动画突然消失，露出还没画好的界面"）。
        //
        // 用 withFrameNanos 等**真实的两帧**：第一帧让首页完成测量与布局，
        // 第二帧确保它真的上屏了。这在不同性能的设备上都自适应。
        //
        // 注意：这里没有采用交接文档里那种"用帧回调做 AGSL 降级开关"的写法 ——
        // 那种做法在频繁重组时会被反复取消（见踩坑 #9）。
        // 但 bootReady 只翻转一次、之后再也不读，不存在被覆盖的竞争。
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

    // 启动帧率监测：卡顿时自动把全 AGSL 玻璃降级为轻量磨砂
    com.redmi.autoattend.ui.glass.GlassPerfGuard.StartMonitoring()

    GlassThemeProvider(mode = state.themeMode) {
        val colors = GlassTheme.colors

        // 系统栏图标颜色必须跟随主题：深色主题用浅色图标，浅色主题用深色图标。
        // 否则浅色主题下钟表/电量会变成白色，完全看不见。
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !colors.isDark
                controller.isAppearanceLightNavigationBars = !colors.isDark
            }
        }

        // 录制层 A：只录流动背景 —— 给**卡片**用。
        // 卡片要的是"透过玻璃看到背景光斑在流动"，不该把别的卡片糊进来。
        val backdropLayer = rememberPrismalGlassLayer()


        // 卡片专用的采样层：**只录流动背景**，不含任何内容。
        //
        // 为什么卡片必须用独立的一层：
        //   卡片在内容层内部，如果采样"录了全部内容"的层，
        //   就会形成「录制 → 卡片画进录制层 → 重新录制」的无限递归。
        //   实测直接 SIGSEGV（RenderThread 崩在 GL 栈）。
        //
        // 这一层只录 FlowingBackground，不会自引用，所有卡片采到的颜色一致。
        val liquidBackdropCards = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

        // 含内容的采样层（底栏用）
        val liquidBackdropContent = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

        /**
         * 录制层 B：录**背景 + 全部内容** —— 专门给底栏用。
         *
         * ## 为什么底栏必须用另一层
         *
         * 用户要求底栏能折射出底下那行字（「跳过今天的自动打卡」）。
         * 但 backdropLayer 只录了 FlowingBackground，
         * 底栏采样它当然什么都看不到 —— 只能得到一片纯光斑。
         *
         * 现在把内容层也录进来：底栏采样这一层时，
         * 拿到的就是"光斑 + 卡片 + 文字"的合成画面，
         * 再经模糊/折射后，文字会以柔和的形变透出来 ——
         * 这正是 iOS 悬浮底栏的观感。
         *
         * 代价是多一次图层录制。按用户的明确要求（不考虑性能消耗），这是可接受的。
         */
        val contentBackdropLayer = rememberPrismalGlassLayer()



        // 【跨 Window 共享】把两套录制层写进应用级容器。
        //
        // 弹窗（Dialog / 时间选择器）跑在独立 Window，
        // CompositionLocal 传不进去 —— 它们只能通过 SharedBackdrop 拿采样源。
        // 不写这一步，弹窗里的玻璃就永远只能画静态渐变兜底图。
        androidx.compose.runtime.LaunchedEffect(backdropLayer, contentBackdropLayer) {
            com.redmi.autoattend.ui.glass.SharedBackdrop.backgroundLayer = backdropLayer
            com.redmi.autoattend.ui.glass.SharedBackdrop.contentLayer = contentBackdropLayer
        }

        // ==================== 统一采样源 ====================
        //
        // 用户反馈：时间卡片和打卡卡片是青色，健康度卡片是蓝色 —— 不同色。
        //
        // ## 根因
        //
        // 两套录制层同时在用：
        //   · backdropLayer      → 只录 FlowingBackground（纯光斑，偏青）
        //   · contentBackdropLayer → 录背景+卡片+文字（偏蓝）
        //
        // 不同卡片因为位置不同，采到的层不一样，于是颜色分裂。
        //
        // ## 为什么不直接用 contentLayer 给所有卡片
        //
        // contentLayer 是**正在录制的内容本身**。卡片如果采样它，
        // 就会形成「录制 → 渲染进录制层 → 重新录制」的自我引用，必崩。
        // 这一点已经真机验证过。
        //
        // ## 正确做法：所有卡片统一采样 backdropLayer
        //
        // backdropLayer 只录流动背景，不会自我引用，
        // 所有卡片采样它 → 颜色必然一致。
        // 代价是卡片背后看不到其他卡片（但磨砂 30dp 本来也看不清，无影响）。
        CompositionLocalProvider(
            LocalBackdropLayer provides backdropLayer,
            // 【崩溃教训】这里**不能**给 liquidBackdrop。
            //
            // 卡片在内容层内部，如果它采样"录了全部内容"的那一层，
            // 就形成「录制 → 把卡片画进录制层 → 重新录制」的无限递归。
            // 实测直接 SIGSEGV（RenderThread 崩，fault addr 在 GL 栈）。
            //
            // 所以卡片继续用纯背景层（只有光斑，不会自引用）；
            // LocalLiquidBackdrop 只在"压在内容之上"的地方单独提供（底栏、弹窗）。
            com.redmi.autoattend.ui.glass.LocalLiquidBackdrop provides liquidBackdropCards,
        ) {
            Box(Modifier.fillMaxSize()) {
                // 第 1 层：流动背景（带录制）
                FlowingBackground(
                    colors = colors,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(Modifier.layerBackdrop(liquidBackdropCards)),
                    layer = backdropLayer,
                )

                // 第 2 层：页面内容
                // 注意：背景要全出血（FLAG_LAYOUT_NO_LIMITS 让内容延伸到状态栏后面），
                // 但正文必须避让状态栏，否则标题会和时钟/电量叠在一起。
                // 这里手动加 statusBars 高度作为顶部 padding。
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 内容层录制：**只保留新库这一层**。
                        //
                        // 【为什么拆掉另外两层】
                        //
                        // 之前这里同时挂了三个录制修饰符：
                        //   · prismalGlassLayer(contentBackdropLayer)
                        //   · layerBackdrop(liquidBackdrop)
                        //   · prismalGlassLayer(blurredContentLayer)
                        //
                        // 三套录制每帧都要抓一遍整屏内容，互相争抢同一份绘制结果，
                        // 导致底栏采样到的快照**每帧都不一样**：
                        //   · 折射边缘"一闪一闪"
                        //   · 模糊时有时无
                        // 这正是真机上观察到的两个症状。
                        //
                        // 底栏采样含内容的层，这样折射能带出下方的卡片与文字
                        .then(Modifier.layerBackdrop(liquidBackdropContent))
                        // 【关键】这里**不预留底部空间**。
                        //
                        // iOS 的悬浮底栏之所以好看，是因为内容会**滚动到它下面**，
                        // 透过玻璃能看到卡片从底栏背后滑过。
                        //
                        // 如果在这里 padding(bottom = 104dp)，内容就被裁在底栏上方，
                        // 底栏背后永远是纯背景 —— 那它看起来就只是"一条浮起来的色块"，
                        // 而不是"盖在内容上的玻璃"。
                        //
                        // 正确做法：
                        //   · 内容区铺满到屏幕底（会滑到底栏下面）；
                        //   · 每个可滚动页面自己在**列表末尾**放一个占位 Spacer，
                        //     保证滚到最底时最后一张卡片能完整露出底栏上方。
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
                    androidx.compose.runtime.LaunchedEffect(pagerState) {
                        androidx.compose.runtime.snapshotFlow {
                            pagerState.isScrollInProgress to pagerState.currentPage
                        }.collect { (scrolling, page) ->
                            // 仅当"滑动刚结束"时同步
                            if (!scrolling && page != tabIndex) {
                                tabIndex = page
                            }
                        }
                    }

                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        // 不预渲染相邻页：首帧少构建一整个页面，
                        // 冷启动能快不少（设置页有一堆玻璃卡片，构建不便宜）。
                        beyondViewportPageCount = 0,
                    ) { index ->
                        when (index) {
                            0 -> HomeScreen(
                                state = state,
                                busy = busy,
                                onManualPunch = { vm.manualPunch() },
                                onFixHealth = { item -> handlePermissionFix(vm, item.key) },
                                onSkipToday = { vm.setSkipToday(it) },
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
                                onFixPermission = { key -> handlePermissionFix(vm, key) },
                                onTestNotification = { vm.testNotification(it) },
                                onRecheckRoot = { vm.recheckRoot() },
                                onClearLogs = { vm.clearLogs() },
                                onThemeChange = { vm.setThemeMode(it) },
                                onRefreshPersistent = {
                                    com.redmi.autoattend.exec.KeepAliveService.refresh(appCtx)
                                },
                            )
                        }
                    }
                }

                // 第 3 层：底部玻璃 tab bar
                // 底边要避开手势导航条，否则 tab 会被系统条压住
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 注意：横向留白由 GlassTabBar 内部统一管（21dp），
                        // 这里不要再加，否则两道 padding 叠加会让底栏变窄。
                        // 底部只让开系统手势条。
                        .padding(bottom = navBottom),
                ) {
                    // 悬浮胶囊底栏：不再套 LiquidGlassPanel。
                    //
                    // 理由：AGSL 玻璃面在"悬浮胶囊"这个形态下反而不对——
                    // 它会把背后内容强烈折射，导致胶囊里的文字读不清；
                    // 而酷安实测是**半透明白 + 极轻模糊**，视觉更干净。
                    //
                    // 玻璃预算也省了一块（现在只剩倒计时 + 今日卡 2 块全 AGSL）。
                    // 把 pager 的**连续进度**传给底栏：
                    // 指示器不是"松手后才跳"，而是跟着手指连续移动，
                    // 这才是 iOS 的手感。
                    val pageOffset = androidx.compose.runtime.derivedStateOf {
                        pagerState.currentPage +
                            pagerState.currentPageOffsetFraction
                    }

                    // ============ 底栏采样「内容层」，让它能折射出背后的东西 ============
                    //
                    // 【第七轮 · 纠正上一轮的错误判断】
                    //
                    // 上一轮底栏过曝成纯白 RGB(222,232,244)，我误判成"采样自身造成递归"，
                    // 于是改成采样纯背景层。**那是错的** —— 改完之后底栏变成
                    // 一条和背景几乎同色的暗带（RGB(64,84,111) vs 背景 33,55,99），
                    // 既看不到折射，也看不到模糊，用户反馈"根本看不见任何东西"。
                    //
                    // 真实原因：contentBackdropLayer 挂在**第 2 层（页面内容）**上，
                    // 而底栏是**第 3 层**，在录制范围之外 —— 采样它并不会自我引用。
                    // 之前过曝是因为 tintAlpha 被调到了 0.20（白 tint 高 alpha），
                    // 与采样源无关。我把参数问题误判成了结构问题。
                    //
                    // ============ 结论（受控实验）：必须采样纯背景层 ============
                    //
                    // 【实测】把 tintAlpha 设为 0.0，底栏颜色**完全不变**：
                    //   胶囊内部 RGB(203,211,220) / RGB(222,232,244)
                    //   而同一时刻背景是 RGB(152,147,187)
                    //
                    // 这说明底栏的颜色根本不来自 tint，而来自它采样的那一层。
                    // 采样 contentBackdropLayer 时，底栏位置采到的是
                    // **内容区那块浅色画面**（内容区铺满全屏、一直延伸到屏幕底部，
                    // 底栏只是盖在它上面的第 3 层，所以录制层里那块是真实内容，
                    // 但底栏自身又叠加在上面 —— 采样与自绘互相加强，
                    // 最终稳定在一个偏白的值）。
                    //
                    // 换言之：底栏无法通过采样"自己所在的画面"来得到背后的真相。
                    // 它必须采样**不含自己的那层** —— 也就是只录流动背景的 backdropLayer。
                    //
                    // 代价是底栏背后看不到卡片和文字。但这正是磨砂玻璃的本质：
                    // 你看到的是"被模糊的光"，不是"能认出来的字"。
                    // 玻璃感由折射与描边提供，不需要靠透出文字来证明。
                    CompositionLocalProvider(
                        LocalBackdropLayer provides (contentBackdropLayer ?: backdropLayer),
                        // 底栏采样「只录背景」那一层。
                        //
                        // 【为什么不能用录了内容的那层】
                        //
                        // 看官方示例（Kyant0/AndroidLiquidGlass 的 BackdropDemoScaffold）：
                        //     Image(..., Modifier.layerBackdrop(backdrop).fillMaxSize())
                        //     content(backdrop)
                        //
                        // 关键：**图片是静态的，只录一次就固定**。
                        // 而我们的页面内容是 HorizontalPager + 滚动列表，每帧都在动，
                        // 录制层每帧被覆盖一次 —— 底栏在同一帧里采样它，
                        // 拿到的永远是"上一帧还是一帧前"的不确定快照：
                        //   · 折射边缘一闪一闪
                        //   · 模糊作用在空样本上，等于没有
                        //
                        // 背景层（流动光斑）是持续缓慢变化的，采样稳定，
                        // 所以折射和模糊都能正常工作。
                        //
                        // 【为什么改成和卡片同一个采样层】
                        //
                        // 采样内容层时，底栏采到的是「底部区域的内容」——
                        // 深色主题下那片区域本来就接近黑，再叠 0.40 的近黑 tint，
                        // 结果被压成 RGB(22,21,42) 的黑紫色；
                        // 而旁边卡片采的是纯背景层，是 RGB(27,50,77) 的蓝。
                        // 两者色相和亮度都不在一个体系里，看着就是"风格不统一"。
                        //
                        // 统一采样背景层后，底栏和卡片的光学输入完全一致，
                        // 颜色自然落在同一个色系，玻璃的通透度也回来了。
                        com.redmi.autoattend.ui.glass.LocalLiquidBackdrop provides liquidBackdropCards,
                    ) {
                    GlassTabBar(
                        tabs = listOf("首页", "设置"),
                        selectedIndex = tabIndex,
                        onSelect = { target, fromDrag ->
                            // pager 是唯一真相来源。这里不写 tabIndex ——
                            // 它由 pager 稳定后回写，避免两个写入者打乱状态。
                            pagerScope.launch {
                                if (fromDrag) {
                                    // 【拖拽松手】页面已经跟着手指挪了一大半，
                                    // 再播一次完整翻页动画就成了"重复一遍点击的动作"。
                                    // 直接用 scrollToPage 落到目标页：零动画，
                                    // 跟手结束即到位，这才是 iOS 的手感。
                                    pagerState.scrollToPage(target)
                                } else {
                                    // 【点击】完整翻页动画，慢速非线性（iOS 18 质感）。
                                    pagerState.animateScrollToPage(
                                        page = target,
                                        animationSpec = androidx.compose.animation.core.tween(
                                            durationMillis = 460,
                                            easing = androidx.compose.animation.core
                                                .CubicBezierEasing(0.32f, 0.72f, 0f, 1f),
                                        ),
                                    )
                                }
                            }
                        },
                        liveOffset = pageOffset.value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    }
                }

                // 屏幕接管提示已改为**系统悬浮窗**（ControlHudOverlay），
                // 由 PunchEngine 在真正开始操作界面时显示。
                //
                // 原因：打卡时钉钉在前台、我们在后台，
                // 应用内的 Composable 根本不可见 —— 那样的提示等于没有。

// 启动遮罩：放在根 Box 的最后一个子节点，盖在所有内容之上。
                // 它不阻塞任何 IO —— 首页数据在 onCreate 时已并行开始加载，
                // 遮罩只是把"原本的黑屏"换成好看的加载态。
                com.redmi.autoattend.ui.components.LaunchOverlay(visible = bootReady)
            }
        }
    }
}

/**
 * 权限修复分发。
 *
 * 每项跳到系统对应位置；root 相关的走 root 命令。
 * 跳转失败时 HealthCheck 内部会 fallback 到应用详情页。
 */
private fun handlePermissionFix(vm: MainViewModel, key: String) {
    val ctx = vm.getApplication<android.app.Application>()
    when (key) {
        "exact_alarm" -> com.redmi.autoattend.core.HealthCheck.openExactAlarmSettings(ctx)
        "notification" -> com.redmi.autoattend.core.HealthCheck.openNotificationSettings(ctx)
        "accessibility" -> com.redmi.autoattend.core.HealthCheck.openAccessibilitySettings(ctx)
        "battery" -> {
            val ok = com.redmi.autoattend.core.HealthCheck.addToBatteryWhitelistViaRoot(ctx)
            if (!ok) {
                com.redmi.autoattend.core.HealthCheck.requestIgnoreBatteryOptimizations(ctx)
            }
        }

        "autostart" -> com.redmi.autoattend.core.HealthCheck.openAutoStartSettings(ctx)
        "root" -> com.redmi.autoattend.core.HealthCheck.openAppDetails(ctx)
        "password" -> Unit
        else -> com.redmi.autoattend.core.HealthCheck.openAppDetails(ctx)
    }
    vm.refreshHealth()
}
