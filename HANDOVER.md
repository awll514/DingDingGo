# 钉钉GO · 开发与维护交接文档（v1.0.0）

> 最后更新：2026-11-16 · 目标设备：Redmi K90 至尊版（HyperOS 3 / Android 16 / API 36）  
> 仓库：`https://github.com/awll514/DingDingGo.git` · 分支：`master` · 包名：`com.dingdinggo.app` · 版本：`1.0.0`（`versionCode = 1`）

---

## 一、v1.0.0 正式版核心状态

1. **包名与构建配置**
   - `namespace` 与 `applicationId` 统一为 `com.dingdinggo.app`，已移除 `debug` 构建的 `.debug` 后缀。
   - 签名配置：如果项目根目录存在本地 `keystore.properties`（已被 `.gitignore` 忽略，不提交仓库），`debug` 与 `release` 构建类型会自动共用其中的 `release` 签名；多台电脑同步测试时，只需把本地签名密钥库与 `keystore.properties` 复制到另一台电脑根目录即可实现免卸载无缝覆盖安装。若缺失 `keystore.properties`，`debug` 构建仍回退使用默认本地调试签名，确保开源/克隆可直接编译。
   - 源码目录：`app/src/main/java/com/dingdinggo/app/`、单测目录：`app/src/test/java/com/dingdinggo/app/`、Room Schema：`app/schemas/com.dingdinggo.app.data.db.AppDatabase/`。

2. **已完成的核心链路与近期关键修复**
   - **双通道打卡与锁屏解锁可靠性加固**：
     - `RootShell.launchPackage` 已移除 `monkey` 启动方式（实测 `monkey` 会向系统注入 `MonkeyRotationEven#injectEvent`，意外关闭系统「方向锁定」），改用 `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER` 解析真实入口 Activity 后由 `am start -n` 拉起。
     - `PunchEngine.unlock` 与 `RootShell.dismissKeyguard`：优先调用 `wm dismiss-keyguard` 直接唤起 PIN 面板（避免锁屏通知堆积、杂志锁屏或唤醒过渡吞掉上滑触摸手势），备用上滑坐标调至底部安全区 `(300, 2350 -> 300, 1050)`；仅在失败重试（`attempt > 0`）时执行 `clearPinField(password.length)`（规避首轮 12 次退格键消耗 ~5.25 秒撞上 HyperOS 锁屏 10 秒超时）；每轮尝试前校验亮屏并即时重唤醒；`RootShell.inputText` 排空子进程输出防阻塞，`isAvailable()` 的 `false` 缓存 30 秒自动过期重探。
     - `AccessibilityKeeper.ensureEnabled` 在 `settings get` 返回空或超时时增加了 `Settings.Secure.getString` 兜底检查，避免误覆写其它已开启的无障碍服务。
   - **钉钉娘双窗口悬浮控制条（`ControlHudOverlay.kt`）**：
     - 拆分为 `mascotHost` 与 `bubbleHost` 两个协调同步位置的 `WindowManager` 窗口，解决气泡展开/收起时悬浮像素精灵坐标跳变（瞬移）的问题。
     - 文案读取配置中的实际 `clockInMinute`（经 `ControlHudCopyFormatter` 格式化），展示动态上班时间与二次元气泡状态；提供暂停/继续（`II` / `▶`）与取消（`X`）操作，贴边吸附时自动反向展开气泡。
   - **系统原生通知体系（`PunchNotifier.kt`）**：
     - 成功/失败/阻塞通知统一采用 Android / HyperOS 原生 `Notification.BigTextStyle` 样式（移除了自定义深色卡片 `RemoteViews`），保留失败通知的「立即重试」`PendingIntent` 与代码级 `Vibrator` + `ToneGenerator` 双重反馈。
     - 已清理废弃的 `notifyTest` 方法与旧通知 XML 布局，`MainViewModel` 启动前台服务异常兜底改调真实的 `PunchNotifier.notifyStartBlocked`。
   - **液态玻璃管线与平面化 UI 优化**：
     - 基于 `io.github.kyant0:backdrop-android:2.0.1` 的三层管线：`bgBackdrop`（仅录制 `FlowingBackground`）→ `contentBackdrop`（主内容层）→ `combinedBackdrop`（供悬浮胶囊底栏采样并写入 `SharedBackdrop.sceneBackdrop`）。
     - 首页「最近失败」提示条、「立即手动打卡」按钮及状态按钮统一平面液态玻璃质感，倒计时/提前时长超过 60 分钟自动格式化为 `X 小时 Y 分 Z 秒`（`PunchSchedule.formatRemainSeconds` / `normalizeDurationDisplay`）。
     - 全局按钮统一点击即时震动（`View.performClickHaptic()` / `HapticFeedbackConstants.KEYBOARD_TAP` 在 `onClick` 直接触发，而非长按才触发）。
   - **同窗全屏玻璃弹层宿主（`GlassModalHost.kt`）**：
     - `ConfirmDialog` 与 `TimeEditorSheet` 优先挂载到 `MainActivity` 根布局最顶层宿主 `GlassModalHostLayer`，背景模糊直达状态栏与导航栏边缘，避免弹窗外状态栏出现清晰割裂带；独立 `Dialog` 降级路径保留 `DialogSystemBlur`（以 `lp.packageName == ctx.packageName` 精准识别本应用窗口）。
   - **循环防串扰时间滚轮（`GlassWheelTimePicker.kt` / `TimeEditorSheet.kt`）**：
     - `WheelColumn` 使用 `WHEEL_LOOP_CYCLES = 2_000` 虚拟循环列表实现 24 小时（`23 ↔ 00`）与 60 分钟（`59 ↔ 00`）首尾循环滚动，滚动停稳接近边界时静默拉回中央区域；点击可见项也可平滑居中。
     - 通过 `hourState` / `minuteState` + `rememberUpdatedState` 消除跨列闭包覆盖串扰；在 `!listState.isScrollInProgress` 下才响应外部补对齐，不打断手指甩动惯性；`SelectedMinuteReadout` 隔离状态读取，避免拖动期间外层玻璃面板重组。
   - **蓝牙考勤机特征与安全阀（`BleScanReceiver.kt` / `BtScanner.kt` / `BtDeviceLearner.kt` / `BtPunchRule.kt`）**：
     - 稳定特征为 `ServiceUuid = 0000fe3c-0000-1000-8000-00805f9b34fb`（16-bit `0xFE3C` + 22 字节 Manufacturer Data + 设备名/SN），兼容未配对设备 BLE RPA 随机 MAC 轮换（`UUID` 必选 + `MAC`/`名称` 增强确认）。
     - 首页与设置页 UI 文案均明确展示当前学习到的设备名称、MAC 与 `UUID 0xFE3C` 特征。
   - **设置页分组结构（`SettingsScreen.kt`）**：
     - 依次为：自动打卡 → 外观 → 打卡时间 → 蓝牙自动打卡 → 安全 → 通知 → 打卡日志 → 关于（`版本 1.0.0`）→ **调试（「预览钉钉娘悬浮窗」独立单行卡片）** → **权限与保活**。

---

## 二、关键约束与避坑备忘

1. **后端与保活红线**
   - 必须保持 `AlarmManager.setExactAndAllowWhileIdle`（预热 75 秒 + 准点 + 120 秒补执行）与 `BOOT_COMPLETED` 重排，不依赖 `WorkManager` 做准点触发。
   - 后台（`onStop` 后）严禁持续渲染：`FlowingBackground(paused = true)` 停止时间推进，悬浮控制条仅在执行阶段驻留。
   - 打卡成功必须以钉钉界面真实文本反馈为准（先查失败/外勤信号再查成功信号），记录于数据库 `evidence` 字段。
2. **HyperOS 3 设备特性**
   - 不声明 `neverForLocation` 是为了避免系统黑名单静默丢弃 iBeacon/特定考勤机广播，代价是本机必须同时申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`。
   - `adb install` 新包名时需保持屏幕解锁，否则 MIUI 安全中心会直接返回 `INSTALL_FAILED_USER_RESTRICTED`。
   - 通知发出后必须保留代码级 `Vibrator` + `ToneGenerator` 反馈（防止通知渠道被系统/用户静音）。
3. **跨列 / 动效与 Backdrop 注意事项**
   - `effects` 链路顺序固定为 `vibrancy() -> blur(...) -> lens(...)`；不要增加全树重组的 `LaunchedEffect` 读状态。
   - 弹窗等跨层组件优先走 `LocalGlassModalHost`，使背景录制统一覆盖系统栏区域。

---

## 三、开发、测试与验收命令

```powershell
# 1. 运行全部单元测试（20 个测试类 / 185 个用例）
#    因 AGP 9.x 下 testDebugUnitTest 存在运行时 classpath 缺陷，统一使用该脚本直跑 JUnitCore
powershell -ExecutionPolicy Bypass -File run-tests.ps1

# 2. 编译签名版 Release / Debug APK（需根目录配置 local.properties，签名需 keystore.properties）
.\gradlew.bat :app:assembleRelease :app:assembleDebug --console=plain --no-daemon

# 3. 检查设备闹钟、无障碍、进程与电池白名单状态
.\verify-alarm.ps1
```

> 另一台电脑协同开发：先克隆仓库 `master` 分支，配置 `local.properties`（`sdk.dir=...`），再将本机的 `dingdinggo-release.jks` 与 `keystore.properties` 私下复制到项目根目录即可（切勿提交进 Git）。
