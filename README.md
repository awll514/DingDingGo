# 自动打卡 · Redmi K90 至尊版

一个**单机型专用**的钉钉自动打卡工具，为 Redmi K90 至尊版 / HyperOS 3 / Android 16 (API 36) 定制。
锁屏状态下按设定时间自动完成钉钉上下班打卡，打卡后本地通知，UI 为 HyperOS 3 风格的液态玻璃。

> **这不是通用 App。** 所有技术决策以「在 Redmi K90 至尊版上能跑」为唯一目标，
> 不做其他机型与 OS 版本的兼容性适配。

---

## 一、这份代码诚实的前提

请先读这一节，再决定是否依赖它。

| 事项 | 实际情况 |
|---|---|
| **100% 准时** | **做不到，也不承诺。** HyperOS 的后台管控可能在极端情况下延迟唤醒或杀掉进程。本应用用「预热 + 主闹钟 + 补执行」三重闹钟 + 电池白名单 + root 辅助来提高成功率，但**不保证每次都成功**。 |
| **打卡成功的判定** | 只认钉钉界面上出现的成功文案（"打卡成功"/"已打卡"/"更新成功"）。**不会**因为"点击已发出"就记成功。判定依据会写进日志的 `evidence` 字段，事后可查。 |
| **钉钉 UI 文案** | 代码里的文案候选是按公开资料与社区经验整理的。**钉钉版本更新会改文案**，首次真机使用大概率需要按实际界面调整 `DingTalkUi.kt` 里的候选列表。 |
| **真机验证** | **锁屏 + 有密码 + 钉钉真实打卡**这一整段**仍未在 K90 上跑通**。已验证的是：全量编译通过、**88 个单元测试全绿**、界面在真机上无崩溃。完整打卡链路需要你自己跑第一次。 |
| **风控** | 钉钉可能对 root 环境或非标准 UI 自动化有风控。本应用**不做虚拟定位、不伪造位置、不修改钉钉包体**。执行失败时会用通知和日志让你知情，由你手动兜底。 |

---

## 二、HyperOS 3 上必须手动开启的清单（5 项）

**这 5 项缺任何一项，成功率都会显著下降。** 系统没有提供完整的可编程接口，所以只能手动确认。

### 1. 自启动（最关键，且无法自动检测）

- 路径：设置 → 应用设置 → 应用管理 → 自动打卡 → **自启动** → 打开
- **为什么必须**：Android 重启会清空所有 AlarmManager 闹钟，只有 `BOOT_COMPLETED` 能重新注册。
  自启动关闭时，重启后**闹钟全部丢失且 App 不会被告知**。
- ⚠️ **系统不提供"自启动是否已开"的读取接口**。App 内的健康度卡片会明确写"无法自动检测"，不会假装正常。
- 唯一可自证的信号：重启手机后执行 `adb shell dumpsys alarm | findstr autoattend`，看闹钟还在不在。

### 2. 省电策略 → 无限制（双重作用）

- 路径：设置 → 应用设置 → 应用管理 → 自动打卡 → **省电策略** → 无限制
- **为什么必须**：
  1. 防止后台进程被清理；
  2. **这是精确闹钟的官方豁免**——Android 14 起，power allowlist 上的应用
     "always allowed to call setExact() / setExactAndAllowWhileIdle()"。
     也就是说，即使你没给"闹钟和提醒"权限，只要开了电池无限制，精确闹钟依然合法。
- App 内点"电池无限制"的修复按钮时，会优先尝试用 root 直接加白名单（`dumpsys deviceidle whitelist +包名`），失败才跳系统设置。

### 3. 无障碍服务

- 路径：设置 → 更多设置 → 无障碍 → 已下载的应用 → **自动打卡 · 界面操作** → 打开
- **为什么必须**：这是首选执行通道。没有它就只能依赖 root。
- 隐私说明：该服务**只监听钉钉包名**（`com.alibaba.android.rimet`），不读取任何其他应用的内容。

### 4. 通知权限

- 路径：设置 → 应用设置 → 应用管理 → 自动打卡 → **通知管理** → 允许
- **为什么必须**：打卡结果（成功/失败/没启动）全靠通知告知。关掉就等于静默失败。

### 5. 🔴 MIUI/HyperOS「后台发送本地通知」（最容易漏）

- 路径：设置 → 应用设置 → 应用管理 → 自动打卡 → **其他权限** → **后台发送本地通知** → 允许
- **为什么必须**：HyperOS 默认**拒绝**应用在后台发送本地通知。
  这一项不打开，打卡成功/失败的通知会被系统直接吞掉——
  你会在完全不知情的情况下错过所有打卡结果。
- 这是 F4（打卡后通知）在小米设备上的隐藏杀手，**前 4 项都对了但漏了这项，通知依然收不到**。

### 额外建议

- **锁屏密码**：在 App 设置页配置。密码走 `EncryptedSharedPreferences` 加密存储，
  不进日志、不进数据库、不出现在任何网络请求里。
  小米数字锁屏在输满位数后自动解锁，因此流程里**不需要发回车**。
- **Root（可选但强烈建议）**：Magisk / KernelSU 均可。
  root 通道在「点亮屏幕 / 解锁输入密码 / 后台拉起钉钉」三处比无障碍可靠得多，
  尤其是**后台启动 Activity**——AOSP 对 `Process.ROOT_UID` 直接放行，
  不需要申请 `SYSTEM_ALERT_WINDOW` 之类的 BAL 豁免。

---

## 三、权限与实现的对应关系

| 权限 | 用途 | 缺失后果 |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | 精确闹钟 | 降级为不精确闹钟，可能延迟数分钟 |
| `RECEIVE_BOOT_COMPLETED` | 开机重排闹钟 | 重启后闹钟全丢 |
| `FOREGROUND_SERVICE` + `SPECIAL_USE` | 执行编排服务 | 无法执行打卡 |
| `POST_NOTIFICATIONS` | 结果通知 | 结果不可见 |
| `WAKE_LOCK` | 执行期间保持 CPU | 屏幕关闭时可能被挂起 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加白名单 | 后台易被清理 |
| `QUERY_ALL_PACKAGES` | 检测钉钉是否安装 | 健康度无法判断钉钉存在性 |

> **为什么 FGS 用 `specialUse`**：这是唯一「零前置条件 + 无时长上限 + 无 BOOT 限制」的类型。
> `dataSync` 在 Android 15+ 有 6 小时/天配额，且不允许从 BOOT 启动。

---

## 四、构建

### 环境要求（本项目实测通过的组合）

```
JDK          21
Gradle       9.5.0
AGP          9.3.1
Kotlin       2.4.21（AGP 9 内置，不再单独应用 kotlin-android 插件）
KSP          2.3.13（KSP2 独立版本线）
Compose BOM  2026.06.01
compileSdk   37        ← backdrop（Kyant0/AndroidLiquidGlass）要求 minCompileSdk=37
minSdk       31
targetSdk    37
```

### 几个必须知道的构建约束

1. **compileSdk 必须是 37**，且要用 AGP 9 的新 DSL 写法：
   ```kotlin
   compileSdk { version = release(37) }
   ```
   AGP 8.x 读不到 `android-37.0` 这种带次版本号的平台目录名，会报
   `Failed to find target with hash string 'android-37'`。

2. **不要应用 `org.jetbrains.kotlin.android` 插件**。AGP 9 内置 Kotlin，
   再应用旧插件会抛 `class ... cannot be cast to class com.android.build.gradle.BaseExtension`。

3. **KSP 必须用 KSP2 独立版本线**（如 `2.3.13`），并且不能在 `gradle.properties` 里
   同时开 `android.builtInKotlin=false`——那会让 AGP 退回旧 Kotlin 集成路径。

4. **Gradle 发行包要能下载**。如果公司网络卡在 `services.gradle.org`，
   可手动把 `gradle-9.5.0-bin.zip` 放进
   `~/.gradle/wrapper/dists/gradle-9.5.0-bin/<hash>/` 再跑。

### 构建命令

```bash
# Windows
gradlew.bat :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里写 SDK 路径（**用正斜杠，避免 properties 转义踩坑**）：

```properties
sdk.dir=E:/dev/android-sdk
```

### 运行单元测试

```bash
gradlew.bat :app:testDebugUnitTest
```

覆盖 **104 个用例**（10 个测试类），包括：
- 排班计算（跨天、跨周、补执行窗口、单日覆盖、休息日强制）
- 计时器精度（预热提前量 = 75 秒）
- uiautomator XML 解析（坐标换算、零面积节点跳过、精确优先于包含）
- 成功/失败信号判定（且保证两类信号不重叠）
- 迟到/下班安全闸门（改常量必失败）
- 打卡结果判定（极速打卡、失败优先、状态翻转）
- 已完成班次跳过（手动打卡后不重复触发）
- **调度抗自激**（闹钟触发后重排不会排回同一班次）
- **目标时段已打判定**（防止下班卡被误判成上班卡）
- **打卡窗口边界**（提前量、迟到阈值）

> ⚠️ 其中 12 个用例带 `@Ignore`：它们是红线的**期望语义**，断言已写好，
> 等把相关函数从 `PunchEngine` 抽成独立入口后即可解挂。
> `@Ignore` 是 skipped 而非 passed，不会造成"假绿"。

> AGP 9.3.1 在 Windows 下 `testDebugUnitTest` 会报 ClassNotFoundException
> （测试类其实编译成功了）。绕过方式见 HANDOVER §7。

---

## 五、验证清单（部署到你手机后按顺序做）

### 验证 1：闹钟真的注册了

```bash
adb shell dumpsys alarm | findstr /i autoattend
```

应该能看到 `com.redmi.autoattend` 的三条记录（PREWARM / FIRE / RETRY）。
**然后杀掉 App 进程再跑一次**，闹钟必须还在——这是验收标准 1。

```bash
adb shell am force-stop com.redmi.autoattend
adb shell dumpsys alarm | findstr /i autoattend
```

### 验证 2：重启后闹钟恢复

```bash
adb reboot
# 等开机完成
adb shell dumpsys alarm | findstr /i autoattend
```

**如果这里空了，说明自启动没开。** 这是最常见的失败点。

### 验证 3：通知能到达

App 设置页 → 通知 → 分别点「测试成功通知」和「测试失败通知」。
失败通知应该带一个「立即重试」按钮。

**如果收不到，先检查第二节的第 5 项（后台发送本地通知）。**

### 验证 4：锁屏全链路（最关键，也最需要你手动确认）

1. 把上班时间改成 2~3 分钟后
2. 锁屏（确认屏幕已关闭、有密码）
3. 等待触发
4. 观察：屏幕是否点亮 → 是否自动解锁 → 钉钉是否被拉起 → 是否完成打卡
5. 看日志：App 设置页 → 打卡日志，检查 `evidence` 字段有没有记录成功判定依据

**第一次大概率会在"进入考勤页"或"点击打卡"这两步失败**，
因为钉钉的实际 UI 文案可能和我预设的候选不一致。排查方法：

```bash
# 抓钉钉当前界面层级，看真实的 text / resource-id
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
```

然后在 `app/src/main/java/com/redmi/autoattend/exec/DingTalkUi.kt` 里
把实际文案加进对应候选列表，重新构建。

### 验证 5：玻璃真的在采样背景

肉眼观察首页：背景光斑应该是**缓慢流动**的（周期 26~42 秒），
大玻璃面板内部能看到背景光斑被模糊、在边缘被折射拉伸，
上边缘应该有一条更亮的白色高光线。
如果面板内部是纯色块、且和背后光斑完全无关，那就是采样失败。

### 验证 6：性能

```bash
adb shell dumpsys gfxinfo com.redmi.autoattend framestats
```

**玻璃面数量上限已解除** —— 用户明确要求"不用考虑性能消耗"，
这台设备（Adreno 830）性能充足，视觉优先。

当前使用玻璃面板的位置：首页倒计时大面板、今日打卡卡、底部 tab bar、
健康度卡、设置页分组等。

> 但保留 `GlassPerfGuard` 作为**兜底**：设备过热/降频时自动退到磨砂，
> 不会让界面卡死。这是保护，不是设计约束。

> ⚠️ **已知限制**：`LiquidGlassPanel` 的 `blurRadius` 在本机**完全不生效**
> （8/12/20/200dp 逐像素一致）。磨砂感实际由 `tintAlpha` 遮挡实现。
> 详见 HANDOVER §0.1 的完整证据链，不要再尝试调大 blurRadius。

**三层性能保护**（都在 `ui/glass/` 下）：

| 机制 | 位置 | 作用 |
|---|---|---|
| 背景 20fps 节流 | `FlowingBackground.kt` | 背景若以 60fps 重绘，录制层每帧重录，3 个玻璃面板每秒要跑 180 次全管线。节流到 20fps 后 GPU 工作量降到 1/3，视觉上完全看不出（光斑周期是 26~42 秒）。 |
| 自适应降级 | `GlassPerfGuard.kt` | 连续 20 帧慢于 48ms 就自动把全管线换成轻量磨砂；恢复流畅 45 帧后升回来（带 8 秒冷却防抖）。 |
| 模拟器静态降级 | `GlassPerfGuard.initialDegrade()` | 检测到 SwiftShader / goldfish / ranchu 直接降级——软件渲染器跑 AGSL 会卡到不可用。 |

> 实测数据（Android 14 模拟器 + SwiftShader 软件渲染，这是最坏情况）：
> 加保护前 50th 150ms / 90th 250ms / 100% 掉帧；
> 加保护后 50th 57ms / 90th 81ms / 74% 掉帧。
> 真机（K90 的 Adreno GPU）会走完整 AGSL 管线，不会触发降级。

### 验证 7：主题切换

设置 → 外观 → 主题，三档都试一遍：
- **浅色**：背景是冷白偏蓝，玻璃用实白 + 白描边，文字深色
- **深色**：背景是深海军蓝，玻璃几乎不着色（tintAlpha 0.012），靠模糊与折射成像
- **跟随系统**：切换系统深色开关，App 应立即跟随

状态栏和导航栏的图标颜色也会跟着变（深色主题浅色图标，浅色主题深色图标）。

---

## 六、架构说明

```
app/src/main/java/com/redmi/autoattend/
├── MainActivity.kt              沉浸式宿主 + 主题下发 + 双 tab 切换
├── core/
│   ├── AppConfig.kt             配置仓库（含 EncryptedSharedPreferences 密码）
│   ├── PunchSchedule.kt         排班纯函数（可单测）
│   ├── AlarmScheduler.kt        三段闹钟注册（预热/FIRE/补执行）
│   └── HealthCheck.kt           保活与权限诚实检测 + 5 级自启动跳转
├── data/db/                     Room：打卡日志（30 天）
├── exec/
│   ├── PunchAlarmReceiver.kt    静态闹钟接收器（进程被杀也能唤起）
│   ├── BootReceiver.kt          开机重注册
│   ├── PunchForegroundService.kt 执行编排 + 唤醒锁生命周期
│   ├── PunchEngine.kt           ★ 核心：点亮→解锁→拉钉钉→点击→判定
│   ├── PunchAccessibilityService.kt  无障碍通道
│   ├── RootShell.kt             root 通道（su -c）
│   ├── UiHierarchy.kt           uiautomator XML 解析（root 通道定位坐标）
│   ├── DingTalkUi.kt            ★ 钉钉界面文案候选（首次真机最可能要改这里）
│   ├── GestureExecutor.kt       无障碍手势注入
│   └── PunchNotifier.kt         成功/失败/未启动 三类通知
└── ui/
    ├── theme/                   双主题色板 + 排版尺度
    ├── glass/                   全 AGSL 面板 / 轻量磨砂 / 流动背景 / 录制层
    ├── components/              倒计时、状态徽标、滚轮选择器、设置组件
    └── screens/                 首页 / 设置 / 时间编辑弹层
```

### 执行流程中的超时与回退

| 步骤 | 做法 | 超时 | 失败回退 |
|---|---|---|---|
| 点亮屏幕 | root `input keyevent 224` | 3s 轮询 | 拉起 Activity（`turnScreenOn`） |
| 解锁 | 上滑 → 输入数字（**不发回车**） | 6s 轮询 × 3 次 | 补发一次回车 |
| 启动钉钉 | root `monkey` / Intent | 8s 固定等待 + 20s 轮询 | — |
| 进入考勤 | 无障碍点「工作」→「考勤打卡」 | 每步 3 次重试 | 切 root 通道 dump XML 按坐标点 |
| 点击打卡 | 无障碍文本定位 | 3 次重试 | 切 root 通道 |
| 判定结果 | 读界面文本，先查失败信号再查成功信号 | 3 次 × 1.5s | 返回 null → 记失败 |

**为什么"先查失败信号"**：如果钉钉同时显示"打卡成功"和"不在打卡范围"（例如部分成功），
宁可判失败让用户知情，也不要误报成功。

---

## 七、已知限制（不装成能做的事）

1. **没有真机验证过完整打卡链路。** 编译、单测、UI 渲染已验证；锁屏打卡这一段需要你在 K90 上跑第一次。
2. **钉钉文案硬编码在 `DingTalkUi.kt`。** 钉钉更新后可能要改。这是设计上的取舍——用固定的候选列表比用模糊的图像识别更可预测、更好排查。
3. **自启动状态无法检测。** 系统没有 API。健康度卡片会明说，不会假装正常。
4. **休眠（Doze）下闹钟精度有限。** `setExactAndAllowWhileIdle` 在 Doze 下对每个 App 有"每 9 分钟最多一次"的约束（我们每天只有 2~4 个闹钟，不冲突），但系统仍可能整体延迟唤醒。
5. **不做位置伪造、不改钉钉包体。** 如果公司考勤要求定位，本应用不解决这个问题。
6. **HyperOS 版本升级可能改变后台策略。** 每次系统大版本更新后建议重跑验证清单。

---

## 八、快速验证脚本

项目根目录的 `verify-alarm.sh`（bash）和 `verify-alarm.ps1`（PowerShell）可一键检查闹钟注册状态。

```bash
# Linux / macOS / Git Bash
./verify-alarm.sh

# Windows PowerShell
./verify-alarm.ps1
```

脚本会输出：
- 当前注册的闹钟列表（含触发时间）
- App 进程是否存活
- 无障碍服务是否在线
- 最近一次打卡记录

---

## 九、许可与免责

本项目仅供**个人在自有设备上**提升打卡便利性使用。
使用者需自行确认所在公司的考勤政策是否允许此类自动化工具。
作者不对因使用本工具导致的考勤异常、纪律处分或任何其他后果承担责任。