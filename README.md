# 钉钉GO（DingDingGo · v1.0.0）

一个专为 **Redmi K90 至尊版 / HyperOS 3 / Android 16 (API 36)** 打造的钉钉自动打卡工具，支持锁屏自动点亮解锁并完成打卡、蓝牙考勤机在场自动触发、像素钉钉娘桌面接管悬浮窗，以及全套液态玻璃（Liquid Glass）视觉与交互。

- **包名**：`com.dingdinggo.app`
- **版本**：`v1.0.0`（`versionCode = 1`）
- **定位**：单机型深度适配工具，专注在该设备与系统环境下的稳定性与可观测性。

---

## 一、核心功能与安全边界

| 事项 | 说明 |
|---|---|
| **定时自动打卡** | 基于 `AlarmManager.setExactAndAllowWhileIdle`（预热 75 秒 + 准点触发 + 120 秒补执行三重闹钟），配合 `BOOT_COMPLETED` 重注册与低功耗保活检查。 |
| **锁屏全自动链路** | 点亮屏幕 → 上滑并自动输入 `EncryptedSharedPreferences` 加密存储的锁屏密码 → 拉起钉钉（`cmd package resolve-activity` + `am start -n`，不修改系统旋转锁定）→ 进入考勤页并点击打卡。 |
| **结果真实判定** | 只以钉钉界面真实出现的成功反馈（如「打卡成功」「已打卡」「更新成功」）为成功依据，判定依据记录在日志的 `evidence` 字段中，绝不将“发出点击”误报为成功。 |
| **双执行通道** | 无障碍服务（`PunchAccessibilityService`）优先；连续失败时自动降级到 Root `su` 通道（可手动锁定通道）。 |
| **蓝牙考勤机触发** | 使用系统蓝牙栈 `PendingIntent` 后台扫描钉钉考勤机 BLE 广播（`0xFE3C`），受安全阀保护：仅在设定提前窗口与迟到宽限内生效，拒绝提前打下班卡。 |
| **合规边界** | **不做虚拟定位、不做位置伪造、不修改钉钉客户端包体。** 不承诺 100% 绝对不被系统清理或延迟，失败时通过系统原生通知（含「立即重试」按钮）与震动提示音告知。 |

---

## 二、HyperOS 3 必开系统设置（6 项）

以下权限或策略缺少任何一项都会直接影响定时触发、锁屏执行或结果通知：

### 1. 自启动（最关键）
- 路径：设置 → 应用设置 → 应用管理 → **钉钉GO** → **自启动** → 打开
- 作用：手机重启会清空系统闹钟，开启自启动后才能在 `BOOT_COMPLETED` 恢复所有定时闹钟。
- 说明：Android / HyperOS 不提供“是否已开启自启动”的查询接口，应用健康度卡片会提示「需自行确认」。

### 2. 省电策略 → 无限制
- 路径：设置 → 应用设置 → 应用管理 → **钉钉GO** → **省电策略** → **无限制**
- 作用：避免后台服务与精确闹钟被系统休眠挂起；应用内「权限与保活」修复按钮会优先尝试通过 Root 写入白名单。

### 3. 无障碍服务
- 路径：设置 → 更多设置 → 无障碍 → 已下载的应用 → **钉钉GO · 界面操作** → 打开
- 隐私说明：配置中限定 `packageNames="com.alibaba.android.rimet"`，仅读取钉钉界面节点，不访问其他应用。

### 4. 通知权限
- 路径：设置 → 应用设置 → 应用管理 → **钉钉GO** → **通知管理** → 允许
- 作用：及时接收打卡成功 / 失败通知，并在失败通知中一键「立即重试」。

### 5. MIUI / HyperOS「后台发送本地通知」
- 路径：设置 → 应用设置 → 应用管理 → **钉钉GO** → **其他权限** → **后台发送本地通知** → 允许
- 作用：HyperOS 默认拦截后台应用发送通知，未开启会导致打卡结果通知被静默吞掉。

### 6. 蓝牙触发相关权限（仅启用蓝牙自动打卡时需要）
- 路径：设置 → 应用设置 → 应用管理 → **钉钉GO** → **权限** → **附近的设备** + **位置信息**
- 说明：因未声明 `neverForLocation`（避免部分考勤机广播被系统位置过滤名单静默丢弃），Android / HyperOS 要求同时授予「附近的设备」与精确位置权限。**本应用不会读取或上传任何位置信息。**

### 额外建议
- **锁屏密码**：在「设置 → 安全 → 锁屏密码」配置，使用 `EncryptedSharedPreferences` 加密保存。
- **Root 授权（强烈建议）**：在 Magisk / KernelSU 中为钉钉GO授予 Root 权限，用于在熄屏解锁与后台拉起钉钉时绕过系统限制并作为无障碍失败后的备用通道。
- **悬浮窗权限**：用于自动打卡接管屏幕时显示「钉钉娘」桌面状态与暂停/取消控制条。

---

## 三、构建与签名说明

### 环境要求

| 工具 / SDK | 版本 |
|---|---|
| JDK | 17 / 21 |
| Gradle | 9.5.0 |
| AGP | 9.3.1 |
| compileSdk / targetSdk | 37 |
| minSdk | 31 |

### 构建命令

在项目根目录创建 `local.properties` 配置本地 Android SDK 路径（建议使用正斜杠）：

```properties
sdk.dir=C:/AndroidTools/sdk
```

如需构建正式签名安装包，可在项目根目录放入签名配置文件 `keystore.properties`（已被 `.gitignore` 忽略，不会上传仓库）：

```properties
storeFile=dingdinggo-release.jks
storePassword=你的仓库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

执行构建：

```bash
# Release 包（配置 keystore.properties 后自动签名）
./gradlew :app:assembleRelease

# Debug 包
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/release/` 或 `app/build/outputs/apk/debug/`。

### 运行单元测试

```powershell
powershell -ExecutionPolicy Bypass -File run-tests.ps1
```

当前覆盖 **20 个测试类 / 185 个单元测试用例**（排班跨天跨周计算、预热与补执行窗口、安全闸门、蓝牙在场判定规则、界面节点解析、结果判定、悬浮窗文案等）。

---

## 四、快速验证（ADB）

```bash
# 1. 检查闹钟是否已注册
adb shell dumpsys alarm | findstr /i dingdinggo

# 2. 强行停止应用后复查闹钟是否仍留存在系统队列中
adb shell am force-stop com.dingdinggo.app
adb shell dumpsys alarm | findstr /i dingdinggo

# 3. 性能帧率采样
adb shell dumpsys gfxinfo com.dingdinggo.app framestats
```

也可直接运行根目录的一键检查脚本：

```powershell
# Windows PowerShell
.\verify-alarm.ps1
```

```bash
# Linux / macOS / Git Bash
./verify-alarm.sh
```

---

## 五、项目结构

```text
app/src/main/java/com/dingdinggo/app/
├── MainActivity.kt                  沉浸式单 Activity 宿主与主题/弹层宿主
├── core/
│   ├── AppConfig.kt                 配置与 EncryptedSharedPreferences 加密存储
│   ├── PunchSchedule.kt             排班、倒计时、时长格式化纯函数
│   ├── AlarmScheduler.kt            三重闹钟调度（PREWARM / FIRE / RETRY）
│   ├── BtPunchRule.kt               蓝牙触发安全阀判定
│   ├── BtTriggerStore.kt            蓝牙开关、早到窗口与设备记忆
│   └── HealthCheck.kt               权限与保活状态检测
├── ble/
│   ├── BtScanner.kt                 PendingIntent 蓝牙 LE 扫描管理
│   ├── BleScanReceiver.kt           系统蓝牙广播静态接收器
│   └── BtDeviceLearner.kt           考勤机设备学习
├── data/db/                         Room 打卡日志数据库（保留最近 30 天）
├── exec/
│   ├── PunchAlarmReceiver.kt        闹钟静态接收器
│   ├── BootReceiver.kt              开机、时间变更、覆盖安装重排闹钟
│   ├── KeepAliveService.kt          低功耗保活与闹钟自检服务
│   ├── PunchForegroundService.kt    打卡流程前台编排与唤醒锁生命周期
│   ├── PunchEngine.kt               点亮 → 解锁 → 启动钉钉 → 点击 → 结果校验
│   ├── PunchAccessibilityService.kt 无障碍执行通道
│   ├── RootShell.kt                 Root 通道
│   ├── ControlHudOverlay.kt         钉钉娘双窗口零瞬移悬浮控制条
│   └── PunchNotifier.kt             原生系统通知与代码级震动/提示音反馈
└── ui/
    ├── theme/                       浅色/深色双液态玻璃主题与 iOS 缓动曲线
    ├── glass/                       Backdrop 模糊折射面板、全屏弹层宿主与系统窗体模糊
    ├── components/                  悬浮胶囊底栏、循环玻璃时间滚轮、像素钉钉娘组件
    └── screens/                     首页、设置页、时间编辑弹层
```

---

## 六、已知限制

1. **HyperOS 后台管控与深度 Doze**：系统深度休眠可能延迟精确闹钟投递或限制第三方应用后台 BLE 扫描，需严格保持「自启动」与「省电策略无限制」，并保留手动确认习惯。
2. **钉钉界面文案变更**：考勤页面及按钮候选文本集中维护在 `DingTalkUi.kt`，若后续钉钉客户端改版导致无法识别按钮，需按新界面更新候选词列表。
3. **不保证 100% 准时**：受系统调度策略与环境风控影响，所有自动化流程均配备日志记录与失败重试通知作为兜底。

---

## 七、免责声明

本项目仅供**个人在自有设备**上提升日常考勤便利性使用，请自行确认所在组织的考勤管理规定。作者不对因使用本工具导致的任何考勤异常或其他后果承担责任。
