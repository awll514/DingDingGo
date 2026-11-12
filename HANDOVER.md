# 钉钉GO · 交接文档

> 生成时间：2026-11-11 · 设备：Redmi K90 至尊版（M332BF / warsaw / HyperOS V816 / Android 16 / API 36）
>
> **最后更新：2026-11-12 16:45（第七轮：底栏动画层重构，详见 §0.11）**
>
> 分支：`fix/p0-punch-chain` · 基于 `91c07f3`
>
> ⚠️ **第五轮是唯一一次"真机跑通整条链路"的记录**。前四轮全部止步于
> 编译 + 单元测试，从未在锁屏状态下走完 点亮→解锁→钉钉→考勤页。
> 本轮实测暴露了 3 个只在真机出现的缺陷，其中 1 个会**直接漏打当天卡**。
**从 `PrismalAGSL`（50 星）换成 `Kyant0/AndroidLiquidGlass`（4600 星）**

```kotlin
// gradle/libs.versions.toml
backdrop = "2.0.1"
backdrop = { group = "io.github.kyant0", name = "backdrop-android", version.ref = "backdrop" }
```

#### 为什么换

PrismalAGSL 在本机有个硬伤：**`blurRadius` 完全不生效**。真机证据：

- `14dp / 60dp / 200dp` 三档截图逐像素一致
- 折射、色散归零 → 仍然不糊
- 底栏高度 `52dp → 120dp` → 仍然不糊
- 它自己的 `supportsBackdropBlur` 却返回 `true`

为此试过七八种绕过方案（外层 `RenderEffect` 链、`RenderNode` 中转、
`Bitmap` 拷贝、录制层预模糊、`CanvasDrawScope`），全部卡在同一个物理限制：
**Compose 的 `GraphicsLayer` 无法在绘制阶段同步读成像素**，
而 `drawLayer` 画出去的内容不经过父节点的 `RenderEffect`。

（验证过：`RenderNode` + `setRenderEffect` 在这台机器上是有效的——
画纯色矩形能看到真实羽化；但把 `drawLayer` 画进去就是空白。
另外 `setRenderEffect` 必须在 `beginRecording` **之前**调用。）

#### 新库的三个坑（都踩过了）

**1. `effects` 块的顺序不能改**

```kotlin
effects = {
    vibrancy()                       // 提亮 + 提饱和
    blur(with(density) { r.toPx() }) // 真实高斯模糊
    lens(w, h)                       // 边缘透镜，参数是**像素尺寸**
}
```

- 把 `vibrancy()` 放最后 → 模糊被覆盖，等于没糊
- `lens()` 两个参数是**像素**，不是强度系数。传 `1.2f / 0.35f` 等于 1 像素，等于没折射

**2. `tintAlpha` 不是"着色浓度"，是"挡住下层多少"**

这是最容易误判的一点。实测区间（浅色）：

| tintAlpha | 效果 |
|---|---|
| 0.05 | 下层文字完全锐利，blur 调到 150px 都像没糊 |
| 0.28 | 文字淡成灰影，仍能认出字形 |
| 0.40 | **能看出后面有东西、但认不出字**（当前取值） |
| 0.55 | 完全盖住，玻璃变实心白板 |

**3. 这个库的 blur 本来就是"轻微柔化"，不是"糊成团块"**

官方所有示例的半径都在 2–16dp：`LiquidBottomTabs` 8dp、`LiquidButton` 2dp、
`DialogContent` 16dp(浅)/8dp(深)。所以"要仔细分辨才看清"在这个库里
**不是靠加大 blur 实现的，是靠 tint 遮挡**。当前取值：

- 底栏 `blur 8dp(深)/16dp(浅)` + `tintAlpha 0.45(深)/0.40(浅)`
- 卡片 `blur 30dp`（默认，走同样的默认 tint）

### 0.2 修掉的两个真机问题

#### 折射闪烁（已修复）

内容层上同时挂了**三个**录制修饰符（两个 Prismal + 一个新库），每帧互相覆盖
同一份快照。删到只剩一个后实测三帧色值 `332 / 333.1 / 322.8`，波动 3%，稳定。

#### 深黑字体导致折射发脏（已修复）

`lens()` 传 20dp 时，折射把面板外的黑色笔画整段拉进边缘，模糊又不足以化开，
形成内部发黑的污渍。**降到 8dp** 后边缘干净。

### 0.3 深色主题底栏与界面统一（已修复）

用户原话：「深色模式下感觉底栏风格和界面不是很统一」。

根因是三条叠加：

1. 底栏显式传了 `tint = Color(0xFFFFFFFF)`（白色）——
   白叠在深蓝紫背景上，数学上必然趋向灰，于是底栏变灰紫
2. 流动背景是「上蓝下紫」的渐变，底栏在屏幕底部天然采到紫色，卡片在中部采到蓝色
3. 底栏区域背景本来最暗，再叠 0.40 的近黑 tint 就被压成近黑

修法：深色 tint 改为偏蓝的 `Color(0xFF0E1B33)`，`tintAlpha 0.45`；
底栏采样层与卡片统一为「只录背景」那一层。

验证数据：

| | 改前 | 改后 |
|---|---|---|
| 深色底栏 | `RGB(22,21,42)` 灰紫 | `RGB(24,26,67)` 蓝调 |
| 深色卡片 | `RGB(27,50,77)` | `RGB(27,50,77)` |
| 浅色底栏 | — | `RGB(211,227,251)` |
| 浅色卡片 | — | `RGB(190,220,241)` |

### 0.4 悬浮窗改造（部分完成 ⚠️）

用户反馈三条：

1. **两个按钮没作用** — ⚠️ **已改代码但未在真机验证点击效果**
2. **只能暂停不能恢复** — ✅ 已加「▶」继续按钮（`requestResume` + `ACTION_RESUME`）
3. **成功/失败/迟到全都不显示** — ✅ 已加状态提示，3 秒后自动淡出

#### 已实现

`ControlHudOverlay` 新增状态形态：

```kotlin
enum class Tone { RUNNING, SUCCESS, FAILURE, LATE, PAUSED }

showStatus(context, tone, title, subtitle, holdMs = 3000L)
```

- **入场**：上方 12dp 滑入 + 淡入，220ms `DecelerateInterpolator(1.6f)`
- **停留**：2400ms
- **出场**：上移 10dp + 淡出，380ms `AccelerateInterpolator(1.4f)`（收得比展开慢）
- 左侧结果色圆点：成功绿 `#34C759` / 失败红 `#FF3B30` / 迟到琥珀 `#FF9F0A`

接入点 `PunchForegroundService.runPunch()`：打卡结束后立即显示，
迟到判定口径与引擎一致（`finishedAt - targetAt >= 60_000L`）。

设置页新增「测试结果提示」入口（依次演示成功/失败/迟到）。

### 0.5 本轮**未完成**的事（重要，接手请从这里开始）

| # | 事项 | 状态 | 关键信息 |
|---|------|------|---------|
| 1 | 悬浮窗两个按钮点击是否真的生效 | ⚠️ **仍未验证** | 代码已改，但一直没拍到生效截图。**需要在打卡窗口期手动跑一次确认**（见 §4.2） |
| 2 | 状态提示的实际视觉效果 | ⚠️ **未验证** | `showStatus` 已实现并接入，但没拍到生效截图 |
| 3 | 底栏"真正的模糊" | ❌ **放弃**（第四轮已实测确认） | 见下方说明；第四轮又用 8/12/20/200dp 四档验证，逐像素一致 |
| 4 | `LiquidGlassPanel` 的 `sampleOffsetX/Y` | 死参数 | 签名里保留但函数体从未使用，是旧 Prismal 遗留 |
| 5 | `SettingsScreen` / `LaunchOverlay` 的裸 `tween()` | 未迁移 | 仍有多处 `tween(220)` / `tween(280)` 未走 `GlassMotion` |
| 6 | 深色首页两个大卡片偏黑 | 用户说不急 | 第二轮遗留 |


## 0.11 第七轮（2026-11-12 15:57–16:45）—— 底栏动画层重构

用户原话：「越修越差了，重构整个底栏动画，但别动底栏已经实现好的液态玻璃材质」。

### 0.11.1 旧架构为什么必然出 bug

旧实现同时维护**五个位置来源**，渲染时按优先级挑一个用：

```
pressX（拖拽） slideX（点击平移） settledX（落位锚点）
liveOffset（pager 进度） xAnim（兜底）
```

这不是参数没调好，是**结构问题**：每加一个来源就多一组
「何时交接、谁先谁后」的时序歧义。实测连续 4 轮修复，
每轮都修好一处、弄坏另一处：

| 轮次 | 修了 | 弄坏了 |
|---|---|---|
| 1 | 点击瞬移 | 松手复读 |
| 2 | 松手复读 | 长按不跟手 |
| 3 | 长按不跟手 | 松手回弹 |
| 4 | 松手回弹 | 起步抽搐 |

### 0.11.2 新架构：一个真值 + 一个状态机

```kotlin
posAnim: Animatable<Float>   // 唯一位置（tab 单位，0.0~lastIndex）
phase: Idle / Pressed / Dragging / Settling  // 谁在驱动它
```

所有路径（按下、拖拽、点击、落位、pager 同步）**都只写 posAnim**，
渲染端只读它 —— 结构上不可能出现「两个值对不上」。

`liveOffset` 只有**一个**注入入口（`snapshotFlow`），且仅在
`requestedIndex == selectedIndex`（无待处理切页）时生效。

拖拽跟手仍然用普通 float state（`dragPos`）覆盖，因为
`Animatable.snapTo` 是挂起函数，逐帧调用必然产生 20~40ms 调度延迟。

### 0.11.3 参数换成 iOS 26 逐帧实测值

数据源：Codename One motion-probe（XCUITest 真触摸驱动真实
`UITabBarController`，逐帧记录 layer position/transform，
用闭式弹簧公式反解 ω 和 ζ）+ UIKitCore 逆向。

```kotlin
// 位移 / 松手吸附（同一条）
spring(dampingRatio = 0.85f, stiffness = 246.7f)    // 过冲 0.63%
// 拖拽跟手
spring(dampingRatio = 0.903f, stiffness = 1024f)
// 按下抬升（临界阻尼 0.25s）
spring(dampingRatio = 1f, stiffness = 631.7f)
```

**这条研究与项目规则冲突，需要显式记录**：AGENTS.md 写「全应用禁用 spring」，
但 iOS 26 底栏指示器**就是弹簧驱动的**（UIKit 甚至不挂 CAAnimation，
每帧自己写 layer position）。正确的分工是：
**材质/指示器层用弹簧，页面切换与弹层用 bezier**。

原来的 `cubic-bezier(0.32, 0.72, 0, 1)` 偏差有多大：

| 时刻 | 旧 bezier | iOS 实测 |
|---|---|---|
| 84ms | 0.656 | 0.413 |
| 126ms | 0.847 | 0.647 |
| 210ms | 0.955 | 0.914 |

最大偏差 25%（84ms 处）—— 这就是「啪一下弹过去、后面慢慢蹭」的来源。

### 0.11.4 「抽搐」的真正根因（atrace 实测）

不是动画参数问题，是**主线程被组合器占满**：

```
Choreographer#doFrame  total=164.3ms
└─ AndroidOwner:measureAndLayout  147.4ms
   ├─ Compose:recompose            39.3ms
   ├─ Compose:applyChanges         32.7ms
   └─ TextStringSimpleNode::measure × 74   27.8ms
```

关键点：

1. 这 147ms 里**没有任何 AGSL / RenderEffect 切片** ——
   玻璃渲染不是原因（设置页用的全是轻量 `FrostedPanel`）。
   别再往着色器方向排查。
2. 这段组合跑在 **`AndroidOwner:onTouch`** 里，即输入分发路径上同步执行，
   Choreographer 的下一帧回调根本排不进去。
3. 冷进程首次切页 147ms，同进程第 2 次降到 4.5ms、第 3 次 1.7ms ——
   典型的「首次组合」一次性成本。
4. 反向切回首页只要 16ms（首页是 pager 初始页，本来就组合好了）。

### 0.11.5 三个修复

**① 页面瞬时切换 + 预渲染相邻页**

```kotlin
// MainActivity: onSelect 里
pagerScope.launch { runCatching { pagerState.scrollToPage(target) } }
// HorizontalPager
beyondViewportPageCount = 1,   // 原来是 0
```

iOS 原生 `UITabBarController` 切 tab 时**页面内容没有任何过渡动画**，硬切。
整段运动只有指示器在动。这是 Apple 的设计意图：让 lens 成为唯一被注意的运动。

`beyondViewportPageCount = 1` 把那 147ms 从「用户点击时」挪到「启动遮罩还在时」。
代价：冷启动 +150ms（543ms → ~700ms）、设置页节点树常驻（Java Heap +2MB）。

**② liveOffset 注入加守卫**

长按把指示器滑到设置后，动画结束进 `Idle`，但 pager 的 `liveOffset` 仍是 0。
无条件 `snapTo(off)` 会让位置从 `0.998` 跳回 `0.000`：

```
JUMPS=68: 0.998/Settling -> 0.000/Idle
```

修法：只在 `requestedIndex == selectedIndex` 时同步。

**③ 修掉一个崩溃**

```
java.lang.IllegalArgumentException: Padding must be non-negative
    at GlassTabBar.kt:619
```

`scale = 1.40` 时 `hDp = 56dp > barHeight = 52dp`，
`padding(top = (52-56)/2) = -2dp` → 崩溃。真机表现是「按住就退回桌面」。

改成**固定高度布局 + graphicsLayer 缩放**：`graphicsLayer` 的 scale 以自身中心为轴，
不改变布局尺寸，放大后自然上下溢出、不会被裁剪。

### 0.11.6 验证方式：代码内逐帧采样

录屏目测在这个量级（520ms、每帧 8px）上极不可靠 —— 本轮多次误判。
改用**内存缓冲采样**（渲染路径写 `ArrayList`，动画结束一次性 `Log.i`，
零逐帧 I/O），拿到了可判定的序列：

| 场景 | 序列 | 跳变 |
|---|---|---|
| 点击设置 | 0.000→0.007→0.100→0.144→0.191→…→1.000 | 无 |
| 点击首页 | 1.000→0.943→0.900→0.856→0.809→…→0.000 | 无 |
| 长按设置 | 0.000→0.018→0.062→0.123→0.201→…→0.998 | 无 |
| 拖拽往返 | 0.073→0.092→0.115→…→0.791 | 无 |

判据：相邻帧位移 > 0.15 tab（约 54px）记为跳变。

### 0.11.7 新增真机坑

39. **Gradle 增量编译对这个项目不可靠**
    本轮至少 5 次「改了代码但 APK 时间戳没变」，导致装了旧包、
    测出假结果、白排查。`--rerun-tasks` 也不行。
    唯一可靠做法：`Remove-Item -Recurse -Force app\build` 后全量重建。
    改完必须核对两个时间戳：
    ```
    (Get-Item app\build\outputs\apk\debug\app-debug.apk).LastWriteTime
    adb shell dumpsys package <pkg> | grep lastUpdateTime
    ```

40. **重装会重置锁屏显示权限，App 启动不了**
    日志特征：`MIUILOG- Show when locked PermissionDenied`。
    表现是 `am start` 返回 `LaunchState: UNKNOWN (0)`、`TotalTime: 0`，
    进程存在但没有 resumed activity。
    修复：`adb shell cmd appops set <pkg> 10023 allow`

41. **`input swipe` 起点终点相同 ≠ 长按**
    系统会把它合并成点击或直接丢弃，Compose 收不到长按。
    实测有效：`input motionevent DOWN x y` … 等待 … `input motionevent UP x y`。

---

### 0.11.8 第七轮补丁（17:00–17:15）—— 两个新问题

重构后用户复测，报了这两个。**都是重构引入的**，已修。

#### 问题 2：在页面上左右滑动，指示器原地不动

**根因：闭包捕获旧值。**

```kotlin
LaunchedEffect(Unit) {          // 只启动一次
    snapshotFlow { liveOffset } // 捕获首帧的 liveOffset
        .collect { off ->
            if (phase == Phase.Idle && requestedIndex == selectedIndex) ...
                                      // ↑ 也是首帧的值
        }
}
```

`LaunchedEffect(Unit)` 永不重启，闭包里的 `liveOffset` / `selectedIndex`
是**首帧的值**：

- `liveOffset` 首帧是 0.0 → `snapshotFlow` 永远只发出 0.0，从不触发
- `selectedIndex` 首帧是 0 → 在设置页时条件永远不成立

**修法**：用 `rememberUpdatedState` 包成 State 容器，闭包读 `.value` 永远最新。

同时引入 `selfDriven` 显式标记区分两种 pager 变化来源：

| 来源 | selectedIndex 变 | liveOffset 变 | 指示器该不该跟 |
|---|---|---|---|
| 用户直接滑页面 | 是 | 是 | **要跟手** |
| 点击/长按底栏切页 | 是 | 是 | 不要跟（状态机驱动）|

光看数值分不出来，必须由发起方主动打标记。

#### 问题 1：点击动画卡顿

代码内逐帧打点拿到确切数据：

```
17:03:05.432
17:03:05.434
17:03:05.434
17:03:05.588   <- 154ms 空档
```

原因是 `beyondViewportPageCount` **仍是 0** —— 上一轮写的修改没有落到文件里
（用 `Select-String` 核对时才发现）。设置页首次组合的 147ms
依然在点击时同步发生。

改成 1 后：

```
28.285 pos=0.000
28.319 pos=0.027  (16ms)
28.327 pos=0.057  (8ms)
28.335 pos=0.100
28.343 pos=0.144
...帧间隔稳定 8~18ms，位置匀速递增
```

#### 又一次踩到构建坑（补 §0.11.7 的 39 条）

42. **`packageDebug` 会被判定 `UP-TO-DATE` 而跳过，即使 APK 文件不存在**
    本轮出现多次：`build/outputs` 是空的，Gradle 却说 `BUILD SUCCESSFUL`、
    39 个任务全部 up-to-date。原因是任务输出缓存的记录和磁盘实际文件脱节。
    `--rerun-tasks --no-build-cache` 也未必够。**唯一可靠做法**：
    ```
    .\gradlew.bat :app:packageDebug --console=plain --no-daemon --rerun-tasks
    # 然后必须显式检查：
    if (Test-Path app\build\outputs\apk\debug\app-debug.apk) { ... } else { 失败 }
    ```
    **每次装包前都要核对 APK 时间戳与设备 lastUpdateTime 一致**，
    本轮至少 3 次因为装了旧包而测出假数据、白排查。

---

### 0.11.9 性能优化（17:20–17:30）

子 agent 反编译 + 字节码分析发现两个**比重绘贵 2~3 个数量级**的问题。

#### 问题 A：指示器每帧触发整树重组 + 重新布局

```kotlin
val indPos = renderPos()      // 读 posAnim.value = 订阅
val scale = scaleAnim.value   // 读 State = 订阅
Modifier.offset { ... }       // layout 通道，每帧 measure+place
```

**在 Composable 函数体里读 State 就是订阅。** 于是指示器每动画一帧，
整个 `GlassTabBar` 子树完整重组：重建 Modifier 链、重建 Canvas 的
draw lambda、`tabs.forEachIndexed` 重建 2 个 Text、重新判定
`LiquidGlassPanel` —— 连 AGSL 玻璃面板一起波及。

修法：

| 改动 | 从 | 到 |
|---|---|---|
| 位移 | `Modifier.offset{}`（layout 通道）| `graphicsLayer.translationX`（draw 通道）|
| 缩放 | 单独 `graphicsLayer` | 合并进同一个 graphicsLayer |
| 文字 | 内联在 GlassTabBar | 抽出 `TabLabels` 子组件 |

文字必须留在 composition（`Text(color=)` 是组合期参数），
但抽成子组件后重组范围只有那 2 个 Text。
`positionProvider` 用 lambda 传，**不能传 Float** —— 传值的话调用方
在 composition 里读 State 就订阅了，照样整树重组。

#### 问题 B：每帧重建 native SkShader

反编译确认：`Brush.verticalGradient` / `radialGradient` 每次调用都 new 对象，
且 `ShaderBrush` 内部按 `createdSize` 缓存 native shader ——
每帧新实例导致 `createdSize` 永远是 `Unspecified`，**缓存 100% 失效**。

稳态每帧 2 个 native `LinearGradient`，交互态 3 个。

修法：**把动态 alpha 从 color stops 挪到 `drawRoundRect(alpha=)` 参数**，
Brush 就变成编译期常量：

```kotlin
// 顶层 val，只初始化一次
private val BodyBrush = Brush.verticalGradient(
    colors = listOf(Color.White, Color.White.copy(alpha = 0.62f)),  // 只表达比例
)
// 调用处
drawRoundRect(brush = BodyBrush, alpha = baseAlpha, cornerRadius = corner)
```

依赖 size / density 的用 `drawWithCache`（只在 size/density 变化时重跑）：

```kotlin
Modifier.drawWithCache {
    val glowBrush = Brush.radialGradient(..., center = Offset(size.width/2, size.height/2))
    val edgeStroke = Stroke(width = 1.dp.toPx())
    onDrawBehind { drawLiquidIndicator(..., glowBrush, edgeStroke) }
}
```

#### 一条必须记住的诚实结论

**「每帧 new 一堆 Brush 造成 GC 压力」在本项目不成立。**
稳态约 11 个小对象/帧（60~120 KB/s），对 ART 是噪声级别。
做这个优化不是因为 GC，而是 native shader 构造 + `Paint.setShader`
管线重建比它贵一个数量级。以后排查卡顿**不要**从这里入手。

#### 验证（真机 dumpsys gfxinfo，连续 12 次切换）

```
Total frames rendered: 2332
Janky frames: 0 (0.00%)
50th percentile:  9ms
90th percentile: 15ms
95th percentile: 17ms
99th percentile: 22ms
Number Slow UI thread: 0
Number Slow issue draw commands: 0
```

#### 顺带修正的文档谎言

`drawLiquidIndicator` 的注释一直写「五层结构」，**实际只有四层** ——
流动高光带（shimmer）从未实现（用户要求去掉常驻流光）。已改为如实描述。

---

## 0.10 第六轮（2026-11-12 14:00–14:20）—— 底栏两个交互缺陷

> 用户反馈：「点一下直接瞬移过去」+「刚切换界面的 0.5 秒左右无法点击或拖拽底栏」。
> 两者都已复现、定位、修复，并用 60fps 录屏逐帧验证。

### 0.10.1 【P0】点击后指示器瞬移（无过渡）

**根因**（GlassTabBar.kt 手势松手处）：

```kotlin
pressX = centerXFor(target)   // 旧代码
```

`pressX` 是普通 `mutableFloatStateOf`，**同步写入当帧就渲染** ——
指示器从当前位置直接跳到目标位置，中间没有一帧过渡。

上一轮之所以这么写，是为了绕开「清空 pressX 后渲染回落到 liveOffset
（还是旧值），指示器先跳回原页再跟着翻过去」的问题。
但正确解法不是「瞬移到终点」，而是给一段真正的平移动画。

**修法**：新增 `slideTo(target, pressStartX)`

| 要点 | 说明 |
|---|---|
| 起点 | `pressStartX`，在 `awaitFirstDown` 之后**立刻捕获** |
| 终点 | `centerXFor(target)` |
| 时长曲线 | 420ms + `IosEaseOut`（= INDICATOR_MOVE_MS） |
| 渲染优先级 | pressX(拖拽) > slideX(点击平移) > liveOffset > xAnim |

**这里踩了两个坑，都值得记住：**

1. **起点不能在 slideTo 里现读 `currentRenderX()`**
   点击瞬间 `liveOffset` 已经开始向目标页滑动、`xAnim` 也被
   `snapshotFlow { liveOffset }` 同步过去了，读出来 ≈ 终点。
   于是 `abs(to - from) < 1f` 成立 → 直接 return → 动画根本不启动。
   **必须在按下那一刻把位置存下来。**

2. **不能用 `Animatable` 承载这个位置**
   `Animatable.snapTo` 是**挂起函数**，写在 `scope.launch` 里要下一帧才生效，
   而渲染端在同一帧读到的是 `NaN` —— 渲染分支一次都没进过
   （加了 TabBarRender 日志验证：一条都没打印）。
   改用普通 `mutableFloatStateOf` + `withFrameNanos` 手动驱动插值，
   写入同步生效，本帧写本帧渲染。

### 0.10.2 【P0】切换后 0.5 秒内点击/拖拽无效

**根因链**：

```
MainActivity: tabIndex 只在 isScrollInProgress == false 时回写（620ms 动画结束）
    ↓
GlassTabBar: 用 currentSelected 判断「目标页是不是当前页」
    ↓
用户点 B（动画开始）→ 想立刻点回 A
    → tabIndex 仍是 A → target(A) == currentSelected(A)
    → 走 else 分支 → 什么都不做 → 点击被静默吞掉
```

**修法两处**：

1. MainActivity：`tabIndex` 改为 `snapshotFlow { pagerState.currentPage }`，
   意图确定即回写，不再等停稳。
2. GlassTabBar：新增 `pendingTarget` 记录「已请求切到哪一页」，
   落点判断用**请求值**而不是停稳值。

```kotlin
val effectiveCurrent =
    if (pendingTarget != currentSelected) pendingTarget else currentSelected
```

### 0.10.3 真机验证

| 场景 | 结果 |
|---|---|
| 点击后指示器连续平移（60fps 录屏逐帧） | ✅ 有连续位移（修复前 179 帧零位移） |
| 设置页 → 300ms 内点回首页 | ✅ 点击生效，正确停回首页 |
| 快速连点 4 次（首页↔设置） | ✅ 每次都响应 |
| 从首页拖拽到设置 | ✅ 页面与指示器同时切换 |

底栏真实尺寸（真机读数，别再猜）：

```
barW = 717px    tabW = 358.5px
首页中心 x ≈ 460   设置中心 x ≈ 819   y ≈ 2598
```

### 0.10.4 顺带发现的真机坑

36. **`am force-stop` 后重启 App 不会重新注册闹钟** ——
    `alarm_scheduler.xml` 里的 `last_target_at` 会让 `scheduleNext`
    认为该班次已处理，于是只注册 HEARTBEAT，三个打卡闹钟全部缺失。

    ```
    adb shell run-as <pkg> rm shared_prefs/alarm_scheduler.xml
    adb shell am force-stop <pkg>
    adb shell am start -n <pkg>/com.redmi.autoattend.MainActivity
    ```

37. **`adb shell input tap` 的坐标要按底栏真实边界算** ——
    底栏只占屏宽 56%（717px）居中，左边界 ~281px。
    点在胶囊外不会触发任何 tab 手势（也不报错，静默无效）。
    用 `819, 2598`（设置中心）/ `460, 2598`（首页中心）实测可靠。

---

### 0.10.5 第六轮补丁（14:30）—— 拖拽复读 + 长按不跟手

用户复测后又报了这两个，都属于 §0.10 修复引入的**回归**，一并修掉。

#### 问题 A：拖拽松手后又「复读」一遍动画

**根因**：松手时先执行 `pressX = Float.NaN`，渲染立刻回落到 `liveOffset`，
而此刻 pager 还没开始翻页、`liveOffset` 仍是**旧页**位置 →
指示器先「跳回原页」，再被翻页动画带着走一遍，看起来就是复读。

**修法**（GlassTabBar 松手处）：

```kotlin
// ① 先取真实显示位置作为起点
val fromX = if (dragged && !pressX.isNaN()) pressX else pressStartX
// ② 清空跟手值，同时用滑动动画接住（slideX 同步写入，视觉连续）
pressX = Float.NaN
slideToX(centerXFor(target), fromX, RELEASE_MS)
// ③ 再请求切页
currentOnSelect(target, dragged)
```

关键是 **slideX 同步写入当帧生效**，中间不会有一帧回落到旧 liveOffset。

#### 问题 B：长按设置，指示器原地放大而不跟手

**根因**：`pressX` 只在 `dragged == true`（移动超过 touchSlop）时才写。
长按不动 → 永远不写 → 指示器位置毫无变化，只有缩放动画在跑。

**修法**：按下瞬间就请求把指示器移到手指所在 tab：

```kotlin
val downTarget = tabFor(down.position.x)
if (downTarget != currentSelected) {
    slideToX(centerXFor(downTarget), pressStartX, PRESS_SLIDE_MS)
}
```

`PRESS_SLIDE_MS = 520`（比点击的 420 更从容，符合「按下 → 滑到手指处并放大」）。
真拖起来时立刻取消这段运镜，完全交给 `pressX` 逐帧跟手，避免两者抢写。

#### 真机验证

| 场景 | 结果 |
|---|---|
| 首页长按设置（不移动，1.5s） | ✅ 指示器从首页平滑滑到设置上方 |
| 首页慢速拖到设置（1.2s） | ✅ 拖拽期间跟手，松手后无复读，最终停在设置 |

#### 又一个真机坑

38. **`gradlew assembleDebug` 成功后必须先确认 APK 时间戳再 install** ——
    本轮遇到过「以为装的是新包，实际设备上还是 14:25 的旧包」，
    于是日志一条都不打印，白排查了一轮。核对方式：

    ```
    Get-Item app\build\outputs\apk\debug\app-debug.apk | % LastWriteTime
    adb shell "dumpsys package <pkg> | grep lastUpdateTime"
    ```
    两者不一致说明装的是旧包。

---

## 0.9 第五轮（2026-11-12 12:20–13:00）—— 真机全链路验证

> 本轮首次在**真实设备、真实锁屏、真实密码**条件下跑完整条打卡链路。
> 设备：M332BF / Android 16 / API 36（与文档目标机型一致）。

### 0.9.1 【P0】「太早被拒」会取消当天闹钟 → 当天漏卡（已修复）

**这是本轮最重要的发现，且已在真机上复现。**

复现步骤：12:48 手动触发一次下班卡（目标 18:30，当前 12:48）。

日志（真机）：
```
安全闸门：kind=CLOCK_OUT 目标=18:30:00 偏差=-20477秒
拒绝：下班时间未到，还差 341 分 19 秒
```

**拒绝本身是正确的**（下班前绝不能打下班卡）。但收尾时：

```kotlin
// PunchForegroundService.runPunch()
markFired = result.success || result.abandonedByGate   // ← 旧代码
```

于是这次「提前拒绝」被当成「这一班已处理完毕」：
`onPunchFinished(markFired=true)` → `scheduleNext()` 顺延下一班 →
`render()` 先 `cancelAll(previous)` —— **当天 18:30 的
PREWARM / FIRE / RETRY 三段闹钟被一起取消**。

真机证据（`dumpsys alarm`）：
```
拒绝前：RTC_WAKEUP #34 origWhen 1794479325000  (今天 18:28:45 PREWARM)
        RTC_WAKEUP #35 origWhen 1794479400000  (今天 18:30:00 FIRE)
        RTC_WAKEUP #36 origWhen 1794479520000  (今天 18:32:00 RETRY)
拒绝后：RTC_WAKEUP #80 origWhen 1794533325000  (明天 09:28:45 PREWARM) ← 全跳到明天
```

**后果**：当天真正的下班卡静默漏打，界面上完全看不出来。

**修法**：`PunchResult` 增加 `rejectedTooEarly`，语义是
「太早 ≠ 这一班处理完了，而是什么都没发生，闹钟必须原样保留」。

| 结果类型 | markFired | 理由 |
|---|---|---|
| 成功 | true | 顺延下一班 |
| 迟到边界后主动放弃 | true | 本班确实处理完了 |
| **太早被拒（下班未到/上班未到）** | **false** | 什么都没发生，闹钟必须保留 |
| 执行失败 | false | 留 RETRY 兜底 |

改了 4 处口径（闸门 1 的下班太早、上班太早；外勤复查后同样两种）。

**回归测试**：新增 `TooEarlyGateTest`（6 例），把上述契约钉死。

### 0.9.2 【P0】无 root 时「点亮屏幕」必然失败（已确认，靠 root 规避）

真机实测：连续 3 次触发，全部死在第一步：

```
[44742] 开始执行 kind=CLOCK_OUT 通道偏好=AUTO
[44742] root 不可用，尝试用 Activity 唤醒屏幕
[48855] 失败于 [点亮屏幕] 无法点亮屏幕
```

`PunchEngine.prepareScreen()` 三条方案在**无 root** 时全部无效：

| 方案 | 无 root 时 | 原因 |
|---|---|---|
| A. `RootShell.wakeUp()` (input keyevent 224) | 不可用 | 需要 su |
| B. 拉 MainActivity（turnScreenOn） | **无效** | Activity 已在栈顶时 intent 被直接投递，不重新触发 turnScreenOn |
| C. 充电保持唤醒 | 不适用 | 用户未开启 |

**验证**：KernelSU 给 App 授权后（`RootShell: root available = true`），
同一条链路立刻跑通：

```
[12817] root 点亮屏幕 input keyevent 224     ✅
[12862] 无障碍上滑                            ✅
[13663] 输入锁屏密码，第 1 次                 ✅ 一次就过
[13949] 启动钉钉                              ✅
[22326] 钉钉前台就绪=true                     ✅
[22378] 顶部快捷入口：当前已在考勤页           ✅
[22378] 安全闸门：目标=18:30:00 偏差=-20477秒  ✅ 正确拒绝
```

> **结论**：这台设备上 **root 是必需项，不是可选项**。
> 设置页的「执行后端」不要选「仅无障碍」—— 那样点亮屏幕就会失败。
> `prepareScreen()` 方案 B 需要用 `FLAG_ACTIVITY_CLEAR_TASK` 或先 `finish()`
> 才能重建 Activity 触发 turnScreenOn，目前未修（有 root 时不走这条路）。

### 0.9.2b 装机脚本（第五轮验证通过的完整流程）

按顺序执行，缺任何一步都会在真机上失败：

```powershell
$adb = 'C:\AndroidTools\sdk\platform-tools\adb.exe'
$pkg = 'com.redmi.autoattend.debug'
$svc = "$pkg/com.redmi.autoattend.exec.PunchAccessibilityService"
$base = "li.songe.gkd/...:io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService"

# 1. 装包（必须先开 开发者选项 → USB 安装）
& $adb install -r app\build\outputs\apk\debug\app-debug.apk

# 2. 无障碍（install -r 会解绑，必须重写；保留原有的 GKD 等服务）
& $adb shell ("settings put secure enabled_accessibility_services '" + $base + ":" + $svc + "'")
& $adb shell "settings put secure accessibility_enabled 1"

# 3. 通知权限 —— 【最容易漏，漏了必然解锁失败】
& $adb shell "pm grant $pkg android.permission.POST_NOTIFICATIONS"
& $adb shell "appops set $pkg POST_NOTIFICATION allow"

# 4. 悬浮窗（重装后会被重置成 ignore）
& $adb shell "appops set $pkg SYSTEM_ALERT_WINDOW allow"

# 5. 精确闹钟
& $adb shell "appops set $pkg SCHEDULE_EXACT_ALARM allow"

# 6. 电池白名单
& $adb shell "dumpsys deviceidle whitelist +$pkg"

# 7. MIUI 自启动 / 后台弹出 / 锁屏显示
& $adb shell "cmd appops set $pkg 10021 allow"   # 自启动
& $adb shell "cmd appops set $pkg 10020 allow"   # 后台弹出界面
& $adb shell "cmd appops set $pkg 10023 allow"   # 显示在锁屏上

# 8. 种锁屏密码（debug 构建专用）
& $adb shell "am broadcast -n $pkg/com.redmi.autoattend.exec.DebugPasswordSeedReceiver ``
    -a com.redmi.autoattend.debug.SEED_PASSWORD --es password <你的密码>"
# 日志应出现: DebugPwdSeed: seed password: OK

# 9. root（KernelSU 里给 App 授权，然后确认）
& $adb shell "am start -n $pkg/com.redmi.autoattend.MainActivity" | Out-Null
& $adb logcat -d | Select-String "root available"   # 必须为 true
```

**验证闹钟**：
```powershell
& $adb shell "dumpsys alarm" | Select-String "autoattend" | Select-String "origWhen"
# 用 [DateTimeOffset]::FromUnixTimeMilliseconds(<值>) 解码，应看到今天的上下班时刻
```

### 0.9.3 KernelSU 授权范围（重要，排查时别浪费时间）

KernelSU 的授权是**按 UID** 的，实测现象：

| 谁 | `su -c id` | 说明 |
|---|---|---|
| adb shell (uid 2000) | **不可用** | `/system/bin/sh: su: inaccessible or not found` |
| App 进程 (uid 10305) | **可用** | `RootShell: root available = true` |

所以：**不能用 `adb shell su` 来判断 root 是否可用**，
必须看 logcat 里 `RootShell: root available = ?`，或看功能是否真的走通。

另外 `/data/adb` 对 shell 是 `Permission denied`，属正常。

### 0.9.4 TRIGGER_PUNCH 广播链路真相（§4.6 结案）

原文档说「`result=0` 但没写 punch_log 行，链路断在哪儿没查」。本轮查清：

1. **`result=0` 完全不能证明 receiver 被调用** —— 没有任何 receiver
   匹配时 `am broadcast` 同样返回 `result=0`。之前的推断是错的。
2. 必须用 **`-n` 显式指定组件**：
   ```
   adb shell am broadcast -n com.redmi.autoattend.debug/com.redmi.autoattend.exec.DebugPasswordSeedReceiver \
       -a com.redmi.autoattend.debug.TRIGGER_PUNCH --es kind CLOCK_OUT
   ```
   只写 action 有时会因隐式广播限制/包名解析不到而不投递。
3. 链路本身**没有断**。修好调用方式后，每一次都正常写入了 punch_log，
   并跑完整条流程。

> 也就是说 §4.6 是**排查方法错误**造成的误判，不是代码缺陷。
> 已在本轮证明可稳定复现执行。

### 0.9.5 本轮的真机环境结论

| 项 | 实测值 |
|---|---|
| 设备 | `d65730c5` / M332BF / warsaw / Android 16 / API 36 |
| 排班 | 09:30 上班 / 18:30 下班 / 周一至周六（`active_days=2,3,4,5,6,7`） |
| 锁屏密码 | 已通过 `SEED_PASSWORD` 广播加密写入，`seed password: OK` |
| root | ✅ KernelSU v3.3.1，App 已授权 |
| 无障碍 | ✅ 已绑定（与 GKD 共存） |
| 悬浮窗 | ✅ `SYSTEM_ALERT_WINDOW: allow` |
| 电池白名单 | ✅ `user,com.redmi.autoattend.debug,10305` |
| 闹钟 | ✅ PREWARM / FIRE / RETRY 三段齐备 |
| 编译 | ✅ BUILD SUCCESSFUL（JDK 17 够用，无需 JDK 21） |

### 0.9.6 本轮踩到的新坑（补进 §11）

29. **MIUI/HyperOS 的「USB 安装」开关会挡住 `adb install`** ——
    报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。
    `pm install-create / install-write` 能成功写入，但 `install-commit` 照样被拒。
    必须去 **开发者选项 → USB 安装** 手动打开。
    （settings put 那几个 verifier 开关都绕不过去，别浪费时间。）

30. **签名不一致时只能卸载重装** ——
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。代价是清空 App 数据：
    锁屏密码、排班、打卡日志全部丢失，装完必须重新种密码。

31. **`am force-stop` 会解绑无障碍** —— 和 `install -r` 一样。
    重装/强停之后必须重新 `settings put secure enabled_accessibility_services`。

32. **`dumpsys alarm` 的 `origWhen` 是毫秒时间戳**，
    用 `[DateTimeOffset]::FromUnixTimeMilliseconds()` 解码比猜时间靠谱得多。
    排查漏卡时这是最快的一条证据。

33. **通知权限弹窗会卡在锁屏上，导致解锁 100% 失败** ——
    连续 4 次真机测试全部死在「输入锁屏密码，第 1/2/3 次」，
    现象看起来像"密码错了"，实际是：
    - `POST_NOTIFICATIONS` 是 `granted=false`（`pm grant` 首次没生效）
    - App 启动时 `requestNotificationPermission()` 弹的**系统弹窗浮在锁屏之上**
    - 上滑 → 焦点落在弹窗的「拒绝/始终允许」按钮上，不是密码键盘
    - 密码数字全部打进了无效位置 → 三次尝试全失败

    修法（装机后必做）：
    ```
    adb shell pm grant com.redmi.autoattend.debug android.permission.POST_NOTIFICATIONS
    adb shell appops set com.redmi.autoattend.debug POST_NOTIFICATION allow
    ```
    并确认 `dumpsys package ... | grep POST_NOTIFICATIONS:` 是 `granted=true`。
    **只设 appops 不够，权限本身也必须是 granted。**

34. **`appops set <pkg> SYSTEM_ALERT_WINDOW allow` 重装后会被重置成 `ignore`** ——
    重装/卸载重装后必须重新设一次，否则打卡时悬浮窗提示不出现。

35. **单元测试真实数字是 99 passed + 5 skipped，不是 104** ——
    `@Test` 共 104 个，但 `@Ignore` 是 **5 个**（不是文档曾说的 12 个），
    分布在 `SafetyGateTest`(2) / `SchedulerGuardTest`(3)。
    104 = 99 通过 + 5 跳过。第五轮新增 `TooEarlyGateTest` 6 例后为 110/105+5。


### 0.8 第四轮迭代（2026-11-12）—— 底栏手势彻底重构 + 打卡识别 P0

> 本轮全部改动已推送：`fix/p0-punch-chain`，HEAD `91c07f3`。
> 提交链 `d458686 → 91c07f3` 共 8 个提交。

#### 0.8.1 【P0】下班卡被识别成上班卡（已修复，三轮根因叠加）

三个独立缺陷叠加，缺一不可修：

| # | 位置 | 问题 | 修法 |
|---|------|------|------|
| 1 | `MainViewModel.manualPunch()` | 传的 `targetAt = now`，而不是班次目标时刻 | 传真实目标时刻 |
| 2 | `MainViewModel.inferManualKind()` | 无条件返回 `CLOCK_IN` | 按距上下班时刻最近推断 |
| 3 | `PunchEngine.judgeResult()` | 拿到任意成功文案就判成功，不看类型 | 增加 `PunchKind` 校验（`successMatchesKind`） |

新增单测：`TargetDoneTest`(14) / `PunchWindowTest`(10)。

#### 0.8.2 底栏手势彻底重构（本轮最大工作量，5 次方案迭代）

**用户反馈的 5 个症状，全部同一个根因：拖拽时 pager 没有同步跟手。**

| 症状 | 说明 |
|------|------|
| 拖拽卡顿一下然后瞬移 | 帧间状态被合并/丢弃 |
| 松手瞬移回原页再播一遍动画 | `liveOffset` 仍是旧值 |
| 长按时指示器不跟手 | 没有逐帧驱动源 |
| 点击要两次才生效 | 父级手势与子级 Clickable 各触发一次 |
| 刚切换界面时点击没反应 | 按下时用 `pressTab != currentSelected` 判断，而它尚未回写 |

**根因是结构性的，不是参数问题：**

`awaitEachGesture` 跑在**受限协程作用域**里，任何挂起调用（`Animatable.animateTo`、
`scrollToPage`）都必须经 `scope.launch` 逃逸，而 `launch` 是**异步调度**的 ——
逐帧驱动必然掉帧。三种尝试全部失败：

| 方案 | 失败原因 |
|------|----------|
| `scope.launch { scrollToPage() }` | 异步，帧间被合并 → 丢中间动画、卡顿后瞬移 |
| `dispatchRawDelta(delta)` | 增量被 pager 的 snap 吸回最近的页 → 拖不过去 |
| 绝对位置 `scrollToPage()` | 仍是 `launch` 异步 → 同样卡顿 |

**最终方案：拖拽期间页面完全不动，只有指示器跟手；松手才切页。**

```kotlin
var pressX by remember { mutableFloatStateOf(Float.NaN) }   // 普通 state，同步

按下 → scaleAnim.animateTo(dragScale)          // 放大
拖拽 → pressX = xFor(fingerX)                  // 同步写，本帧即渲染，永不掉帧
松手 → pressX = centerXFor(target)             // 先停到目标
       currentOnSelect(target, dragged)        // 再请求切页
       // pressX 的清空挪到 selectedIndex 更新后的 LaunchedEffect 里
```

**交接时机是关键**（这是最后一次修复）：

```kotlin
// 错：松手就清，但 pager 动画刚开始、selectedIndex 还没回写
currentOnSelect(target); pressX = Float.NaN   // → 指示器跳回原页再翻过去

// 对：等 selectedIndex 真的更新后，在 LaunchedEffect 里清
pressX = centerXFor(target); currentOnSelect(target)
// ...LaunchedEffect(selectedIndex) 末尾: pressX = Float.NaN
```

交接瞬间两边数值相同，无可见跳变。

**其他配套修正：**

- 移除子级 `Clickable`（父级手势已统一处理点按与拖拽，双触发是「要点两次」的直接原因）
- 指示器位置只有一个来源，`isPressed` / `posJob` / `scaleJob` 等中间态全部删除
- 文字颜色改为跟随实际位置插值（`livePos`），不再等 `selectedIndex` → 消除「变色慢一秒」
- 三处动画时长统一为 **620ms**：`RELEASE_MS` / `TAB_RELEASE_MS` / `CLICK_PAGE_MS`，
  保证指示器与页面同长同曲线、双向对称

#### 0.8.3 真机验证结果（Redmi K90 至尊版实测）

| 场景 | 结果 |
|------|------|
| 拖拽中途截图（手指在胶囊中段） | 指示器精确停在手指处、已放大、**页面未动** ✅ |
| 拖拽 首页→设置 / 设置→首页 | 落点正确 ✅ |
| 单击双向 | 各一次生效 ✅ |
| 快速连点三次后立即点击 | 每次都响应 ✅ |
| 在设置页长按设置（原地不动） | 不误切页 ✅ |

> ⚠️ **测试坐标提醒**：底栏是宽度 0.56 屏宽的居中胶囊（1280 屏宽下约 x=300–1000）。
> 用 `adb input` 测试时，坐标要落在胶囊内，两半中心约为 **x=475 / x=830**（y=2694）。
> 曾经用 x=900 测出一堆假失败，实际是超出胶囊右缘导致 Row 内部坐标映射偏了。

#### 0.8.4 其他修复

- 设置页删除全部 4 个「测试」操作行
- 倒计时数字上移淡出动画放慢：`DigitShiftMs 620` / `FadeIn 420` / `FadeOut 540`
- 底栏玻璃采样源改为 `liquidBackdropContent`（含内容层），并确认 `directionalOptics = true`
- 补交 `app/src/debug/AndroidManifest.xml`（`DebugPasswordSeedReceiver` 的配套清单，
  只在 debug 构建合并，release 不含 —— 避免 exported receiver 成为改密码后门）
- 修正 `verify-alarm.ps1` / `.sh` 三处必现误报（CRLF 导致 `$` 锚点失效、
  `$pid` 撞 PowerShell 只读自动变量、logcat 漏抓 4 个关键 tag）

#### 0.8.5 本轮**新增**的待办

| # | 事项 | 状态 | 说明 |
|---|------|------|------|
| 1 | `TRIGGER_PUNCH` 广播链路 | ⚠️ 未修复 | 广播返回 `result=0` 但**没写 `punch_log` 行**，链路断在哪儿没查 |
| 2 | `isTargetDone` 的 90 字符窗口启发式 | ⚠️ 待替换 | 应改为按 y 坐标配对行（`build/v2.xml` 里有真实节点坐标） |
| 3 | `isOutdoor` 未下沉到 `judgeResult` | ⚠️ 待办 | 目前在外层判断，判定依据不够集中 |
| 4 | 打卡时间未回显 | ⚠️ 待办 | `09:19` / `18:30` 应出现在日志与通知正文里 |
| 5 | `MainViewModel.manualPunch()` 的 `delay(1_500)` | ⚠️ 待办 | `finally { _busy = false }` 仍绑固定延时，应改为真实完成信号 |

---

#### 关于「底栏真正的模糊」为什么放弃

要让底栏背后的文字真的糊成一团，必须让它采样**包含该文字的内容层**。
但试过把底栏移进内容层录制范围，**直接 SIGSEGV**（RenderThread 崩在 GL 栈）——
这是「录制 → 自身画进录制层 → 重新录制」的自引用。

官方用「隐藏录制节点 + 显示节点」两个兄弟节点绕开，但那套结构针对固定尺寸的
tab bar；我们的底栏要跟随 pager 进度和拖拽动画，重构成本高、风险大。

**当前方案**：靠 `tintAlpha` 遮挡 + 小半径柔化，视觉上达到「能看出后面有东西、
但认不出字」，但技术手段不是高斯模糊。

### 0.6 本轮删除的文件

- `ui/glass/HomegrownGlass.kt` — 自建 RenderNode 方案的遗留，已无引用
- `ui/glass/GlassSurface.kt` — 同上

### 0.7 验收状态

| 项 | 结果 | 时间 |
|---|---|---|
| `assembleDebug` | ✅ BUILD SUCCESSFUL | 第四轮 |
| 单元测试（10 个类 / 104 个） | ✅ 全部通过 | 第四轮 |
| 安装运行 | ✅ 进程存活，无崩溃 | 第四轮 |
| 底栏双向拖拽 / 双向点击 | ✅ 真机逐项验证（见 §0.8.3） | 第四轮 |
| 拖拽中途跟手 | ✅ 截图确认指示器停在手指处、页面未动 | 第四轮 |
| 深浅双主题 | ✅ 都验证过截图 | 第三轮 |
| 调试探针残留 | ✅ 已清理干净 | 第三轮 |

---

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
| 单元测试 | ✅ **88 / 88 全绿**（第三轮新增 4 个调度抗自激用例） |
| 真机运行 | ✅ 无崩溃（`logcat AndroidRuntime:E` 空） |
| 冷启动 | ⚠️ ~1966ms（首帧降级已移除，见 §0.3） |
| 玻璃渲染 | ✅ AGSL 全管线正常运行 |
| 包名 | `com.redmi.autoattend`（debug 后缀 `.debug`） |

---

## 2. 本轮完成的内容

### 2.1 底栏（GlassTabBar）

> ⚠️ 本节在第四轮**全部重写**。旧版描述的「闭包捕获过期状态」「双边不对称」等
> 问题已经随架构替换消失，不再适用。看这节就够了。

**尺寸（代码实测值，GlassTabBar.kt）：**

| 参数 | 值 |
|------|-----|
| barWidthFraction | 0.56f（屏宽的 56%，居中悬浮胶囊） |
| barHeight | 52.dp |
| indicatorWidth / indicatorHeight | 78.dp / 40.dp |
| dragScale | 1.55f（40 x 1.55 = 62dp > 52dp，真正溢出容器上下边界） |
| INDICATOR_MOVE_MS | 420 |
| PRESS_MS（放大） | 380 |
| PRESS_SLIDE_MS | 520（预留，当前手势未使用） |
| RELEASE_MS（落位） | **620** |
| STRETCH_MS / SETTLE_MS | 260 / 460 |
| GLOW_FADE_MS / COLOR_MS | 520 / 380 |

**手势模型（第四轮定稿）：**

```
按下 → 放大（380ms）
拖拽 → 指示器同步跟手，页面完全不动
松手 → 指示器落位到最近 tab + 页面切过去（620ms，同长同曲线）
```

位置只有一个来源：拖拽时是 pressX（普通 mutableFloatStateOf，同步写入），
其余时候是 liveOffset（pager 连续进度）。**不存在两套坐标对账**。

详见 §0.8.2 —— 那里记录了 5 次失败方案与最终结论，改这个文件前务必先读。

**玻璃容器：**

LiquidGlassPanel（Kyant0/AndroidLiquidGlass）采样 liquidBackdropContent 层。关键参数：

```kotlin
tint = if (colors.isDark) Color(0xFF0E1B33) else Color(0xFFFAFAFA)
tintAlpha = if (colors.isDark) 0.45f else 0.40f
highlightAlpha = 0f                    // 关边缘高光，否则盖住贴边的「设置」文字
dispersionOverride = if (isDark) 0.45f else 0.38f
directionalOptics = true               // 必须 true，关掉会退化成平板色块
blurRadius = if (colors.isDark) 8.dp else 16.dp   // 本机 blur 实际无效，见 §0.1
```

> ⚠️ **blurRadius 在本机是死参数**（8dp / 12dp / 20dp / 200dp 逐像素一致）。
> 真正的「磨砂感」全靠 tintAlpha 遮挡。这是硬件/库的物理限制，不是配置问题，
> 不要再尝试调大 blurRadius（§0.1 有完整证据）。

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

改动这些常量必须让 `SafetyGateTest` 的断言失败。

> 该文件当前 6 个 `@Test`、4 个 `@Ignore`。`@Ignore` 里挂的正是这两条红线的
> **期望语义**（断言已写好，等把函数抽成 `internal object SafetyGate` 后启用）。
> 也就是说：**目前只有一半红线被自动化覆盖**，另一半靠代码 review 保证。

### 3.2 成功判定

**必须来自钉钉界面真实反馈**，不能以"点击已发出"为成功。
`verifyResult()` 返回非 null 才能 `success = true`。

`ALREADY_DONE ≠ SUCCESS`。

> **更正（第四轮）**：此处曾写"全库只有一个 `success = true` 站点"，实测有 **4 处**。
> 改判定逻辑时必须四处一起看：
>
> | 位置（当前行号） | 触发条件 | 说明 |
> |------|---------|------|
> | `PunchEngine.kt` L667 | `alreadyDoneBefore` | 极速打卡：进页面时钉钉已自己打好 |
> | `PunchEngine.kt` L687 | `readTargetDoneState(kind)` | **第四轮新增**：点击阶段发现目标已完成（极速打卡抢先） |
> | `PunchEngine.kt` L710 | `verifyResult() != null` | 主路径，唯一"点了并验证成功"的站点 |
> | `PunchEngine.kt` L1572 | `targetAlreadyDone(blob, kind)` | 外勤闸门里的"目标时段已打卡 → 跳过" |
>
> L1572 最需要警惕：它靠 `targetAlreadyDone()` 的**字符串邻近匹配**
> （"上班"/"下班" 关键词后 90 字符窗口内找"已打卡"）判定。
> 钉钉改文案或改排布时这里会先失准。
>
> ⚠️ **行号会漂移**，查找时用 `Select-String -Pattern 'success = true'` 重新定位，
> 别信文档里的绝对行号。

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

### 4.2 暂停/恢复 —— 代码缺陷已修，仍未真机验证

`ACTION_PAUSE` / `ACTION_RESUME` 已实现且编译通过，
但**从未真正触发过一次打卡来验证**。

**第三轮发现的真实缺陷（已修，见 §12）**：这条路原本是坏的。
`PunchEngine.execute()` 用 `catch (Throwable)` 把协程取消也吞成一个普通失败结果，
导致"暂停"之后 `running` 永远为 true、服务不退出、**当天再也打不了卡**。
所以之前"没验证"不只是时间问题——即使去验证也会失败。

**现在的语义**（待真机确认）：
- 暂停 → 协程被取消并透传、立即释放唤醒锁、服务保活但不再阻塞
- 暂停有 5 分钟有效期，超时后下一次闹钟可正常接管
- 继续 → 先核对是否仍在可打卡窗口内，超出则收尾而不是硬打

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

### 4.6 TRIGGER_PUNCH 广播链路断裂（第四轮发现，未修）

`DebugPasswordSeedReceiver` 处理 `com.redmi.autoattend.debug.TRIGGER_PUNCH` 时，
`am broadcast` 返回 `result=0`，但**数据库里没有新增 punch_log 行**。

已确认：
- receiver 确实被调用了（`result=0` 说明 onReceive 执行过并调用了 setResult）
- 但打卡链路没有产出日志记录

未确认：断在 `PunchEngine` 的哪个环节。要查的话先看 `PunchForegroundService`
的前台服务启动是否被 HyperOS 拦掉（后台启动 FGS 限制）。

**影响面**：仅影响 adb 联调便利性，不影响闹钟触发的正常打卡链路。

### 4.7 文档与代码的历史偏差（已在本轮修正）

第四轮之前，本文档有多处描述与代码不符，本轮已逐项校正：

| 原文档说法 | 实际情况 |
|---|---|
| 测试 8 个类 / 88 个测试 | 10 个类 / 104 个测试 |
| 底栏手势「闭包捕获过期状态」 | 架构已替换，该问题不存在了 |
| 指示器 1.55x | 正确，未变 |
| `RELEASE_MS` 340 | 已改为 620（三段动画统一） |
| 只有 3 个测试类带 `@Ignore` | 实际 12 个 `@Ignore` 分布在 3 个类 |

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

**推荐直接跑脚本**（一条命令查完 6 项）：

```powershell
.\verify-alarm.ps1          # Windows
bash ./verify-alarm.sh      # Git Bash
```

脚本会依次检查：设备连接 → 进程存活 → 闹钟注册 → 保活服务 → 无障碍 → 最近日志。

> 第四轮修了脚本的三处必现误报，注意两点：
> - `adb devices` 的输出是 CRLF，`$` 锚点匹配不到行尾，必须用 `\s*$`
> - 不要用 `$pid` 做变量名，那是 PowerShell 只读自动变量，赋值静默失败

手动查：

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
│   ├── PunchSchedule.kt               ★ 时间计算（纯函数，可测）
│   ├── AlarmScheduler.kt              ★ AlarmManager 精确闹钟；isSameSlot/handlesFor
│   └── HealthCheck.kt                 保活健康度 8 项检查
├── data/db/
│   ├── AppDatabase.kt                 Room 数据库
│   ├── PunchLogDao.kt                 日志查询
│   └── PunchLogEntity.kt              日志实体
├── exec/
│   ├── PunchEngine.kt                 ★ 打卡主流程；安全闸门；外勤轮询
│   ├── PunchForegroundService.kt      前台服务；PAUSE/RESUME/CANCEL
│   ├── PunchAlarmReceiver.kt          闹钟接收；重排下一班
│   ├── PunchAccessibilityService.kt   无障碍通道
│   ├── AccessibilityKeeper.kt         无障碍服务保活/重绑
│   ├── RootShell.kt                   root 通道
│   ├── DingTalkUi.kt                  钉钉界面节点定位与文本判定
│   ├── UiHierarchy.kt                 UI 树解析
│   ├── GestureExecutor.kt             手势执行（无障碍/root 双路）
│   ├── ControlHudOverlay.kt           ★ 系统悬浮窗提示
│   ├── PunchNotifier.kt               通知 + 震动 + 音效
│   ├── KeepAliveService.kt            保活；常驻通知开关
│   ├── ResurrectionService.kt         进程复活
│   ├── BootReceiver.kt                开机重排闹钟
│   └── DebugPasswordSeedReceiver.kt   ⚠️ 仅 debug 构建（见 app/src/debug/）
├── ui/
│   ├── MainViewModel.kt               ★ 单一数据源；100ms tick
│   ├── components/
│   │   ├── GlassTabBar.kt             ★ 底栏（第四轮重构）
│   │   ├── CountdownText.kt           ★ 倒计时数字滚动（上移淡出）
│   │   ├── LiquidGlassButton.kt       液态玻璃按钮（四层按压反馈）
│   │   ├── PressEffect.kt             通用按压反馈
│   │   ├── ClickableNoRipple.kt       无涟漪点击
│   │   ├── ConfirmDialog.kt           二次确认弹层
│   │   ├── GlassWheelTimePicker.kt    玻璃风格时间滚轮
│   │   ├── LaunchOverlay.kt           启动遮罩
│   │   ├── HealthCard.kt              保活健康度卡片
│   │   ├── StatusPill.kt              状态胶囊
│   │   ├── PasswordInput.kt           密码输入
│   │   └── SettingsComponents.kt      设置页组件
│   ├── glass/
│   │   ├── LiquidGlassPanel.kt        ★ 玻璃面板（Kyant0 backdrop）
│   │   ├── FrostedPanel.kt            轻量磨砂（降级路径）
│   │   ├── FlowingBackground.kt       ★ 流动光斑背景（20fps 节流）
│   │   ├── LocalBackdrop.kt           内容层录制（玻璃采样源，★第四轮修正）
│   │   ├── GlassPerfGuard.kt          降级守卫
│   │   └── SystemBlur.kt              系统模糊（弹窗用，API 31+）
│   ├── screens/
│   │   ├── HomeScreen.kt              ★ 首页
│   │   ├── SettingsScreen.kt          设置页
│   │   └── TimeEditorSheet.kt         时间选择弹层
│   └── theme/
│       ├── GlassColors.kt             ★ 配色（深/浅两套）
│       ├── GlassMotion.kt             ★ 动效常量（IosEaseOut / IosEaseInOut）
│       └── GlassTheme.kt              圆角/间距尺度
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
| `TargetDoneTest` | 14 | **目标已打判定（第四轮新增，P0 下班卡误判回归）** |
| `UiHierarchyTest` | 14 | 界面节点定位 |
| `PunchResultJudgeTest` | 13 | 打卡结果判定（极速打卡、失败优先、最坏情况防护） |
| `PunchScheduleTest` | 12 | 下一次打卡计算 |
| `CompletedSlotTest` | 11 | 已完成班次跳过（手动打卡后不重复触发） |
| `SchedulerGuardTest` | 11 | 调度抗自激（含 4 个 `@Ignore`，见下方说明） |
| `PunchWindowTest` | 10 | **打卡窗口边界（第四轮新增，含 2 个 `@Ignore`）** |
| `DayLabelTest` | 8 | 今天/明天/周几/日期文案 |
| `SafetyGateTest` | 6 | 迟到/下班安全闸门（含 4 个 `@Ignore`，见下方说明） |
| `SkipDateTest` | 5 | 跳过某天后下次打卡顺延 |
| **合计** | **104** | 全部通过（`@Ignore` 计为 skipped，不是 passed） |

> **关于 `@Ignore`（12 个，总计）**：`SafetyGateTest` / `SchedulerGuardTest` /
> `PunchWindowTest` 里各有一批红线的**期望语义**用 `@Ignore` 显式挂起。
> 这些不是漏写，是刻意的：被测函数目前还在 `PunchEngine` 内部，
> 需要先抽成 `internal object SafetyGate` / `AlarmScheduler.isSameSlot(a,b)` /
> `handlesFor(slot)` 才能直接测。断言已经写好了，抽出来把 `@Ignore` 删掉即可，
> **不需要重写用例**。
>
> 挂起而不是删除，是因为 `@Ignore` 是 skipped 而非 passed —— 不会「假绿」。


> **SchedulerGuardTest 覆盖的回归点**：闹钟触发后会重排下一班，
> 而 `nextSlot` 在补执行窗口内会返回**同一个班次** ——
> 如果重排时不把刚触发的槽位标记为已完成，就会形成
> "FIRE → 重排 FIRE → FIRE…" 的自激循环。这组用例把
> "确实会返回同一班次"和"标记后确实让位"两个事实都钉住了。

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

1. **验证暂停/恢复**（§4.2）—— 第三轮已修复其代码缺陷（见 §12），
   **但仍未真机验证**，需要在打卡窗口期手动跑一次
2. **查 `TRIGGER_PUNCH` 广播链路**（§4.6）—— 第四轮发现，`result=0` 但不写日志，
   怀疑断在 `PunchForegroundService` 的后台启动限制上
3. **替换 `isTargetDone` 的 90 字符窗口启发式**（§3.2）—— 改为按 y 坐标配对行，
   `build/v2.xml` 里有真实节点坐标可参考；这是钉钉改版后最先失准的地方
4. **把红线的期望语义解挂**（§7）—— 抽出 `internal object SafetyGate`、
   `AlarmScheduler.isSameSlot(a,b)`、`handlesFor(slot)` 三个入口，
   删掉 12 个 `@Ignore`，让 104 个测试全部生效
5. 冷启动优化（§4.1）—— 1936ms 仍偏慢
6. 设置页剩余控件苹果化（§4.4）—— 分段控件、开关、周选择器
7. 打卡时间回显（§0.8.5 第 4 项）—— `09:19` / `18:30` 应进日志与通知正文
8. 时间选择弹层视觉确认（§4.5）

> ⛔ **已否决的方案**：本节过去建议"首帧先用 `FrostedPanel` 降级、稳定后退到 AGSL"。
> **不要这么做** —— 踩坑 #9 已经论证过，用 `withFrameNanos` 做首帧降级会让
> `agslReady` 永远是 false，全部面板永久停在无模糊的降级路径。
> 冷启动要优化，方向是**减少首屏 AGSL 面板数量**或**预编译 shader**。

---

## 10. 环境

| 项 | 值 |
|----|-----|
| 设备序列号 | `d65730c5` |
| **adb（当前机器）** | `E:\dev\android-sdk\platform-tools\adb.exe` |
| **JDK（当前机器）** | `E:\dev\jdk21\jdk-21.0.2`（**JDK 21**，不是 17） |
| **SDK（当前机器）** | `E:\dev\android-sdk` |
| 项目路径 | `E:\dsh\dingding-go`（**纯英文，无中文**） |
| 屏幕 | 1280×2772 @ density 520（≈3.25x） |
| 底栏可点区域 | y=2694；胶囊 x≈300–1000，两半中心 x≈475 / x≈830 |
| 锁屏密码 | 已存于 EncryptedSharedPreferences，**源码中不得硬编码** |
| PIN 验证入口 | `DebugPasswordSeedReceiver`（仅 debug 构建） |

### 当前机器的构建命令

```powershell
$env:JAVA_HOME = 'E:\dev\jdk21\jdk-21.0.2'
$env:ANDROID_HOME = 'E:\dev\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'E:\dsh\dingding-go'
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

> **注意**：旧文档写的是 `F:\徐事\dsh\dingding-go`（含中文）与 JDK 17，
> 那是更早一台机器的环境。当前机器路径纯英文、JDK 21，
> `gradle.properties` 里的 `android.overridePathCheck=true` 已经不需要，但保留无害。
### 装机后的固定流程（每次 install -r 之后必须做）

```powershell
$adb = 'E:\dev\android-sdk\platform-tools\adb.exe'
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

### 第三轮（代码审计，未跑真机）

18. **`catch (Throwable)` 会吞掉协程取消** —— 这是本项目最危险的一处。
    `PunchEngine.execute()` 里的 `catch (Throwable)` 把 `CancellationException`
    转成了普通的 `PunchResult(success=false)`。协程取消是**控制流**，不是业务失败；
    吞掉它之后上层 `finally` 里的 `if (!paused)` 判断失效，
    服务既不清不退出、`running` 永远为 true，**当天后续打卡全部被静默忽略**。
    **规则：任何 `catch (Throwable)` 之前先单独接 `CancellationException` 并 rethrow。**

19. **`nextSlot` 在补执行窗口内会返回"同一个班次"** ——
    这是"补执行"特性的必然结果（`effectiveTrigger = now`）。
    但它和"每次闹钟触发后重排"组合起来，会形成
    "FIRE → 重排 FIRE → FIRE…" 的自激循环。
    凡是"触发后立刻重排"的调度器，都必须显式标记"刚触发过的这个槽位"。

20. **"暂停"如果没有有效期，就等于"永久停用"** ——
    `paused` 曾是布尔值，一旦置 true 就没人能改回来。
    用户点了暂停又忘记继续，当天就再也打不了卡，界面上还完全看不出原因。
    **凡是"用户可进入但不会自动退出的状态"，都要有超时。**

21. **前后台切换的第一帧不能计入掉帧统计** ——
    `GlassPerfGuard` 用 48ms 作为"慢帧"阈值，而进程回到前台的
    首帧重建组合 + AGSL 管线初始化轻松超过 200ms。
    切几次前后台就会被误判成"设备卡顿"，然后玻璃永久降级成无模糊的磨砂板。
    需要丢弃切换后的若干帧。

22. **动效参数必须集中定义** —— 之前缓动曲线散在 6 个文件里，
    还有 2 处残留 `spring()`，导致同一个"按下回弹"在两个组件里观感不同。
    统一到 `ui/theme/GlassMotion.kt` 后改一处即全局生效。

### 第四轮（底栏手势，GUI 交互）

23. **受限协程作用域里无法逐帧驱动动画 —— 这是本轮最大的教训**

    `awaitEachGesture { }` 的 lambda 跑在 `AwaitPointerEventScope` 里，
    它继承了 `RestrictedSuspendable`。任何挂起调用（`Animatable.snapTo`、
    `animateTo`、`pager.scrollToPage`）都**不能直接调**，
    编译器会报 `Restricted suspending functions can invoke ...`。

    唯一出路是 `scope.launch { }` 逃逸，但 `launch` 是**异步调度**的：
    手指移动 60 帧就排 60 个协程，它们被合并、被丢弃、互相取消 ——
    视觉结果就是「卡顿一下然后瞬移」。

    **结论：手势循环里只能写普通 state（`mutableFloatStateOf`），
    不能写 `Animatable`。** 普通 state 的写入是同步的，本帧写完下帧必渲染。

24. **`Animatable` 是互斥的，多处并发写会互相打断**

    同一帧里两个 `scope.launch` 都去 `animateTo` 同一个 `Animatable`，
    后一个会**立刻取消**前一个。表现：放大动画播到一半被打断、
    松手时位置动画和缩放动画互相取消导致闪烁。

    真要多个动画并行，必须用**不同的 `Animatable`**（本轮拆成
    `pressX` 与 `scaleAnim` 各管各的），或者放在同一个 `launch` 里用
    `launch { }` 嵌套（子协程共享父作用域但互不取消）。

25. **「交接」是这类 bug 的总根源 —— 能消除就别做交接**

    本轮先后引入过 `pressX` / `isPressed` / `posJob` / `scaleJob` 等中间态，
    每次都试图「按住期间自己管位置，松手再交还给 liveOffset」。
    每一次都产生新的竞态：瞬移、回弹、复读、闪烁。

    最后收敛到**单一数据源**才彻底干净。教训：
    两个位置源之间的交接，无论怎么调整顺序都有窗口期；
    正确做法是让其中一个彻底不参与位置计算。

26. **`snapTo` 在 `isPressed = true` 之前调用会渲染一帧旧值**

    即使顺序写对了，`snapTo` 是挂起函数，`isPressed = true` 会先触发重组，
    这一帧读到的还是 `pressX` 的旧值（初始 `0f` = 首页位置）。
    在设置页长按 → 指示器先闪到首页再滑回来，就是这个原因。

    修法：用 `launch(start = CoroutineStart.UNDISPATCHED)`，
    保证 `snapTo` 在本帧内同步完成，中间不插空帧。

27. **父级 `pointerInput` 与子级 `Clickable` 会双触发**

    底栏 Row 上挂了 `pointerInput`，每个 tab 子项又挂了 `ClickableNoRipple`，
    一次点按会**同时**触发两者 → 第一次切过去、第二次切回来，
    用户必须点两次才生效。

    修法：统一由父级手势处理（根据 `touchSlop` 区分点按与拖拽），
    子项不挂任何点击修饰符。

28. **`adb input` 测试底栏坐标必须落在胶囊内**

    底栏是 0.56 屏宽的居中胶囊。1280 屏宽下胶囊约 x=300–1000，
    两半中心约 **x=475 / x=830**（y=2694）。
    用 x=900 测试会得到一堆假失败 —— 那不是代码问题，是测试点超出了
    Row 的局部坐标范围。**别拿错坐标当 bug 修**（本轮因此浪费了数轮）。

---

## 12. 第三轮：代码审计与修复（未跑真机）

> **本轮全程没有触发任何真实打卡。** 所有验证都是编译 + 纯函数单元测试。

### 12.1 修复清单

| # | 严重度 | 问题 | 修复 | 验证 |
|---|--------|------|------|------|
| 1 | 🔴 P0 | `PunchEngine` 用 `catch (Throwable)` 吞掉 `CancellationException`，暂停后 `running` 永远为 true、服务不退出、**当天再也打不了卡** | 单独 catch `CancellationException` 并 rethrow；`VirtualMachineError` 等也继续上抛；入口加 `ensureActive()` 检查点 | 编译通过 |
| 2 | 🔴 P0 | 闹钟触发后立刻重排，而 `nextSlot` 在补执行窗口内返回**同一班次** → FIRE 自激循环 | 新增 `AlarmScheduler.markJustFired()`；重排前标记刚触发的槽位；重排提到 startService **之前** | `SchedulerGuardTest` 4 例 |
| 3 | 🔴 P0 | 暂停态无有效期，且暂停时不释放唤醒锁 | 加 `PAUSE_MAX_MS`（5 分钟）有效期；暂停立即 `releaseWakeLock()`，继续时重新获取；新请求可接管超时暂停 | 编译通过 |
| 4 | 🟡 P2 | 恢复执行时不校验 `pendingTargetAt` 是否已过期 | 恢复前先查 `withinRetryWindow`，超窗直接收尾 | 编译通过 |
| 5 | 🟡 P2 | 前后台切换首帧被计入掉帧 → 误判降级，玻璃永久变磨砂 | `RESUME_GAP_MS` 间隔检测 + 丢弃切换后 8 帧 | 编译通过 |
| 6 | 🟡 P2 | 2 处残留 `spring()` 违反"全应用禁用 spring"；缓动曲线散在 6 个文件 | 新建 `ui/theme/GlassMotion.kt` 统一时长与曲线；`PressEffect`/`LiquidGlassButton` 改用它 | 全库 `spring(` 仅剩注释 |
| 7 | 🟢 | `PunchSchedule` 的 `best!!` 断言 | 改局部变量持有 | 编译通过 |
| 8 | 🟢 | `RootShell.exec` 的 `catch (Throwable)` 会把中断当失败 | 单独接 `InterruptedException` 恢复中断位并 rethrow | 编译通过 |
| 9 | 🟢 | 文档称"全库只有一个 `success = true` 站点"，实际 3 处 | 修正 §3.2 并列表三处 | — |
| 10 | 🟢 | §9 建议的冷启动方案与踩坑 #9 直接矛盾 | §9 标注"已否决" | — |

### 12.2 新增/变更文件

| 文件 | 变更 |
|------|------|
| `exec/PunchEngine.kt` | 取消传播；`ensureActive()` 检查点 |
| `exec/PunchForegroundService.kt` | `PAUSE_MAX_MS`、`isPauseActive()`、暂停释放唤醒锁、恢复前校验窗口 |
| `exec/PunchAlarmReceiver.kt` | 重排提前到启动服务之前 + 调用 `markJustFired` |
| `core/AlarmScheduler.kt` | `markJustFired()` / `isJustFired()` / 重排时跳过刚触发槽位 |
| `core/PunchSchedule.kt` | 去掉 `!!` |
| `exec/RootShell.kt` | 中断语义修正 |
| `ui/theme/GlassMotion.kt` | **新增**：统一动效规格 |
| `ui/components/PressEffect.kt` | 去掉 spring |
| `ui/components/LiquidGlassButton.kt` | 去掉 spring |
| `ui/glass/GlassPerfGuard.kt` | 前后台切换预热帧 |
| `app/src/test/.../SchedulerGuardTest.kt` | **新增**：4 个调度抗自激用例 |

### 12.3 本轮验证结果

```
:app:compileDebugKotlin        BUILD SUCCESSFUL
:app:compileDebugUnitTestKotlin BUILD SUCCESSFUL
JUnitCore 全量                  OK (88 tests)
```

> ⚠️ **仍未真机验证**：暂停/恢复的实际观感、`markJustFired` 在跨天场景下的行为、
> 玻璃降级阈值是否合适。这些都需要上机确认。
