# 钉钉GO · 交接文档

> 生成时间：2026-11-11 · 设备：Redmi K90 至尊版（M332BF / warsaw / HyperOS V816 / Android 16 / API 36）
>
> **最后更新：2026-11-11 14:05（第二轮真机迭代，详见 §0）**

---

## 0. 第二轮真机迭代的成果（本机实测）

### 0.1 修复的 6 个用户反馈问题

| # | 问题 | 修复方式 | 验证 |
|---|------|---------|------|
| 1 | 底栏指示器停住后又拉伸一下 | `settleTo` 加 `stretch` 分流 + `xAnim` 跟踪 pager 位置 | 录屏逐帧：动画后完全静止 ✅ |
| 2 | 手动打卡后没替换自动节点 | 新增 `CompletedSlot`，`nextSlot` 跳过已完成班次 | 11 个单元测试 ✅ |
| 3 | 极速打卡识别不出成功 | 抽出 `judgeResult()` 纯函数，加「状态翻转」判定 | 13 个单元测试 ✅ |
| 4 | 操控屏幕时悬浮窗不出现 | 线程修正 + 不再静默失败 + 加「测试悬浮窗」入口 | **真机截图确认 ✅** |
| 5 | 锁屏密码无法配置 | `securePrefs()` 自愈（清损坏 key 重建）+ `commit` 回读校验 | **真机配置成功 ✅** |
| 6 | 幽灵触控点到「跳过今天」 | **查明是 GKD 第三方工具误触**，非代码 bug | 用户已加排除规则 ✅ |

### 0.2 幽灵触控的真相（重要）

排查过程发现，用户设备上装了 **GKD**（一个自动点击工具），
它的无障碍服务在运行，规则匹配到了「跳过今天的自动打卡」这行文字，
用无障碍接口点击了它 —— **完全是外部工具行为，不是本 App 的 bug**。

证据链：
- 事件坐标恒为控件中心（`x=640`），非手指位置
- 「藏在底栏下面也能点中」→ 无障碍不看视觉遮挡
- 「切到设置页就不触发」→ 那页没有匹配文字
- `enabled_accessibility_services` 里有 `li.songe.gkd`

**保留的防护**：`MainActivity.dispatchTouchEvent` 的孤立 UP 拦截
（处理「按着屏幕切后台，UP 永远不来导致状态卡死」的边界场景）。

### 0.3 玻璃渲染的 10 轮排查（最重要的技术债已清）

**问题现象**：无论怎么调 `blurRadius`（16→32→52dp），画面毫无变化。

**根因**：`LiquidGlassPanel` 里有一段「冷启动首帧降级」逻辑：

```kotlin
var agslReady by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { withFrameNanos {}; withFrameNanos {}; agslReady = true }
val agsl = ... && agslReady      // ← 永远 false
```

`withFrameNanos` 在面板频繁重组时被反复取消，`agslReady` 永远来不及置 true。
**所有面板（卡片、底栏）永久停在 `FrostedPanel` 降级路径** ——
而那条路只有半透明填充，**没有模糊、没有折射**。

诊断日志（`GlassDiag`）证实：
```
path=FROSTED降级 | agslSupport=true degrade=false ready=false
```

**修复**：移除首帧降级，直接 `val agsl = isAGSLShaderSupported() && !shouldDegrade`。
代价是冷启动多 30~50ms，换取正确的玻璃质感。

### 0.4 弹窗背景真模糊（8 次尝试后成功）

AGSL 的 `blurRadius` 只能模糊**它采集的 GraphicsLayer**。
弹窗打开后主界面被遮挡、录制层冻结 → 参数完全无效。

**正解**：`Window.setBackgroundBlurRadius()`（系统合成器真模糊）。

**卡了 8 次的地方**：取 Window 的方式。

```kotlin
// ✗ view.parent / view.rootView.mAttachInfo → 永远是 Activity 窗口
// ✓ 枚举 WindowManagerGlobal.mViews，按 type == TYPE_APPLICATION 筛选
```

`dumpsys window windows` 证实：弹窗是 `type=2` 的独立窗口，
而主窗口是 `type=1` —— 前 8 次都在改主窗口，所以毫无效果。

另外 `dimAmount` 默认 ~0.6 会把模糊结果压暗，需要降到 0.06。

### 0.5 配色的最终方案

**核心规律：`tint` 必须是彩色的，白色 tint 无论 alpha 多少都会推向灰色。**

| tint | alpha | 实测结果 | 判定 |
|------|-------|---------|------|
| `0xFFF0FAFF`（G高） | 0.036 | RGB(136,202,244) 203° | 青绿 ✗ |
| `0xFFFFFFFF`（白） | 0.036 | 灰 | ✗ |
| 白 | 0.20/0.23/0.42 | RGB(197~211, 恒等, 恒等) | 全灰 ✗ |
| **`0xFFA0CFFF`（蓝）** | **0.09** | **RGB(145,191,250)** | **✓** |

参考目标是设置页卡片 `RGB(160,198,247)`，最终差值 R=15 / G=7 / B=3。

---

## 1. 项目现状：可用

| 项目 | 状态 |
|------|------|
| 编译 | ✅ `BUILD SUCCESSFUL` |
| 单元测试 | ✅ **84 / 84 全绿**（新增 24 个） |
| 真机运行 | ✅ 无崩溃（`logcat AndroidRuntime:E` 空） |
| 冷启动 | ⚠️ ~1966ms（首帧降级已移除，见 §0.3） |
| 玻璃渲染 | ✅ AGSL 全管线正常运行 |
| 包名 | `com.redmi.autoattend`（debug 后缀 `.debug`） |

---

## 2. 本轮完成的内容

### 2.1 底栏（GlassTabBar）—— 重写

修掉三个真实 bug，并达到 iOS 26 视觉标准：

**bug 1：拖拽只有指示器动、页面不切（第二次必现）**

根因是 **Kotlin 闭包捕获了过期状态**：

```kotlin
// 错误：pointerInput 的 key 不含 selectedIndex，手势块永不重建
.pointerInput(barWidthPx, tabs.size) { ... selectedIndex ... }
```

手势 lambda 一直拿着首次组合时的 `selectedIndex`，第二次拖拽时比较的是旧值，
`nearest != selectedIndex` 恒为 false，`onSelect` 从不被调用。

修复：`val currentSelected by rememberUpdatedState(selectedIndex)`，手势里读最新值。

**bug 2：指示器卡在中间**

旧代码在 `onDragEnd` 里既 `settleTo()` 又 `onSelect()`，
加上 `LaunchedEffect(selectedIndex)` 也 `settleTo()` —— 三个来源抢同一个 `Animatable`。
现在统一：**只有 `onSelect` 触发状态变更，只有 `LaunchedEffect` 驱动动画**。

**bug 3：双边切换不对称**

旧判定用 `round((dragX + iw/2) / tabWidth)`。tab 中心恰好落在 `k+0.5` 整数边界，
`round` 向上取整 —— 从首页拖 0px 就判定为切到设置，反方向却要拖满一格。

现在改为**比较到各 tab 中心的绝对距离**，实测双向均为 291px 触发。

**视觉**：
- 底栏宽度 0.56 屏宽居中（真正的悬浮胶囊，不是通栏条）
- 高度 52dp，指示器 78×40dp
- **用 `LiquidGlassPanel` 做容器**，采样 `LocalBackdropLayer` 的录制层，
  得到真正的模糊 + 折射 + 色散。之前只是半透明白块，所以"不像玻璃"
- 指示器拖拽时放大 **1.55×**（40 × 1.55 = 62dp > 52dp，真正溢出容器上下边界）
- **移除了常驻流光**（用户要求），改为静止安静、仅交互动

### 2.2 屏幕接管提示 → 系统悬浮窗

**这是一个设计缺陷修复，不只是换实现。**

原来的 `ControlHud` 是应用内 Composable。但打卡时**钉钉在前台、我们在后台**，
那个提示根本不可见 —— 等于没有。

现在改为 `ControlHudOverlay`（`TYPE_APPLICATION_OVERLAY`）：
- 能盖在钉钉之上
- 贴右上角，内容自然向左延伸（符合"在右边就往左展开"）
- 含暂停（26dp）/ 取消（26dp）两个微型按钮
- **只在接管时存在**，`hide()` 立即移除，不占资源
- 需要 `SYSTEM_ALERT_WINDOW` 权限（已加进 manifest，需用户手动授权）

清理逻辑集中在 `fail()` 和 `catch` 里 —— 所有失败路径都经过 `fail()`，
避免 6 个 return 各写一遍 hide() 而漏掉某处。

### 2.3 应用改名 + 图标

- 名称：`自动打卡` → **钉钉GO**
- 图标：像素风钉钉娘，依据萌娘百科官方设定绘制
  - 头顶呆毛是小翅膀 / 蓝毛掺银 / 衣服小翅膀 / 蓝白异色瞳
  - 背景三层：深蓝夜色渐变 + 中心径向光晕 + 底部像素网格
- 全部用 XML vector，无位图，任意尺寸清晰

### 2.4 打卡完成反馈

`PunchNotifier.playCompletionFeedback()`：
- **震动**：成功 = 两下短促（45ms-70ms-45ms）；失败 = 一次长震（180ms-90ms-60ms）
- **声音**：`ToneGenerator` 合成（`TONE_PROP_ACK` / `NACK`），160ms
- 不用音频文件，省资源
- 关键：**不依赖通知渠道**。用户可能把渠道静音，代码级反馈保证一定可感知

### 2.5 常驻通知开关

设置 → 通知 → 「常驻保活通知」。
关掉后服务仍运行（保活不依赖通知），但通知压到 `PRIORITY_MIN` 只剩一行。

### 2.6 本轮迭代（第二轮反馈）

**量化规格**（已写入 `AGENTS.md`）：

| 参数 | 值 |
|------|-----|
| 玻璃透明度 | 20%–40% |
| 背景模糊 | 40px 档 |
| 圆角 | 16–24dp（内容）/ 全圆（胶囊） |
| 动效 | 300–600ms |

**配色修正**（实测驱动）：

之前背景用了 `#B57BFF`（紫粉），模糊后整片发紫，卡片采样再被 vibrancy 增强，
看起来完全不是蓝调。现在四个光斑全部锁在 190°–218° 蓝—靛区间。

卡片 tint 迭代：
```
0.030 → 卡片发灰发暗（RGB 169,187,213，比背景还暗）
0.110 → 提亮过头（蓝色通道 249，比背景 227 还蓝，很跳）
0.058 → 当前值，与背景同色系不刺眼
```

**间距修正**：发现 `HomeScreen` 里 `10.dp` 和 `cardGap` 被连续叠加了两次，已删除。
**底部预留修正**：`MainActivity` 预留 + `HomeScreen` 内 Spacer 双重计算，已统一到一处。

---

## 3. 关键红线（改动前必读）

### 3.1 安全常量 —— 绝对不可改动

```kotlin
LATE_GRACE_MS = 60_000L              // 迟到判定：上班后满 1 分钟
LATE_SAFETY_MARGIN_MS = 5_000L       // 安全余量
CLOCK_OUT_SAFETY_MARGIN_MS = 2_000L  // 下班卡最早触发点
```

语义：
- **下班之前绝不打下班卡**（`clock_out` 目标时间 +2s 才允许）
- **上班之后绝不打迟到卡**（进入上班时间后 55s 内是绿色窗口，满 60s 才算迟到）

改动这些常量必须让 `SafetyGateTest`（21 个用例）失败。

### 3.2 成功判定

**必须来自钉钉界面真实反馈**，不能以"点击已发出"为成功。
`verifyResult()` 返回非 null 才能 `success = true`。
全库只有一个 `success = true` 站点。

`ALREADY_DONE ≠ SUCCESS`。

### 3.3 外勤规则

检测到「未进入考勤范围」/「可外勤打卡」时：
- **不点击**
- 每 **5 秒**重新检测
- 最长等 **15 分钟**，超时则放弃并通知

### 3.4 后台零消耗

Activity 不可见后**不得有任何持续动画或 AGSL 计算**。
隐藏的悬浮组件必须 `return`，不能只把 alpha 设 0。

---

## 4. 已知问题 / 待完成

### 4.1 冷启动 ~1.9s（已优化，仍偏慢）

从 2125ms 降到 1936ms（移除 pager 相邻页预渲染）。
主因是 AGSL 玻璃面板的首帧录制成本。

**建议**：首帧先用 `FrostedPanel` 降级、稳定后退到 AGSL。

### 4.1b 冷启动停留在上次页（预期行为，非 bug）

`rememberPagerState` 内部是 `rememberSaveable`，
**Activity 被系统回收后重建**会恢复到上次的页 —— 这是正确的系统行为。

`am force-stop` 后进程是全新的，会正常从首页开始。
如果用 `am start` 反复启动同一个实例，看到的是恢复后的页面，不是 bug。

### 4.0 ⚠️ 最新一轮（幽灵触控 + 底栏折射）尚未真机验证

设备在改动完成前已断开。以下改动**编译通过、单元测试全绿，但没有跑过真机**：

| 改动 | 状态 |
|------|------|
| 幽灵触控拦截（dispatchTouchEvent） | ⚠️ 未验证 |
| 底栏折射参数（14dp/26dp/0.22） | ⚠️ 未验证 |
| contentBackdropLayer 内容录制 | ⚠️ 未验证 |
| 底部占位 124dp → 74dp | ⚠️ 未验证 |

**重新上机时优先验证第 1 项**：
反复冷启动 10 次，确认不再自动点在「设置」和「跳过今天」上；
用 `adb logcat -s GhostGuard` 看拦截日志。

底栏折射需要肉眼判断（是否既能透出字、又有边缘形变）——
如果太透就调 tintAlpha 到 0.08，如果折射过头就降 refractionAmount 到 18dp。

### 4.2 暂停/恢复未真机验证

`ACTION_PAUSE` / `ACTION_RESUME` 已实现且编译通过，
但**从未真正触发过一次打卡来验证**。

需要：在打卡窗口期手动触发一次，观察悬浮窗暂停按钮是否生效、能否恢复。

### 4.3 系统悬浮窗需用户手动授权（已有入口）

打卡时若没有悬浮窗权限，`ControlHudOverlay` 会静默跳过提示（不阻塞打卡）。

设置 → 通知 里已经加了入口：
- 有权限时只显示「屏幕接管提示」开关
- 没有权限时额外显示「授予悬浮窗权限」跳转行

### 4.4 设置页尚未完全苹果化

分组卡片、列表行、字号字距已按 HIG 调整，
但**分段控件、开关、周选择器**仍沿用旧样式，尚未逐个重做。

### 4.5 时间选择弹层未截图确认

去掉了压黑遮罩、加了入场动画（缩放 0.92→1.0 + 淡入 280ms），
但**没有截图验证最终观感**。

---

## 5. 构建与调试

### 5.1 构建

```powershell
$env:JAVA_HOME="E:\dev\jdk21\jdk-21.0.2"
$env:ANDROID_HOME="E:\dev\android-sdk"
Set-Location "E:\dsh\dingding-go"
& ".\gradlew.bat" :app:assembleDebug --no-daemon
```

### 5.2 安装后必须重新启用无障碍

> ⚠️ **每次 `install -r` 后无障碍都会被解绑**，必须重新写入。

```powershell
$adb = "E:\dev\android-sdk\platform-tools\adb.exe"
$svc = "com.redmi.autoattend.debug/com.redmi.autoattend.exec.PunchAccessibilityService"
$base = "li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService:io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService"
& $adb shell ("settings put secure enabled_accessibility_services " + [char]39 + $base + [char]58 + $svc + [char]39)
& $adb shell "settings put secure accessibility_enabled 1"
```

### 5.3 授予悬浮窗权限

```powershell
& $adb shell "appops set com.redmi.autoattend.debug SYSTEM_ALERT_WINDOW allow"
```

### 5.4 验证闹钟已注册

```powershell
& $adb shell "dumpsys alarm | Select-String autoattend"
```

### 5.5 查崩溃

```powershell
& $adb logcat -d -s AndroidRuntime:E
```

---

## 6. 代码地图

```
app/src/main/java/com/redmi/autoattend/
├── MainActivity.kt                    入口；三层布局（背景/内容/底栏）
├── core/
│   ├── AppConfig.kt                   SharedPreferences 封装；跳过日期、通知开关
│   ├── PunchSchedule.kt               ★ 时间计算（纯函数，可测）；dayLabel/vacationLabel
│   └── AlarmScheduler.kt              AlarmManager 精确闹钟
├── exec/
│   ├── PunchEngine.kt                 ★ 打卡主流程；安全闸门；外勤轮询
│   ├── PunchForegroundService.kt      前台服务；PAUSE/RESUME/CANCEL
│   ├── PunchAccessibilityService.kt   无障碍通道
│   ├── RootShell.kt                   root 通道
│   ├── ControlHudOverlay.kt           ★ 系统悬浮窗提示（本轮新增）
│   ├── PunchNotifier.kt               通知 + 震动 + 音效
│   └── KeepAliveService.kt            保活；常驻通知开关
├── ui/
│   ├── MainViewModel.kt               ★ 单一数据源；100ms tick
│   ├── components/
│   │   ├── GlassTabBar.kt             ★ 底栏（本轮重写）
│   │   ├── LiquidGlassButton.kt       液态玻璃按钮（四层按压反馈）
│   │   ├── ClickableNoRipple.kt       无涟漪点击
│   │   ├── ConfirmDialog.kt           二次确认弹层
│   │   └── SettingsComponents.kt      设置页组件
│   ├── glass/
│   │   ├── LiquidGlassPanel.kt        ★ AGSL 全管线玻璃面板
│   │   ├── FrostedPanel.kt            轻量磨砂（降级路径）
│   │   ├── FlowingBackground.kt       ★ 流动光斑背景（20fps 节流）
│   │   └── LocalBackdrop.kt           背景录制层（玻璃采样源）
│   ├── screens/
│   │   ├── HomeScreen.kt              ★ 首页
│   │   ├── SettingsScreen.kt          设置页
│   │   └── TimeEditorSheet.kt         时间选择弹层
│   └── theme/
│       ├── GlassColors.kt             ★ 配色（深/浅两套）
│       └── GlassTheme.kt              圆角/间距尺度
└── data/db/                           Room 日志库
```

### 关键文件说明

| 文件 | 为什么重要 |
|------|-----------|
| `PunchSchedule.kt` | 纯函数，21 个安全测试全打在这里。改时间逻辑先跑测试 |
| `PunchEngine.kt` | 打卡主流程。`fail()` 是统一收尾点（含 HUD 清理） |
| `GlassTabBar.kt` | 踩过 3 个 bug。`rememberUpdatedState` 那行不能删 |
| `LiquidGlassPanel.kt` | 玻璃光学参数。`surfaceTintAlpha` 有实测注释 |
| `FlowingBackground.kt` | 20fps 节流是关键性能设计，别改回 60fps |

---

## 7. 测试

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --no-daemon
```

| 测试类 | 数量 | 覆盖 |
|--------|------|------|
| `SafetyGateTest` | 21 | 迟到/下班安全闸门（红线） |
| `UiHierarchyTest` | 14 | 界面节点定位 |
| `PunchScheduleTest` | 12 | 下一次打卡计算 |
| **`PunchResultJudgeTest`** | **13** | **打卡结果判定（含极速打卡、失败优先、最坏情况防护）** |
| **`CompletedSlotTest`** | **11** | **已完成班次跳过（手动打卡后不重复触发）** |
| `DayLabelTest` | 8 | 今天/明天/周几/日期文案 |
| `SkipDateTest` | 5 | 跳过某天后下次打卡顺延 |
| **合计** | **84** | |

> ⚠️ **AGP 9.3.1 在 Windows 下 `testDebugUnitTest` 会报 ClassNotFoundException**，
> 但测试类其实编译成功了（在 `intermediates/built_in_kotlinc/` 下）。
> 绕过方式：直接用 JUnitCore 跑，classpath 需要包含 kotlin-stdlib：
>
> ```powershell
> $cp = "...\built_in_kotlinc\debugUnitTest\compileDebugUnitTestKotlin\classes;" +
>       "...\built_in_kotlinc\debug\compileDebugKotlin\classes;" +
>       "junit-4.13.2.jar;hamcrest-core-1.3.jar;kotlin-stdlib-2.4.0.jar"
> & "$jdk\bin\java.exe" -cp $cp org.junit.runner.JUnitCore com.redmi.autoattend.SafetyGateTest
> ```

---

## 8. 设计决策记录

### 8.1 为什么底栏用 `LiquidGlassPanel` 而不是自己画背景

半透明白色圆角矩形 = 贴纸。真玻璃需要**采样背后内容 + 模糊 + 折射**。
`LiquidGlassPanel` 提供这套管线，自己画拿不到折射。

### 8.2 为什么指示器拖拽用 1.55× 而不是 1.18×

`44 × 1.18 = 51.9dp < 58dp`，没出围栏。
`40 × 1.55 = 62dp > 52dp`，上下各溢出 5dp —— 才真的"浮起来"。

### 8.3 为什么双击确认要自己做 `DialogButton` 而不是复用 `LiquidGlassButton`

`LiquidGlassButton` 内部是 `ClickableNoRipple`（`composed{}` 包 `pressEffect + clickable`）。
放进 `Dialog` 时**首次组合会幽灵触发一次 `onClick`**。
真机上表现：打开应用什么都没点，"跳过今天"却被写进了配置。

弹窗内改用最朴素的 `clickable` + 本地状态。

### 8.4 为什么背景节流到 20fps

挂了 `prismalGlassLayer` 的录制层每帧重新录制，每个玻璃面板每帧重采样。
光斑动画周期 26–42 秒，**根本不需要 60fps**。
节流到 20fps，视觉无差别，GPU 工作量降到 1/3。

---

## 9. 下一步建议（按优先级）

1. **验证暂停/恢复**（§4.2）—— 这是唯一没跑过的功能路径
2. **冷启动优化**（§4.1）—— 1936ms 仍偏慢
3. 设置页剩余控件苹果化（§4.4）—— 分段控件、开关、周选择器
4. 时间选择弹层视觉确认（§4.5）

---

## 10. 环境

| 项 | 值 |
|----|-----|
| 设备序列号 | `d65730c5` |
| **adb（当前机器）** | `C:\AndroidTools\platform-tools\adb.exe` |
| **JDK（当前机器）** | `C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot` |
| **SDK（当前机器）** | `C:\AndroidTools\sdk` |
| 屏幕 | 1280×2772 @ density 520（≈3.25x） |
| 截图缩放 | 预览 860×1860，换算系数 1.4884 |
| 锁屏密码 | 已存于 EncryptedSharedPreferences，**源码中不得硬编码** |

### 当前机器的构建命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME = 'C:\AndroidTools\sdk'
$env:ANDROID_SDK_ROOT = 'C:\AndroidTools\sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'F:\徐事\dsh\dingding-go'
.\gradlew.bat assembleDebug --console=plain
```

**注意**：项目路径含中文（`F:\徐事\dsh\dingding-go`），
`gradle.properties` 里已加 `android.overridePathCheck=true` 才能构建。

### 装机后的固定流程（每次 install -r 之后必须做）

```powershell
$adb = 'C:\AndroidTools\platform-tools\adb.exe'
$pkg = 'com.redmi.autoattend.debug'

# 1. 重绑无障碍（install -r 会解绑）
$svc = "$pkg/com.redmi.autoattend.exec.PunchAccessibilityService"
$base = "li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService:io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService"
& $adb -s d65730c5 shell ("settings put secure enabled_accessibility_services " + [char]39 + $base + [char]58 + $svc + [char]39)
& $adb -s d65730c5 shell "settings put secure accessibility_enabled 1"

# 2. 悬浮窗权限（appops 设置不持久，重装会重置）
& $adb -s d65730c5 shell "appops set $pkg SYSTEM_ALERT_WINDOW allow"
```

> ⚠️ **MIUI/HyperOS 的坑**：系统权限页会显示「已开启」，
> 但 `appops get` 可能是 `ignore`，此时 `canDrawOverlays()` 返回 false，
> 悬浮窗永远不显示。**必须用系统界面把开关关一次再打开**才真正生效。

---

## 11. 踩过的坑（给后来者）

### 第一轮

1. **Kotlin 闭包捕获旧值** —— `pointerInput` 里读 `selectedIndex` 必须用 `rememberUpdatedState`
2. **多个 Animatable 写入源** —— 同一个动画量只能有一个驱动者
3. **`round()` 在 0.5 边界的偏向** —— 判定"最近的项"要比较绝对距离，不要用 round
4. **`ClickableNoRipple` 在 Dialog 里幽灵点击** —— 弹窗内用原生 `clickable`
5. **双重 padding 叠加** —— 父级和子级都预留底部空间会出大片死区
6. **纯色背景上的玻璃是假的** —— 必须采样真实内容
7. **`install -r` 会解绑无障碍** —— 每次安装后必须重新写入
8. **截图预览是缩放过的** —— 物理坐标要乘 1.4884

### 第二轮（本次）

9. **`withFrameNanos` 做首帧降级会让 AGSL 永久失效** ——
   面板重组时协程被反复取消，`agslReady` 永远是 false。**不要用这种方式优化冷启动。**

10. **调 UI 参数前先确认渲染路径** —— 我在错误的 `FrostedPanel` 路径上
    调了 6 轮 `blurRadius`/`tint`/`折射`，全都没生效。加一行 `Log.i` 就能省下这些。

11. **Compose Dialog 是 subcomposition** —— 它会继承父级 `CompositionLocal`。
    想给它不同的采样源必须用**新的 CompositionLocal**（`LocalDialogBackdrop`）显式覆盖。

12. **主界面卡片不能采样内容层** —— 那是正在录制的内容本身，会无限递归崩溃。

13. **`view.parent` / `rootView.mAttachInfo` 拿不到 Dialog 的 Window** ——
    它们永远返回 Activity 窗口。必须枚举 `WindowManagerGlobal.mViews` 按 `lp.type` 筛选。

14. **白色 tint 会把玻璃推向灰色** —— 无论 alpha 多少。
    因为 `PrismalGlassSurface` 是采样后合成，白色相当于对采样值做平均。
    要彩色就必须给彩色 tint。

15. **测颜色必须确认取样点在元素内** —— 我用 `y=1600` 采样健康度卡片
    （实际范围 900~1250），连续 3 次测到背景色，还误判成"tint 没生效"。

16. **`dimAmount` 默认值会把系统模糊压暗** —— Dialog 默认 ~0.6，
    叠在模糊结果上整片发黑，看起来像"模糊没生效"。降到 0.06。

17. **第三方无障碍工具会干扰 UI 测试** —— 用户设备上的 GKD 会自动点击
    匹配到的文字（如「跳过今天」），排查时先确认有没有这类工具在跑。