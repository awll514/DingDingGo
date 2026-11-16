# 项目：Redmi K90 至尊版 钉钉自动打卡 App（单机型专用）

## 角色与目标
你是一名资深 Android 工程师，为一个**单一设备**开发钉钉自动打卡工具：
- 设备：Redmi K90 至尊版
- 系统：HyperOS 3，底层 Android 16（API 36）
- Root：Magisk / KernelSU 可用
- 目标：锁屏下按用户设定时间自动完成钉钉上下班打卡，打卡后本地通知，UI 采用 HyperOS 3 风格的液态玻璃动效。

不要考虑其他机型和其他 OS 版本的兼容性。所有技术决策以这台设备上的可运行性为最高优先级。

## 技术栈约束
- 语言：Kotlin
- UI：Jetpack Compose（Material 3 为基础，但外观由自定义玻璃管线主导，不直接套用 Material 默认卡片）
- 液态玻璃：使用 `io.github.kyant0:backdrop-android:2.0.1`（`Backdrop` + `RenderEffect.createBlurEffect(CLAMP)` + `lens` + `Highlight`/`Shadow`/`InnerShadow`），配合 `SystemBlur` / `DialogSystemBlur` 系统窗口模糊；核心容器走 `LiquidGlassPanel`，次要/健康度与蓝牙状态卡片走 `FrostedPanel`，保留 `GlassPerfGuard` 自动降级兜底
- 调度：AlarmManager.setExactAndAllowWhileIdle 作为硬调度主干；前台服务作为执行编排器，不依赖 WorkManager 做打卡准点触发（Android 16 对前台服务关联的后台作业有配额限制）
- 持久化：Room（日志）+ EncryptedSharedPreferences（锁屏密码等敏感配置；不可用时降级并在 UI 诚实告警）
- 执行后端可插拔：无障碍服务为首选，root shell `input` 作为降级/备用通道

## 功能需求（逐项实现，不要遗漏）

### F1. 打卡时间配置
- 上班时间、下班时间，各精确到分钟
- 按星期选择生效日（默认周一至周五）
- 单日临时覆盖，不影响后续循环
- 时间选择器使用玻璃风格的自定义滚轮，不使用系统 TimePicker

### F2. 主界面状态显示
必须实时显示：
1. 下一次打卡类型（上班/下班）+ 倒计时（时:分:秒，秒级刷新）
2. 今日上班是否已打、下班是否已打；已打则显示实际时间
3. 当前状态：未到时间 / 等待执行 / 执行中 / 成功 / 失败（附原因）
4. 保活健康度：闹钟已注册？无障碍在线？钉钉可启动？任一异常时给出轻量提示

### F3. 锁屏自动打卡流程
按以下顺序实现，每一步都要有超时和失败回退：

1. **预热闹钟**：目标时间前 75 秒触发 `AlarmManager` 精确闹钟，获取 `PARTIAL_WAKE_LOCK`
2. **点亮屏幕**：`PowerManager.wakeUp()`；root 下可备用 `input keyevent 224`
3. **解锁**：
   - 无密码：`input swipe` 上滑
   - 有数字密码：上滑后 `input text <password>` + `input keyevent 66`
   - 密码从 EncryptedSharedPreferences 读取，不得硬编码
4. **启动钉钉**：Intent 启动 `com.alibaba.android.rimet`，等待 ≥8 秒或轮询 Activity 栈直至“工作”页就绪
5. **点击链**：`工作` → `考勤打卡` → 打卡按钮
   - 首选：无障碍服务，基于 text / viewId 定位
   - 降级：root `input tap x y`，配合截图或 UI 状态校验
   - 每步校验页面状态，失败重试最多 3 次
6. **结果确认**：读取界面文本（“打卡成功”/“已打卡”等）或 accessibility event，**以界面真实反馈为成功依据**
7. **收尾**：灭屏、释放唤醒锁、写日志

**打卡通道切换**：如果无障碍在该路径上连续失败，自动切换到 root shell 通道；通道选择记录在设置中可手动指定。

**禁止事项**：不做虚拟定位，不做位置伪造，不修改钉钉包体。

### F4. 打卡后通知
- 成功：普通优先级本地通知，标题“✅ 上班打卡成功”，正文包含实际时间
- 失败：高优先级通知，标题“⚠️ 打卡失败”，正文简述失败环节，附带“立即重试”PendingIntent
- 通知使用自定义 RemoteViews 或 DecoratedCustomViewStyle，深色半透明圆角，与 App 视觉一致

### F5. 开机自启与保活
- 静态 `BroadcastReceiver` 监听 `BOOT_COMPLETED`，重新注册所有闹钟
- 设置页提供一键跳转 HyperOS 自启动管理页（`miui.intent.action.OP_AUTO_START`）
- 前台服务：低功耗，仅在打卡窗口临近时持有唤醒锁；通知渠道 `IMPORTANCE_LOW`
- root 辅助：用 `su` 将 App 加入省电白名单/自启动白名单；可选进程存活检查，但**不要写过于激进的循环拉起**，避免引发系统异常
- 闹钟兜底：目标时间后 120 秒内保留一次补执行机会
- UI 必须诚实显示保活健康度，**不得承诺“绝对不死”**

### F6. 设置页
- 时间配置入口
- 锁屏密码设置（写入 EncryptedSharedPreferences）
- 权限状态卡片：自启动、无障碍、电池无限制、root 可用性，每项带跳转按钮
- 执行后端选择：自动 / 仅无障碍 / 仅 root
- 最近 30 天打卡日志查看

## UI/UX 规格：Apple Liquid Glass（iOS 26）为首要标准

> **设计优先级：美观第一。** 这台设备（Redmi K90 至尊版 / Adreno 830）性能充足，
> 允许使用最顶级的视觉与动效，**不为性能做妥协**。
> 唯一例外见下方「后台零消耗」红线：不显示时不能有任何持续开销。

### 总原则

一切视觉决策以 **Apple Human Interface Guidelines · Liquid Glass (iOS 26)** 为准。
不是"参考风格"，而是**按规格实现**：尺寸、间距、透明度、动效时长都要能对应到 HIG 的具体条款。

三条治理原则（Apple 原文）逐条落地：

| 原则 | 在本项目中的含义 |
|------|------------------|
| **Hierarchy（层次）** | 控件浮在内容之上的独立功能层。底栏、按钮、悬浮窗都属于"玻璃层"，**绝不能用在内容卡片上** |
| **Harmony（和谐）** | 同心圆角：嵌套元素的圆角半径逐级递减，共享同一圆心。胶囊控件统一 `RoundedCornerShape(999.dp)` |
| **Consistency（一致）** | 全应用同一套材质参数，不做「这个页面特殊」的例外 |

### 材质规格（`GlassTheme` / `GlassColors` / `GlassDimens`）

全应用统一双主题色板：**夜幕琉璃蓝（Dark）** 与 **晨曦晶透蓝（Light）**，语义色与材质参数经 `GlassThemeProvider` 平滑过渡（`GlassMotion.THEME_TRANSITION`）：

| 参数 | 规格 | 说明 |
|------|------|------|
| 主玻璃面板（`LiquidGlassPanel`） | `backdrop-android:2.0.1`（`vibrancy` + `RenderEffect` 高斯模糊 + 边沿 `lens` 折射 + `Highlight`/`InnerShadow`） | 浅色淡蓝叠加、深色深蓝半透，顶部高亮底部收敛 |
| 次要磨砂面板（`FrostedPanel`） | 垂直通透渐变 + 顶部高光描边（深色 `0.34~0.48` alpha；浅色 `0.26~0.52` cool ice alpha） | 用于 `BtStatusCard`、`HealthCard` 及 `GlassPerfGuard` 降级兜底 |
| 背景流动层（`FlowingBackground`） | 约 24fps 正余弦极光流场（纯绘制阶段读取 `progress`，`paused` 时完全停帧） | 前台轻柔流动，退后台或息屏即停止调度 |
| 圆角与间距（`GlassDimens`） | 卡片 `24.dp` / 按钮全圆 `999.dp` / 内缩边距 `16.dp` | 同心圆角层级统一 |
| 动效时长（`GlassMotion`） | 统一 `IosEaseOut` / `IosEaseInOut` | 无默认线性，无僵硬跳变 |

### 底栏（`GlassTabBar`）

- 居中悬浮胶囊（约 `0.60f` 屏幕宽，高 `58.dp`，底部边距 `21.dp`），完全脱离屏幕边缘并采样 `combinedBackdrop` 折射背后页面内容
- **图标 + 文字垂直堆叠**（`Home` / `Settings` Rounded 图标 + 标签），选中指示器为独立液态胶囊（`96.dp × 46.dp`）
- 拖拽页面时通过 `liveOffsetProvider: () -> Float` 在 `snapshotFlow` 内读取，**零重组**同步页面滑动偏移，拖拽放大倍率 `BarDragScale = 1.26f`；点击切换时播放液滴拉伸动效
- 浅色模式下指示器采用高透晶莹白渐变（`BodyBrushLight` + `EdgeBrushLight`）配极淡冷蓝阴影，深色模式下采用夜幕冰蓝半透椭圆

### 动效规格（`GlassMotion` · iOS 标准非线性缓动）

全应用交互状态切换统一收敛到 `GlassMotion.kt`（`IosEaseOut = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`，`IosEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)`），拖拽与点击分流处理（滑动结束用 `scrollToPage` / 静默归位，点击切换播放完整翻页与拉伸曲线）：

| 场景 | 时长 / 常量 | 曲线 |
|------|------|------|
| 底栏指示器位移 | `TAB_INDICATOR = 420ms` | `IosEaseOut` |
| 液滴拉伸 / 收回 | `260ms` / `460ms` | `IosEaseOut` / `IosEaseInOut` |
| 页面切换 / 弹窗入场 | `PAGE_SCROLL = 460ms` / `280ms` | `IosEaseOut` |
| 明细展开与主题过渡 | `DISCLOSURE = 280ms` / `THEME_TRANSITION = 450ms` | `IosEaseOut` / `IosEaseInOut` |
| 按压反馈 | 缩放 `0.97f` + `drawBehind` 镜面高光下移（`PRESS = 180ms` / `RELEASE = 340ms`） | `IosEaseOut` |

### 按钮、时间滚轮与弹窗（`LiquidGlassButton` / `GlassWheelTimePicker` / `ConfirmDialog` / `TimeEditorSheet`）

- **按钮**：`drawBehind` 阶段绘制按压高光移动与透镜边缘提亮，无每帧重组或笔刷分配，伴随 `HapticFeedback`
- **时间选择器**：自定义 `GlassWheelTimePicker`，居中玻璃透镜高亮条（`0.76f` 宽 × `itemHeight` × `14.dp` 圆角）
- **弹层**：背景轻量退避遮罩（绝不压黑），由 `DialogSystemBlur` 通过反射枚举 `WindowManagerGlobal.mViews` 精准对 `TYPE_APPLICATION` 弹窗窗口开启系统 `FLAG_BLUR_BEHIND`（默认 `28.dp`，实机约 `91px`）与低 `dimAmount = 0.06f`，并用 `LocalLiquidBackdrop provides SharedBackdrop.sceneBackdrop` 供给前景模糊纹理

### 屏幕接管提示（`ControlHudOverlay`）

- **只在自动打卡接管屏幕时出现**，平时不常驻视图；靠近屏幕左右边缘时自动反向展开，宽度贴合内容
- 提供暂停/继续（`II` / `▶`）与取消（`X`）快捷操作，`setPaused()` 精确更新文本容器 `root.getChildAt(0)` 与按钮 `root.getChildAt(2)`

### 页面结构

- 底部两 tab：**首页 / 设置**（底部预留 `GlassDimens.bottomBarReserved = 124.dp` 滚动安全空间）
- **首页（`HomeScreen`）**：标题与状态胶囊 → Hero 倒计时面板（`54.sp` 等宽数字 `tnum`、周期进度条、F2.3 内联 `HomeStatus.Failed` 失败原因横幅与重试入口）→ 今日双状态卡 → 「立即手动打卡」主按钮 → `BtStatusCard`（缺蓝牙权限时提供「去授权」快捷入口）→ `HealthCard`（可展开/收起「明细」列表，诚实边界说明）→ 跳过/恢复文字按钮
- **设置页（`SettingsScreen`）**：分组严格按 **自动打卡 → 外观 → 打卡时间 → 蓝牙自动打卡 → 安全 → 通知 → 打卡日志 → 关于 → 权限与保活** 排列；支持「密码定位自动滚动」（`BringIntoViewRequester`）与 `secureStorageDegraded` 降级黄色告警

### 玻璃分层与采样规范（`LocalLiquidBackdrop` / `SharedBackdrop`）

1. **三层Backdrop 管线**：`bgBackdrop`（仅录制 `FlowingBackground`）供主/设置页所有 `LiquidGlassPanel` 采样，避免内容自录制递归；`contentBackdrop` 录制主内容层；`combinedBackdrop = rememberCombinedBackdrop(bgBackdrop, contentBackdrop)` 供悬浮底栏 `GlassTabBar` 采样并写入 `SharedBackdrop.sceneBackdrop`。
2. **Compose Dialog 跨 Window 采样**：弹窗所在 subcomposition 显式覆盖 `LocalLiquidBackdrop provides SharedBackdrop.sceneBackdrop`，再叠加系统级 `DialogSystemBlur` 实时虚化底层窗口。
3. **无首帧伪降级延迟**：不使用会导致材质长期锁死在降级路径的 `withFrameNanos` 首帧开关；仅当 `Build.VERSION.SDK_INT < S`、`RenderEffect` 不可用或 `GlassPerfGuard` 主动触发降级时切换到 `FrostedPanel`。

### 后台零消耗红线

> 用户原话：「没运行时在后台的时候不要消耗资源」。

这是唯一不可妥协的性能约束，语义是**不做无谓的持续开销**：

1. 后台（`onStop` 后 / 无界面显示）时**不得有任何持续渲染**（`FlowingBackground(paused = true)` 停止时间步进，主窗口后台 0 帧渲染）
2. 保活服务只做定时自检，不发动画、不轮询 UI
3. 前台服务只有在打卡窗口临近时才持唤醒锁
4. 隐藏的悬浮组件必须 `return` / 移除，不能仅设为透明
## 关键代码约束与验收标准

1. **AlarmManager 必须使用 `setExactAndAllowWhileIdle`**，并在 `BOOT_COMPLETED` 后重新调度；验收时杀掉 App 进程，闹钟仍能触发 BroadcastReceiver。
2. **锁屏打卡全流程**必须在 **屏幕关闭 + 有锁屏密码** 的状态下完整跑通一次（可用 adb 或手动锁屏验证）。
3. **打卡成功判定必须来自钉钉界面反馈**，不能以“点击已发出”作为成功；在日志中记录判定依据。
4. **玻璃面板必须采样真实背景**：验收时背景光斑应在流动，玻璃面板内可见其模糊折射；不能是纯色半透明。
5. **玻璃面数量不再设上限**（用户已明确"不用考虑性能消耗"）。但需满足两条：
   - 前台显示时，Adreno 830 上不得出现可见掉帧（用 `dumpsys gfxinfo` 或 GPU 渲染分析确认）；
   - **后台时零渲染**：Activity 不可见后不得有任何持续动画或 AGSL 计算。
6. **通知必须到达**：成功和失败两种情况分别验证通知标题、正文和重试按钮。
7. **打卡完成必须有触觉 + 声音反馈**：震动与提示音不能只依赖通知渠道（用户可能静音渠道），
   需用 `Vibrator` + `ToneGenerator` 做代码级反馈。
8. **底栏拖拽必须同步切换页面**：把指示器从「设置」拖到「首页」，页面内容与高亮都要跟过去，
   不能只有指示器移动。
9. 提供 `README.md`，列出 HyperOS 3 上必须手动开启的系统设置清单（自启动、省电无限制、无障碍、通知权限、root 授权）。

## 风险与诚实边界
- 在代码注释和 README 中明确：HyperOS 3 的后台管控可能导致闹钟延迟或进程被杀，本 App 通过多重兜底提高成功率，但**不保证 100% 准时执行**。
- 钉钉可能对 root 环境或非标准 UI 自动化有风控行为；**不做任何伪造位置或篡改客户端的行为**。执行失败时以通知和日志让用户知情并手动兜底。
- 如果某个步骤在你的测试中无法稳定复现，**不要假装它已经工作**；在 README 中标注该步骤的已知限制，并在代码中保留降级路径。

## 交付物
1. 完整可编译的 Android Studio 项目（Kotlin + Compose）
2. `README.md`：设备配置清单、权限开启步骤、已知限制、构建说明
3. 一条 `adb` 命令或脚本，用于快速验证闹钟已注册（`adb shell dumpsys alarm | grep <package>`）