# Redmi K90 至尊版 / HyperOS 3（Android 16, API 36）锁屏自动打卡 —— 后台与调度技术结论

> 调研时间：2026-11-10。依据分三类标注：
> **[官方]** = Google / 小米官方文档或 AOSP 源码；**[社区]** = 社区实践（StackOverflow、GitHub 项目、ROM 分析帖）；
> **[推断]** = 由官方文档 + AOSP 源码推导、但未在本机实测。未实测的一律标 **[推断]**，不冒充已验证。
> 本轮**没有真机/模拟器**（工作区无 adb、无连接设备），故所有「实测」结论为空，第 9 节列出必须实机确认的清单。

---

## 0. 结论速览：哪些做法在 targetSdk 36 上**会被系统阻止**

| 做法 | 结果 | 依据 |
|---|---|---|
| 普通 `Context.startActivity()` 从后台/Service 拉起钉钉或自己的 Activity | **被拦**，Logcat 出现 `Background activity launch blocked!`，**不抛异常**（静默失败） | [官方] https://developer.android.google.cn/guide/components/activities/background-starts |
| 从 Service / FGS 里 `startActivity()`（无 SAW、无 START_ACTIVITIES_FROM_BACKGROUND） | **被拦** —— FGS 不是 BAL 豁免项 | [官方] 同上；AOSP `BackgroundActivityStartController` |
| `PowerManager.wakeUp(...)` | **不可用**：`@hide` + 需要 `DEVICE_POWER`(signature)，普通 App 调用抛 `SecurityException` | [官方/AOSP] `PowerManager.java` 中 `wakeUp(long)` 标注 `@removed Requires signature permission` |
| `setExactAndAllowWhileIdle` 未获 `SCHEDULE_EXACT_ALARM` 且**不在省电白名单** | 抛 `SecurityException` | [官方] https://developer.android.google.cn/about/versions/14/changes/schedule-exact-alarms |
| `startForeground()` 时未声明 `foregroundServiceType` + 对应 `FOREGROUND_SERVICE_*` 权限（targetSdk ≥ 34） | 抛 `SecurityException` / `MissingForegroundServiceTypeException` | [官方] https://developer.android.google.cn/develop/background-work/services/fgs/service-types |
| `BOOT_COMPLETED` 里启动 `dataSync`/`camera`/`mediaPlayback`/`phoneCall`/`mediaProjection`/`microphone` 类型 FGS（targetSdk ≥ 35） | 抛 `ForegroundServiceStartNotAllowedException` | [官方] https://developer.android.google.cn/about/versions/15/behavior-changes-15 |
| 仅持 `SYSTEM_ALERT_WINDOW` 但**没有可见悬浮窗**时从后台启动 FGS（targetSdk ≥ 35） | 抛 `ForegroundServiceStartNotAllowedException` | [官方] 同上 |
| `dataSync` / `mediaProcessing` FGS 24h 内累计超 6h（targetSdk ≥ 35） | `Service.onTimeout()` → 不 stopSelf 则 `RemoteServiceException` | [官方] 同上 |
| 后台发送本地通知（小米私有权限） | **默认拒绝**，打卡结果通知可能发不出去 | [官方-小米] https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626 |
| 后台弹 Activity（小米私有权限） | **默认拒绝** | [官方-小米] https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625 |
| 自启动（小米私有权限） | **默认拒绝**，需用户手动开 | [官方-小米] https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624 |

**本项目的关键结论**：我们**不需要**自己获得 BAL 豁免 —— 钉钉由 `su -c am start`（调用者 uid=0）拉起，uid 0 在 AOSP 里是 BAL 白名单 UID。这条路把「后台弹 Activity」这个最大的坑整个绕开了。

---

## 1. HyperOS 3 的后台限制

### 1.1 AOSP 层（Android 15 / 16）

| 机制 | 内容 | 依据 |
|---|---|---|
| Doze / App Standby | Doze 下：挂起网络、**忽略 wake lock**、`setExact()`/`setWindow()` 被推迟到维护窗口；`setAndAllowWhileIdle()`/`setExactAndAllowWhileIdle()` 仍可触发；`setAlarmClock()` 正常触发且系统会提前退出 Doze | [官方] https://developer.android.google.cn/training/monitoring-device-state/doze-standby |
| 后台启动 FGS | targetSdk ≥ 31 起后台不能起 FGS，除非命中豁免（精确闹钟即豁免之一） | [官方] https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start |
| Android 14：cached 状态下的动态广播被排队 | Manifest 静态注册的广播**不排队**，且投递时会临时把 App 移出 cached 状态 | [官方] https://developer.android.google.cn/about/versions/14/behavior-changes-all |
| Android 15：FGS 收紧 | `dataSync`/`mediaProcessing` 6h/24h 超时；`BOOT_COMPLETED` 不能起 6 类 FGS；SAW 豁免收窄为「必须已有可见悬浮窗」 | [官方] https://developer.android.google.cn/about/versions/15/behavior-changes-15 |
| Android 16：**job 与 FGS 解耦失败** | 与 FGS **并发执行的 job 同样受 runtime quota 限制**（JobScheduler / WorkManager / DownloadManager 全中招）；top 状态下启动的 job 也受配额 | [官方] https://developer.android.google.cn/about/versions/16/behavior-changes-all |
| Android 16：standby bucket `active` 也开始限配额 | `active` 桶不再是无限豁免 | [官方] 同上 |
| Android 16：**未新增**「后台启动 FGS」的限制 | 逐条核对 `behavior-changes-16` 与 `behavior-changes-all`（两页均为 2026-10-28 更新），无新增后台起 FGS 条款；收紧发生在 15 | [官方] 同上两页 |

> 对 AGENTS.md 的直接印证：**「Android 16 对前台服务关联的后台作业有配额限制」是对的**，官方原文为 *"jobs that are executing concurrently with a foreground service will adhere to the job runtime quota"*。所以 FGS 里跑 WorkManager 做重试并不可靠，重试必须用自己的协程 + 闹钟兜底。

### 1.2 小米私有层（HyperOS 3 是否还有？）

**有，而且小米自己在 2024–2026 年持续更新这些文档**，说明机制仍在维护：

| 小米机制 | 官方口径 | 文档更新 |
|---|---|---|
| **Powerkeeper（应用智能省电）** | 路径：设置 → 省电与电池 → 右上角 → 应用智能省电。触发条件：「后台长期持锁、虽在后台但短时间耗电过多、长时间后台不使用、非法持有传感器」→ 处置为「**释放锁资源、kill 应用进程**」。用户选「无限制」即取消限制。`isBackgroundRestricted()` 返回 true。小米不限制前台程序 | 2022-12-07 |
| **自启动** | 「**手机将默认应用不可自启动**」，需用户自行允许 | 2024-09-25 |
| **后台弹出页面** | 「该权限**默认为拒绝**」，仅音乐/运动/VOIP 等白名单例外 | 2024-11-27 |
| **后台发送本地通知** | 「该权限**默认拒绝**，即应用位于后台时默认不允许发送本地通知」 | **2026-03-20** |

- Powerkeeper 说明：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1628
- 自启动：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624
- 后台弹出页面：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625
- 后台发送本地通知：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626

> 🔴 **对 F4 的重击**：「后台发送本地通知」默认拒绝意味着**打卡成功/失败通知在后台可能被小米直接吞掉**。这不是 AOSP 的 `POST_NOTIFICATIONS`，是小米额外的一层。必须在 README 里列为必开项，并在代码里做「通知未送达」的兜底（前台可见时补发 + 日志页展示）。**[推断]** startForeground 常驻通知是否同样被拦未确认 —— 需在实机上把 App 切后台后用 `dumpsys notification --noredact` 观察。

### 1.3 前台服务在 HyperOS 3 的实际存活表现（诚实版）

- **[官方]** 小米只说「检测到不良系统健康行为才限制」，并明确「用户选无限制即不限制」。没有公开的保活 SLA，也没有「FGS 永不被杀」的承诺。
- **[社区]** 长期后台驻留的 FGS 仍会被 Powerkeeper 在「长时间后台不使用」时清掉；`com.miui.powerkeeper` 会按 UID 维护自己的白/黑名单，并会**周期性重建**（FCMGuard 项目观察到 `Settings.System.MILLET_NO_RESTRICT_APP` 会被重新写回，把 `com.google.android.gms` 踢掉）。见 https://github.com/Liuchijiang/NotiGuard 、https://github.com/rsps1008/FCMGuard-Plus-For-HyperOS4
- **[推断]** 对本项目：打卡窗口只有几十秒，FGS 是「闹钟唤起 → 干活 → stopSelf」，不做常驻，恰好避开 Powerkeeper「长时间后台占资源」的触发条件。这比死磕常驻更实际。
- **不确定**：HyperOS 3（Android 16 底）是否新增了比 MIUI 14/15 更激进的冻结策略（例如扩大 cached-app freezer 范围）。无公开资料，**必须实机验证**。

---

## 2. 自启动管理页 Intent

### 2.1 两种写法

```kotlin
// 写法 A：隐式 action（最老、覆盖面最广）
Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)

// 写法 B：显式 ComponentName（更确定，但组件名可能随版本变）
Intent().setComponent(ComponentName(
    "com.miui.securitycenter",
    "com.miui.permcenter.autostart.AutoStartManagementActivity"
))
```

- 写法 A 出处：StackOverflow Q&A「Xiaomi Android devices - properly requesting permissions for background locations」（https://stackoverflow.com/questions/55258440/），原答案即 `new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)`。**[社区]**
- 写法 B 是事实标准：`judemanutd/AutoStarter`、`android_autostart`(Flutter)、`ChinesePermissionUtils` 三份独立实现都用 `com.miui.securitycenter` + `com.miui.permcenter.autostart.AutoStartManagementActivity`。**[社区]**

### 2.2 需要携带什么 extra？

**结论：官方无任何 extra 约定；`AutoStartManagementActivity` 是「自启动应用列表页」，不接收定位到单个应用的 extra。** **[社区 + 推断]**

- AutoStarter 的 Xiaomi 分支只构造 ComponentName，**不 putExtra**（见 `AutoStartPermissionHelper.kt` 的 `PACKAGE_XIAOMI_COMPONENT` 与 `getIntent(pkg, component, newTask)`）。
- 社区常提的 `extra_pkgname` 属于**另一个页面**：`com.miui.permcenter.permissions.AppPermissionsEditorActivity`（单应用权限编辑页），打开的是「某个 App 的权限列表」，不是自启动开关页。

```kotlin
// 直达「本应用权限编辑页」（含自启动 / 后台弹出 / 锁屏显示等 MIUI 私有权限项）
Intent().setClassName(
    "com.miui.securitycenter",
    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
).putExtra("extra_pkgname", packageName)
```

- **[推断]** 可以顺带把包名塞进写 A/B（`putExtra("extra_pkgname", pkg)`）：部分版本会定位到列表中的该应用，不识别时被忽略、不会崩。属「试试不亏」，不要当可靠行为。

### 2.3 在 HyperOS 3 上是否仍有效？

- **[官方-小米]** 自启动权限本身仍在，官方文档 2024-09-25 还在更新，说明**权限还在**。
- **[社区]** HyperOS 之后 `com.miui.securitycenter` 的 Activity 结构存在被重构的风险；第三方博客指出「early versions of HyperOS maintain backward compatibility with `com.miui.securitycenter`，future updates may restructure」。
- **[不确定]** `AutoStartManagementActivity` 这个具体类名在 HyperOS 3（Android 16 底）是否原样存在 —— **没有官方承诺，也没有可靠实测报告**。所以**必须运行时探测 + 多级 fallback**，不能硬编码裸 startActivity。

### 2.4 推荐 fallback 链（按顺序，每步先 resolve）

```kotlin
private fun autostartCandidates(ctx: Context): List<Intent> {
    val pkg = ctx.packageName
    return listOf(
        // 1) 事实标准：隐式 action
        Intent("miui.intent.action.OP_AUTO_START")
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra("extra_pkgname", pkg),
        // 2) 显式组件（自启动列表页）
        Intent().setComponent(ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        // 3) 单应用权限编辑页（顺带能看到自启动 / 后台弹出 / 锁屏显示）
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", pkg),
        // 4) 系统兜底：应用详情页
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg, null)),
        // 5) 最后兜底：应用列表
        Intent(Settings.ACTION_APPLICATION_SETTINGS),
    ).onEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}

fun openAutostart(ctx: Context): Boolean {
    for (i in autostartCandidates(ctx)) {
        val resolvable = ctx.packageManager
            .queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        if (!resolvable) continue                       // 关键：先探测再启动
        if (runCatching { ctx.startActivity(i) }.isSuccess) return true
    }
    return false
}
```

要点：
1. **每步先 `queryIntentActivities`**，别裸 `startActivity` —— 否则 `ActivityNotFoundException` 会崩在设置页入口（社区反复踩的坑）。
2. `MATCH_DEFAULT_ONLY` 足够；HyperOS 上若返回空，降级到 `ACTION_APPLICATION_DETAILS_SETTINGS`。
3. 该跳转**必须由可见 Activity 发起** —— 从后台 Service 里 startActivity 会被 BAL 拦掉（第 6 节）。「一键跳转」只能放在设置页按钮回调里。
4. **不要试图用 root 直接改自启动状态**：MIUI/HyperOS 没有公开 API/appops 常量表示「自启动」，社区流传的 `OP_AUTO_START = 10008` **未经验证**，写错 op 号可能污染 appops 表。宁可让用户手点一次。

### 2.5 如何「检测」自启动是否已开？

**做不到可靠检测。** StackOverflow 明确回答「There is no Android API to check if the AutoStart is enabled or not」（https://stackoverflow.com/questions/42347547）。**[社区]**

弱信号（**[推断]**，只作提示不作断言）：
- `cmd appops get <pkg> 10021` —— 10021 是社区广泛记录的 **`OP_BACKGROUND_START_ACTIVITY`（后台弹出界面）**，不是自启动；可查「后台弹 Activity」是否被小米单独拒掉。参见社区 MIUI op 表（https://github.com/MuntashirAkon/AppManager/issues/1379）。
- `MIUI-Autostart` 靠反射 MIUI 私有 API 读状态（https://github.com/enter08092533-cyber/MIUI-Autostart），Android 9 起受 hidden API 限制，**在 Android 16 上大概率失效**，不要依赖。

**建议**：健康度卡片把「自启动」明确标成「无法自动检测，请人工确认」；并用「重启后闹钟是否还在」作为**间接证据**（`dumpsys alarm` 里还能否查到我们的 PendingIntent）。这是本项目唯一可自证的保活信号。

---

## 3. 电池优化白名单

### 3.1 AOSP Doze 白名单（唯一「官方承认」的省电白名单）

```bash
# 查询
adb shell dumpsys deviceidle whitelist | grep -i <pkg>
# 加入 / 移除（需要 shell 或 root；App 自身没有 DUMP 权限）
adb shell dumpsys deviceidle whitelist +<pkg>
adb shell dumpsys deviceidle whitelist -<pkg>
```

- 官方在「Test with Doze and App Standby」只给了 `dumpsys deviceidle force-idle` / `unforce`（https://developer.android.google.cn/training/monitoring-device-state/doze-standby）；`whitelist +pkg` 属 AOSP `DeviceIdleController` 的 shell 命令，**源码 + 社区双确认可用**。**[官方-源码 + 社区]**
- App 侧等价合法入口：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（弹系统对话框）或 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（跳设置页）。**[官方]**
- 检测：`PowerManager.isIgnoringBatteryOptimizations(pkg)`。**[官方]**

> 💎 **本项目最重要的一条**：Android 14 精确闹钟页明确写着 —— **「在 power allowlist 上的应用，永远允许调用 `setExact()` / `setExactAndAllowWhileIdle()`」**（原文："Apps that are on the power allowlist ... are always allowed to call the setExact() or setExactAndAllowWhileIdle() methods"）。也就是说：**只要用户把本 App 设成「无限制 / 不优化电池」，即使 `SCHEDULE_EXACT_ALARM` 被拒，精确闹钟依然合法。**
> **[推断]** 由此 `canScheduleExactAlarms()` 大概率也返回 true（AOSP 该判断会带上省电白名单），但需实机确认一次 —— 见第 9 节。

### 3.2 root 侧一条命令搞定（写进设置页「一键优化」）

```kotlin
// 需 Magisk/KernelSU 已授予 su
RootShell.execQuiet("dumpsys deviceidle whitelist +" + ctx.packageName)
// 校验
val ok = RootShell.exec("dumpsys deviceidle whitelist").contains(ctx.packageName)
```

注意：`dumpsys deviceidle whitelist +pkg` 写入的是 **user 白名单**（`mPowerSaveWhitelistUserApps`），重启后保留；被 `-pkg` 移除或系统重置才失效。**[推断-源码]**

### 3.3 MIUI / HyperOS 私有白名单：存在，但**不要写**

**[社区]** 大量 ROM 分析帖确认小米用 `Settings.System` 的一组键维护自己的白名单，常见键名：

| 键名 | 作用（社区口径） | 可信度 |
|---|---|---|
| `MILLET_NO_RESTRICT_APP` | 「免限制应用」列表 | 高（NotiGuard / FCMGuard 两个独立项目都在读写） |
| `power_proc_white_list` | 进程级白名单 | 中（多个 VOZ 分析帖，含 `settings put system --user 0 power_proc_white_list "pkg1,pkg2"`） |
| `power_service_white_list` / `power_pkg_white_list` / `perf_proc_protect_list` / `rt_pkg_white_list` | 服务/包/进程保护名单 | 低-中（同一批论坛帖，未在 HyperOS 3 交叉验证） |
| `com.miui.powerkeeper.cloudconfig` | Powerkeeper 云端下发白名单 | 中 |
| `/data/system/miui/powercfg.db` | 本地 sqlite | **低**（仅一处论坛帖提及，未经核实） |

**关键警告（必须写进 README）**：
1. **Powerkeeper 会周期性重写这些键**（FCMGuard 明确观察到 `MILLET_NO_RESTRICT_APP` 被重建、已加项被踢掉）。「写一次永久生效」是错的。
2. 改 `/data/system/miui/powercfg.db` 或删 `/data/system/powerkeeper/cloud/` 有**开机循环（bootloop）风险**；社区给的 `pm disable-user --user 0 com.miui.powerkeeper` 同样标注「可能导致 bootloop」。
3. **本项目建议**：只**读取**这些键做健康度展示，**不写入**。写入收益不确定、回滚成本高，而「设置 → 省电与电池 → 应用智能省电 → 无限制」是官方承认有效的。

```bash
# 只读诊断（root）
settings get system MILLET_NO_RESTRICT_APP
settings get system power_proc_white_list
```

---
## 4. Android 16 精确闹钟

### 4.1 两个权限的差异（官方对比表）

| | `SCHEDULE_EXACT_ALARM` | `USE_EXACT_ALARM` |
|---|---|---|
| 授予方式 | **用户授予**（特殊应用访问 →「闹钟和提醒」） | **安装即授予**，normal 权限 |
| 能否被撤销 | 用户可撤、系统可撤 | 用户**不可撤销** |
| 适用场景 | 更广 | 受限（闹钟 / 日历类） |
| Play 政策 | 无 | **有**（带此权限上架须符合政策语言） |
| 撤销后果 | **App 被杀死，所有未来精确闹钟被取消**；须监听 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 后重排 | — |

依据：https://developer.android.google.cn/develop/background-work/services/alarms/schedule （"Use exact alarm cases" 小节表格）+ https://developer.android.google.cn/about/versions/14/changes/schedule-exact-alarms

### 4.2 API 36 上的实际行为

- **Android 14 起**：目标 33+ 的新装应用，`SCHEDULE_EXACT_ALARM` **默认拒绝**（"denied by default"）；备份恢复到 Android 14 设备同样保持拒绝；老用户升级则保留预授权。**[官方]**
- **Android 15 / 16：没有新的精确闹钟行为变更**。逐条核对 `behavior-changes-15`、`behavior-changes-all`(15)、`behavior-changes-16`、`behavior-changes-all`(16) 四页（均 2026-10-28 更新），**无任何 alarm 条目**。targetSdk 36 在该事上继承 Android 14 规则，**没有额外收紧**。**[官方-核查结论]**
- **官方强制要求**：调 `setExact/setExactAndAllowWhileIdle/setAlarmClock` 前必须 `canScheduleExactAlarms()`，否则 `SecurityException`。**[官方]**
- **豁免名单（官方原文）**，满足任一即可无权限使用精确闹钟：
  1. 平台签名应用
  2. privileged app
  3. **在 power allowlist 上的应用**（可通过 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 申请）
  4. 预授权（如持有 `SYSTEM_WELLBEING` 角色）

  → **第 3 条是本项目的解法**（见 3.1 的 💎）。

### 4.3 `setExactAndAllowWhileIdle` 在 Doze 下的真实精度

| 事实 | 依据 |
|---|---|
| Doze 下**仍会触发**，不推迟到维护窗口（区别于 `setExact` / `setWindow`） | [官方] doze-standby |
| **每 App 每 9 分钟最多触发一次**（`setAndAllowWhileIdle` 与 `setExactAndAllowWhileIdle` 共享限制） | [官方] doze-standby 原文："Neither setAndAllowWhileIdle() nor setExactAndAllowWhileIdle() can fire alarms more than once per nine minutes, per app." |
| 精度定位是 "nearly precise"，不是绝对精确 | [官方] alarms/schedule 原文 "Invoke an alarm at a nearly precise time in the future, even if battery-saving measures are in effect" |
| 精确闹钟**不受 FGS 启动限制影响** | [官方] alarms/schedule 原文 "exact alarms aren't affected by foreground service launch restrictions" |
| `setAlarmClock()` 优先级最高：**系统永不调整触发时间**，会提前退出 Doze | [官方] alarms/schedule |

**对本项目的判断**：
- 每天只放 2–4 个闹钟（上下班 × 预热/主/兜底），**远低于「9 分钟一次」限制**，不构成问题。
- **[推断]** 「9 分钟」是 AOSP throttle；OEM 可能在框架层再加秒级偏差。**闹钟回调里不要假设 `now == triggerAtMillis`**，要读 `System.currentTimeMillis()` 重算「距目标还差多少」再决定等还是直接干。
- **[推断]** 若 HyperOS 在深度 Doze 里进一步压制，`setAlarmClock()` 是唯一还有官方「永不调整」承诺的选项（代价：状态栏出现闹钟图标）。可用于**用户可选的「极限模式」**。

### 4.4 「闹钟应用（clock app）类豁免」是否适用？

- **不适用**：`USE_EXACT_ALARM` 的「clock app」豁免是 **Google Play 政策层**的资格判定（"Apps will not be able to publish a version of their app with this permission unless they qualify based on the policy language"），不是系统运行时自动识别。
  - **[推断]** 它是 normal 权限，侧载安装即生效，系统不校验你是不是闹钟 App；
  - **但**这属于政策边缘，一旦未来走 Play 分发会被下架。
- **建议**：manifest 里两个都声明（现行代码已如此），运行时以 `canScheduleExactAlarms()` 为准；设置页把「电池无限制」作为第一优先级引导 —— 它是官方承认的可靠豁免，不依赖政策。

---

## 5. Android 16 前台服务

### 5.1 FGS 类型（targetSdk ≥ 34 强制）

官方要求：manifest 声明 `android:foregroundServiceType` + 申请对应 `FOREGROUND_SERVICE_*` 权限 + 满足该类型的运行时前置条件，否则 `SecurityException`。
依据：https://developer.android.google.cn/develop/background-work/services/fgs/service-types

| type | 权限 | 运行时前置 | 适配本项目 |
|---|---|---|---|
| **`specialUse`** | `FOREGROUND_SERVICE_SPECIAL_USE` | **无** | ✅ 现行选择。需配 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| `systemExempted` | `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | 需满足其一：Demo 模式 / DeviceOwner / ProfileOwner / Safety / ROLE_EMERGENCY / DeviceAdmin / **持有 `SCHEDULE_EXACT_ALARM` 或 `USE_EXACT_ALARM`** / VPN | ⚠️ 技术可行（我们确实持 exact alarm），但权限一被撤就抛 `ForegroundServiceTypeNotAllowedException`，语义偏系统集成，**不建议主用** |
| `shortService` | 无需专属权限（仍需 `FOREGROUND_SERVICE`） | 无 | ⚠️ 约 3 分钟硬超时，超时触发 ANR（"ANR 消息提及 FOREGROUND_SERVICE_TYPE_SHORT_SERVICE"）；**不能启动其他 FGS**；不支持粘性 |
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | 无 | ❌ targetSdk ≥ 35 有 6h/24h 超时，**且 `BOOT_COMPLETED` 里禁止启动** |
| `mediaProcessing` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | 无 | ❌ 同上 |
| `camera` / `microphone` / `location` / 传感器类 `health` | 各自权限 | while-in-use，**后台无法创建** | ❌ 直接 `SecurityException` |

**结论：继续用 `specialUse` 是对的** —— 它是唯一「零运行时前置 + 无时长上限 + 无 BOOT 限制」的通用类型。

### 5.2 Android 16 对「后台启动 FGS」的限制

- **没有新增限制**。Android 15 才是收紧点（BOOT_COMPLETED 类型限制 + SAW 豁免收窄）。
- Android 16 的新增是**间接的**：FGS 并发执行的 job 受 runtime quota（见 1.1）。**[官方]**

### 5.3 通知权限对 FGS 的影响

- **不需要 `POST_NOTIFICATIONS` 就能 `startForeground()`**；但用户拒绝通知权限后，FGS 通知**不出现在通知抽屉，只在「任务管理器」里可见**。**[官方]** https://developer.android.google.cn/develop/ui/views/notifications/notification-permission （原文："Apps don't need to request the POST_NOTIFICATIONS permission in order to launch a foreground service."）
- 所以：
  - FGS 本身**不会**因缺通知权限而失败；
  - 但**打卡结果通知（F4）会看不见** → 必须引导开通知权限，并叠加小米「后台发送本地通知」这一层（见 1.2）。

### 5.4 targetSdk 36 下，从 `BroadcastReceiver` 启动 FGS 是否允许？

**允许。** 官方豁免清单明确写着：

> *"Your app invokes an exact alarm to complete an action that the user requests."*
> — https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start

即：**闹钟触发的 `BroadcastReceiver` 里 `startForegroundService()` 是合法豁免**，前提是该广播确实来自 `AlarmManager` 的精确闹钟 PendingIntent（不是自己 `sendBroadcast` 自娱自乐）。

完整豁免清单（官方原文摘要）与本项目适配：

| 豁免条件 | 本项目可用 |
|---|---|
| App 从用户可见状态切换 | 仅手动打卡 |
| App 可后台启动 Activity | 否（我们刻意不申请） |
| 收到 FCM 高优先级消息 | 不适用 |
| 用户与应用 UI 元素交互（气泡 / 通知 / 小组件 / Activity） | 手动补打卡、通知「立即重试」✅ |
| **App 调用精确闹钟完成用户请求的动作** | ✅ **主路径** |
| App 是当前输入法 | 否 |
| 地理围栏 / 活动识别事件 | 否 |
| 收到 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` | ✅ 但受 5.1 类型限制（`specialUse` 不在禁列 → **[推断]** 可用） |
| 收到 `TIMEZONE_CHANGED` / `TIME_CHANGED` / `LOCALE_CHANGED` | ✅ 可用于重排闹钟 |
| NFC 事件 | 否 |
| DeviceOwner / ProfileOwner 等系统角色 | 否 |
| CompanionDeviceManager + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | 可用但过重 |
| **用户关闭了本 App 的电池优化** | ✅ 与会话 3 的「无限制」联动，双保险 |
| 持 `SYSTEM_ALERT_WINDOW` **且有可见悬浮窗**（targetSdk ≥ 35） | 需常驻悬浮窗，代价大，不推荐 |

> 落地建议：`PunchAlarmReceiver` 里 `startForegroundService` 外面**必须**单独 catch `ForegroundServiceStartNotAllowedException`（现行代码 catch 的是泛化异常，但这个类是「静默降级」的唯一信号，要单独记日志 + 落库）。

---

## 6. 锁屏上启动 Activity

### 6.1 Android 16 允不允许从后台启动 Activity？

**默认不允许；规则与 Android 10 起一致，Android 16 没有放宽。** 官方页面（2026 更新）：
https://developer.android.google.cn/guide/components/activities/background-starts

官方豁免（原文）：
1. App 有可见窗口（Activity 在前台）
2. App 是当前 IME
3. Activity 由**系统**发出的 PendingIntent 启动（如通知点击）
4. App 持有用户授予的 `SYSTEM_ALERT_WINDOW`
5. App 持有 `START_ACTIVITIES_FROM_BACKGROUND`（**signature / privileged**，普通 App 拿不到）
6. App 被一个拥有 BAL 权限的 Service 绑定
7. 由 Launcher 发起
8. 系统核心组件（如 Telephony 来电界面）

失败表现：**不抛异常**，只在 Logcat 打 `Background activity launch blocked!`（tag `ActivityTaskManager`）。这是最坑的地方 —— 必须靠 logcat / `dumpsys` 验证，不能靠 try-catch。

### 6.2 Android 14 / 15 追加的 PendingIntent 双 opt-in

| 版本 | 要求 | API |
|---|---|---|
| targetSdk ≥ 34 | **发送方**必须显式 opt-in，否则默认不贡献自己的 BAL 特权 | `ActivityOptions.setPendingIntentBackgroundActivityStartMode(...)`，推荐 `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`（SDK 36 新增，更严格） |
| targetSdk ≥ 35 | **创建方**也必须显式 opt-in | `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` |

→ **targetSdk 36 下，用 `PendingIntent.getActivity(...)` + 通知点击拉起钉钉，需要创建方和发送方都 opt-in 才稳。** 我们两个角色都是自己，可以两边都设。**[官方]**

### 6.3 四种方案对比

| 方案 | 解决「能否启动」？ | 解决「能否盖在锁屏上」？ | targetSdk 36 结论 |
|---|---|---|---|
| `android:showWhenLocked="true"` + `turnScreenOn="true"`（或 `setShowWhenLocked/setTurnScreenOn`） | ❌ **完全不解决**。它只让**已经在运行的** Activity 在锁屏之上保持 resumed 可见 | ✅ | **单独使用 = 无效**。官方原文："Normally an activity will be transitioned to the stopped state if it is started while the lockscreen is up, but with this flag set the activity will remain in the resumed state visible on-top of the lock screen."（`Activity.java`） |
| `FLAG_SHOW_WHEN_LOCKED`（旧 Window flag） | ❌ 同上 | ✅（已被 `showWhenLocked` 取代） | 同上，且已 deprecated |
| **全屏 Intent（FSI）** `setFullScreenIntent(pi, true)` | ✅ **能** —— 属「系统发出的 PendingIntent」路径 | ✅ 锁屏直接拉起 Activity（闹钟/来电的标准做法） | 需 `USE_FULL_SCREEN_INTENT`；**Android 14 起默认只授予通话/闹钟类应用**，其余需用户在「特殊应用访问 → 管理全屏 intent」手动开；被拒时锁屏只显示 60s 的带药丸按钮 HUN，不拉 Activity |
| `SYSTEM_ALERT_WINDOW` 悬浮窗 | ✅ 能（顺带满足 5.4 的 FGS 豁免） | ✅ 悬浮窗本就在锁屏之上（受各 ROM 策略影响） | 需用户手动开「显示在其他应用上层」；**HyperOS 上还会被「后台弹出界面」权限二次拦截** |
| **root `su -c am start`** | ✅ **最可靠** | ✅（解锁后直接可见） | **不受 BAL 约束** —— AOSP 对 `Process.ROOT_UID` 直接返回 `BAL_ALLOW_ALLOWLISTED_UID`；`com.android.shell` 自身也持 `START_ACTIVITIES_FROM_BACKGROUND` |
| 普通 `startActivity`（App 自己） | ❌ 被拦 | — | 见 6.1 |

依据：
- BAL 规则与豁免：https://developer.android.google.cn/guide/components/activities/background-starts **[官方]**
- `showWhenLocked` 语义：AOSP `core/java/android/app/Activity.java` 的 `setShowWhenLocked` / `setTurnScreenOn` javadoc **[官方-源码]**
- FSI 限制：https://source.android.google.cn/docs/core/permissions/fsi-limits **[官方]**；其验证矩阵明确列出「已锁定屏幕 + 用户已授予 → 启动 FSI；被拒绝 → 只显示 60s HUN」
- ROOT_UID 豁免：AOSP `services/core/java/com/android/server/wm/BackgroundActivityStartController.java`，`checkBackgroundActivityStartAllowedByCallerInBackground()` 首段：
  ```java
  if (state.mCallingUid == Process.ROOT_UID
          || callingAppId == Process.SYSTEM_UID
          || callingAppId == Process.NFC_UID) {
      return new BalVerdict(BAL_ALLOW_ALLOWLISTED_UID, /*background*/ false, "Important callingUid");
  }
  ```
  **[官方-源码]**
- Shell 权限：AOSP `packages/Shell/AndroidManifest.xml` 含 `START_ACTIVITIES_FROM_BACKGROUND`、`INJECT_EVENTS`、`DEVICE_POWER`、`CONTROL_KEYGUARD`、`WRITE_SECURE_SETTINGS` **[官方-源码]**

### 6.4 HyperOS 3 上最可靠的方案（按可靠性排序）

1. **root `am start`（推荐主路径）**
   ```bash
   su -c 'monkey -p com.alibaba.android.rimet -c android.intent.category.LAUNCHER 1'
   # 或已知 Activity 名：
   su -c 'am start -n com.alibaba.android.rimet/com.alibaba.android.rimet.biz.LauncherActivity'
   ```
   - 调用者 uid=0 → 跳过 BAL；
   - 不经过我们自己的 Activity，**App 本身完全不需要 BAL 豁免**，也不需要 `SYSTEM_ALERT_WINDOW`；
   - 不受小米「后台弹出界面」权限约束（**[推断]**：MIUI 的 appops 检查对 uid 0 通常直接放行，但需在实机上用 `cmd appops get` 对照确认）。
2. **FSI（无 root 兜底）**：`USE_FULL_SCREEN_INTENT` + `NotificationManager.canUseFullScreenIntent()` 检查 + 高重要性渠道。但会叠加小米「后台发送本地通知」默认拒绝，成功率取决于两个开关。
3. **SAW 悬浮窗**：能过 BAL，但多一层小米「后台弹出界面」权限，且常驻悬浮窗耗电、体验差。**不推荐**。
4. **`showWhenLocked` 单独用**：**无效**，只在我们自己的 Activity 已被成功拉起之后才有意义。

> ⚠️ **重要认知**：钉钉本身不是 `showWhenLocked` 应用。**在锁屏之上启动钉钉，钉钉会被 keyguard 遮住，等于没启动**（Activity 会 resume 但被锁屏覆盖，点击也点不到）。所以**「解锁」是流程必经步骤，不能省**。现行 F3 的顺序（wakeup → 解锁 → 启动钉钉）是对的。

### 6.5 解锁本身怎么做最稳

| 方式 | 效果 | 结论 |
|---|---|---|
| `KeyguardManager.requestDismissKeyguard(activity, cb)` | 无密码 / 可信状态 → 立即关闭；**有密码 → 只是把 bouncer（密码框）弹出来**，不替你输 | 不能替代输入密码 |
| `su -c wm dismiss-keyguard` | 走 `IWindowManager.dismissKeyguard()`（需 `CONTROL_KEYGUARD`，shell 有）；AOSP `WindowManagerService.dismissKeyguard` 里 `checkCallingPermission(CONTROL_KEYGUARD)` 通过后调 `KeyguardViewMediator.dismiss()` | **[推断]** 对 secure keyguard 同样只弹 bouncer。可作为「先让密码框出现」的可靠手段 |
| `su -c input swipe 540 1800 540 600 300` | 上滑唤出 bouncer | ✅ 现行做法，保留 |
| `su -c input text <PIN>` + `input keyevent 66` | 输入数字密码 | ✅ 现行做法，保留 |
| `su -c locksettings ...` | **无 unlock 子命令** | ❌ 不存在，别写 |

**[推断 / 待验证]**：HyperOS 3 锁屏上滑坐标、是否需先点一下密码输入区、「上滑 → bouncer → 输密码」的时序（建议每步后轮询 `dumpsys window | grep mCurrentFocus` 等焦点变化，而不是固定 sleep）。

---

## 7. `KEYCODE_WAKEUP` vs `PowerManager.wakeUp`

| 维度 | `PowerManager.wakeUp(...)` | `KEYCODE_WAKEUP` (224) via `input keyevent 224` |
|---|---|---|
| 普通 App 可用性 | ❌ **不可用**。`wakeUp(long)` 标注 `@removed Requires signature permission`；公开的 `wakeUp(long,int,String)` 是 `@hide` + `DEVICE_POWER`（signature\|privileged） | ✅ 可用（`input` 需 `INJECT_EVENTS`，shell/root 有） |
| 调用链 | `PowerManagerService.wakeUp()` → 直接改电源状态，走 Wakefulness 状态机 | `InputManager.injectInputEvent(KEYCODE_WAKEUP)` → `PhoneWindowManager` 的按键处理 → `wakeUpFromWakeKey` → 最终同样落到 PowerManagerService |
| 语义 | **只点亮，不 toggle**；可带 `WAKE_REASON`（如 `WAKE_REASON_APPLICATION`）与 details 字符串，便于 `dumpsys power` 追溯 | 同样**只唤醒不 toggle**（与 `KEYCODE_POWER`(26) 的 toggle 不同）。但按键事件会先经过窗口/输入策略（锁屏手势、口袋模式、抬手亮屏） |
| 锁屏影响 | **都不解锁**。设备 asleep → awake，锁屏仍在 | 同 |
| 熄屏 | `goToSleep()` 同样 `@hide` + `DEVICE_POWER`，普通 App 不可用 | `input keyevent 223`(KEYCODE_SLEEP)；**不要用 26**（toggle，会把亮的按灭） |

**结论**：
1. **App 内不要调用 `PowerManager.wakeUp()`** —— 否则 `SecurityException`。现行 `RootShell.wakeUp()` 用的是 `input keyevent 224`，**这条是对的**。若别处写了 `pm.wakeUp(...)`，必须移除。
2. root 通道下 `input keyevent 224` 是唯一可行且语义正确的点亮方式。
3. `cmd power` **没有** `wakeup` / `sleep` 子命令（已核对 AOSP `PowerManagerShellCommand`：只有 `set-mode`、`set-prox`、`suppress-ambient-display`、`set-adaptive-power-saver-enabled` 等）—— **不要写 `cmd power wakeup`**，那是不存在的命令。

---

## 8. 对现有代码的直接影响（差异清单）

| 文件 / 行为 | 现状 | 需要改 |
|---|---|---|
| `AlarmScheduler.canScheduleExact()` | 直接读 `canScheduleExactAlarms()` | 前面加一层 `isIgnoringBatteryOptimizations()`；若已在省电白名单，即使 `canScheduleExactAlarms()` 为 false 也可走 `setExactAndAllowWhileIdle`（官方豁免），但仍要 try-catch `SecurityException` 兜底 |
| `PunchAlarmReceiver` 的 `startForegroundService` | catch 了泛化异常 | 单独 catch `ForegroundServiceStartNotAllowedException`，写明确日志 + 落库（这是**静默保活失效**的唯一信号） |
| `PunchForegroundService` `specialUse` 类型 | 已正确 | 保留。不要改成 `dataSync`（6h 超时 + BOOT 禁启） |
| 通知（F4） | 只考虑 `POST_NOTIFICATIONS` | 追加小米「后台发送本地通知」引导；`PunchNotifier` 需检查 `areNotificationsEnabled()`，失败时把结果写进日志页 |
| `HealthCheck` 电池项 | `isIgnoringBatteryOptimizations` | 追加「自启动无法自动检测」的明示；root 下用 `dumpsys deviceidle whitelist` 做实证 |
| 解锁链 | `input swipe` + `input text` + `66` | 每步加「等 `mCurrentFocus` 变化」的轮询，而不是固定 sleep；密码框可能要先用 `input tap` 点到 |
| 启动钉钉 | root `monkey` / `am start` | 保留，并明确这是**唯一不受 BAL 约束**的路径；解析输出里的 `Error:` 判失败 |
| `PunchAccessibilityService` | 用作首选点击通道 | 无障碍服务**不提供 BAL 豁免**（不是官方豁免项）；它的价值在「点击」，不在「拉起」 |
| 任何 `PowerManager.wakeUp()` | 若存在 | **删除**（SecurityException） |

---

## 9. 必须在真机上验证的清单（本轮无设备，未实测）

1. `canScheduleExactAlarms()` 在「电池无限制」已开、`SCHEDULE_EXACT_ALARM` 已拒时返回什么？（预期 true，来自 power allowlist 豁免）
2. `miui.intent.action.OP_AUTO_START` 与 `AutoStartManagementActivity` 在 HyperOS 3 上 `queryIntentActivities` 是否非空；`AppPermissionsEditorActivity` 是否仍存在。
3. `cmd appops get <pkg>` 在 HyperOS 3 上能列出哪些 MIUI 私有 op；`10021`（后台弹出界面）默认值是否 `deny`；是否存在自启动对应 op。
4. `dumpsys deviceidle whitelist +<pkg>` 后 `isIgnoringBatteryOptimizations()` 是否立即为 true，重启后是否保持。
5. `settings get system MILLET_NO_RESTRICT_APP` / `power_proc_white_list` 在 HyperOS 3 上是否存在、格式如何；设一次「无限制」后是否变化。
6. 后台本地通知：App 切后台后 `dumpsys notification --noredact` 看通知是否真入队；「后台发送本地通知」关闭时 FGS 常驻通知是否也被拦。
7. 锁屏解锁：上滑坐标、bouncer 出现时机、`input text` 是否需先 tap 密码框、是否需要 `keyevent 66`。
8. BAL 日志：`adb logcat -s ActivityTaskManager` 里确认 root `am start` 路径上**没有** `Background activity launch blocked`。
9. 精确闹钟实际误差：`adb shell dumpsys alarm | grep -A5 <pkg>` 看 `when=` 与 `origWhen=`；连续观察 3 天记录偏差分布（预期 ±1s，深度 Doze 下可能 ±10s）。
10. HyperOS 3 是否新增冻结策略：App 置后台 8h 后 FGS 是否还在（`dumpsys activity services <pkg>`）。

---

## 10. 参考链接汇总

**Google 官方**
- 行为变更（targeting 16）：https://developer.android.google.cn/about/versions/16/behavior-changes-16
- 行为变更（all apps 16）：https://developer.android.google.cn/about/versions/16/behavior-changes-all
- 行为变更（targeting 15）：https://developer.android.google.cn/about/versions/15/behavior-changes-15
- 行为变更（all apps 14）：https://developer.android.google.cn/about/versions/14/behavior-changes-all
- 前台服务变更总览：https://developer.android.google.cn/develop/background-work/services/fgs/changes
- FGS 后台启动限制与豁免：https://developer.android.google.cn/develop/background-work/services/fgs/restrictions-bg-start
- FGS 类型表：https://developer.android.google.cn/develop/background-work/services/fgs/service-types
- 启动前台服务：https://developer.android.google.cn/develop/background-work/services/fgs/launch
- 后台启动 Activity（BAL）：https://developer.android.google.cn/guide/components/activities/background-starts
- 调度闹钟：https://developer.android.google.cn/develop/background-work/services/alarms/schedule
- 精确闹钟默认拒绝（Android 14）：https://developer.android.google.cn/about/versions/14/changes/schedule-exact-alarms
- Doze / App Standby：https://developer.android.google.cn/training/monitoring-device-state/doze-standby
- 通知运行时权限：https://developer.android.google.cn/develop/ui/views/notifications/notification-permission
- 时效性通知 / FSI：https://developer.android.google.cn/develop/ui/views/notifications/time-sensitive

**AOSP**
- 全屏 intent 限制：https://source.android.google.cn/docs/core/permissions/fsi-limits
- `services/core/java/com/android/server/wm/BackgroundActivityStartController.java`（ROOT_UID 豁免）
- `core/java/android/app/Activity.java`（`setShowWhenLocked` / `setTurnScreenOn`）
- `core/java/android/os/PowerManager.java`（`wakeUp` 的 `@removed Requires signature permission`）
- `services/core/java/com/android/server/wm/WindowManagerService.java`（`dismissKeyguard` 需 `CONTROL_KEYGUARD`）
- `services/core/java/com/android/server/wm/WindowManagerShellCommand.java`（`wm dismiss-keyguard`）
- `services/core/java/com/android/server/power/PowerManagerShellCommand.java`（**无 wakeup/sleep 子命令**）
- `packages/Shell/AndroidManifest.xml`（shell 的权限集）

**小米官方**
- Powerkeeper 管控说明：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1628
- 自启动权限管理说明：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624
- 后台弹出页面权限管理说明：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625
- 后台发送本地通知权限管理说明：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626

**社区**
- MIUI 自启动 action（SO）：https://stackoverflow.com/questions/55258440/
- 无法检测自启动（SO）：https://stackoverflow.com/questions/42347547
- AutoStarter（Xiaomi 组件名事实标准）：https://github.com/judemanutd/AutoStarter
- android_autostart（Flutter，同组件名）：https://pub.dev/documentation/android_autostart/latest/index.html
- ChinesePermissionUtils：https://github.com/leanderYu/ChinesePermissionUtils
- MIUI op 表（`OP_BACKGROUND_START_ACTIVITY = 10021`）：https://github.com/MuntashirAkon/AppManager/issues/1379
- Monik（Shizuku 版后台管控，含 deviceidle whitelist / standby bucket）：https://github.com/vovaxa55/Monik
- NotiGuard（HyperOS 上 `MILLET_NO_RESTRICT_APP` 会被重建）：https://github.com/Liuchijiang/NotiGuard
- FCMGuard-Plus-For-HyperOS（同上）：https://github.com/rsps1008/FCMGuard-Plus-For-HyperOS4

---

## 11. 一句话总结

> 这套方案能跑，但**不是靠「申请更多权限」跑起来的，是靠「绕开平台限制 + 让用户手点 4 个开关」跑起来的**。
> 精确闹钟靠「电池无限制」这条官方豁免合法化；拉起钉钉靠 root（uid 0）绕开 BAL；FGS 用 `specialUse` 避开所有类型超时；`showWhenLocked` 只是锦上添花、不是启动手段；`PowerManager.wakeUp()` 根本不可用，只能用 `input keyevent 224`。
> 真正的失败面不在代码里，在小米那三个「默认拒绝」的开关（自启动 / 后台弹页面 / 后台发通知）上 —— 所以 README 的配置清单比任何保活技巧都重要。
