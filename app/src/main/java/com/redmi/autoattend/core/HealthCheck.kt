package com.redmi.autoattend.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.redmi.autoattend.exec.PunchAccessibilityService
import com.redmi.autoattend.exec.RootShell

/**
 * 保活与权限健康度。
 *
 * 诚实原则（需求 F5 与"风险与诚实边界"）：
 * - 只报告"检测到的事实"，不承诺"绝对不死"；
 * - 每一项都给出可操作的跳转按钮；
 * - 检测不到时明确说"无法确认"，而不是假装正常。
 */
object HealthCheck {

    private const val TAG = "HealthCheck"

    /** 单项检测结果 */
    data class Item(
        val key: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
        val canFix: Boolean,
        /**
         * 该项无法被程序检测（不是"未通过"）。
         *
         * 引入这个状态的背景：自启动在 Android 上没有任何可读 API，
         * 之前把它硬编码成 ok=false，导致用户明明开了自启动，
         * 界面却显示"未开启"并计入问题数 —— 这是**误报**，比不显示更糟。
         *
         * neutral = true 的项：显示灰色圆点、不计入问题数、不参与 allOk。
         */
        val neutral: Boolean = false,
    )

    /** 整体健康度 */
    data class Report(
        val items: List<Item>,
        val alarmScheduled: Boolean,
        val nextSlotText: String?,
    ) {
        /** 已确认正常的项（不含无法检测的） */
        val knownItems: List<Item> get() = items.filter { !it.neutral }

        /** 是否全部正常（无法检测的项不参与判定，不假装它们通过） */
        val allOk: Boolean
            get() = knownItems.all { it.ok } && alarmScheduled

        /** 有问题的项（只含能确定失败的，不含无法检测的） */
        val problems: List<Item> get() = knownItems.filter { !it.ok }
    }

    /**
     * 执行一次完整检测。
     *
     * 注意：这个函数会做一些跨进程调用（root 探测、无障碍状态），
     * 应该在 IO 线程调用。
     */
    fun check(context: Context, nextSlot: NextPunchSlot?): Report {
        val items = mutableListOf<Item>()

        // 1. 精确闹钟（注意：电池白名单是官方豁免，见 AlarmScheduler.exactStatus）
        val (exactAlarm, exactDetail) = AlarmScheduler.exactStatus(context)
        items.add(
            Item(
                key = "exact_alarm",
                label = "精确闹钟",
                ok = exactAlarm,
                detail = if (exactAlarm) exactDetail else "$exactDetail，打卡可能延迟",
                canFix = !exactAlarm,
            )
        )

        // 2. 通知权限
        val notifOk = notificationEnabled(context)
        items.add(
            Item(
                key = "notification",
                label = "通知权限",
                ok = notifOk,
                detail = if (notifOk) "已开启，结果可送达" else "已关闭，打卡结果无法提醒",
                canFix = !notifOk,
            )
        )

        // 3. 无障碍服务
        val accOk = PunchAccessibilityService.isReady()
        items.add(
            Item(
                key = "accessibility",
                label = "无障碍服务",
                ok = accOk,
                detail = if (accOk) "在线，可用无障碍通道" else "未开启，将只能依赖 root 通道",
                canFix = !accOk,
            )
        )

        // 4. root 可用性
        val rootOk = RootShell.isAvailable()
        items.add(
            Item(
                key = "root",
                label = "Root 授权",
                ok = rootOk,
                detail = if (rootOk) "可用，解锁与点击更可靠" else "不可用，仅能靠无障碍",
                canFix = !rootOk,
            )
        )

        // 5. 电池优化白名单（同时是精确闹钟的官方豁免，优先级很高）
        val batteryOk = isIgnoringBatteryOptimizations(context)
        items.add(
            Item(
                key = "battery",
                label = "电池无限制",
                ok = batteryOk,
                detail = if (batteryOk) {
                    "已加入白名单，同时豁免精确闹钟限制"
                } else {
                    "未加入：后台可能被清理，精确闹钟也会受限"
                },
                canFix = !batteryOk,
            )
        )

        // 6. 钉钉是否安装
        val dingtalkOk = isDingTalkInstalled(context)
        items.add(
            Item(
                key = "dingtalk",
                // 标签必须跟随实际结果。
                // 之前写死成"钉钉已安装"，于是没装钉钉时会出现
                // 标题说"已安装"、正文说"未检测到"的自相矛盾。
                label = if (dingtalkOk) "钉钉已安装" else "钉钉未安装",
                ok = dingtalkOk,
                detail = if (dingtalkOk) {
                    "已安装，可正常拉起"
                } else {
                    "未检测到钉钉，打卡无法进行。请先安装钉钉。"
                },
                canFix = false,
            )
        )

        // 6.5 自启动（尽量真实检测，查不出来才标中性）
        val autostartState = detectAutostart(context)
        items.add(
            Item(
                key = "autostart",
                label = when (autostartState) {
                    AutostartState.ENABLED -> "自启动"
                    AutostartState.DISABLED -> "自启动未开启"
                    AutostartState.UNKNOWN -> "自启动（需自行确认）"
                },
                ok = autostartState == AutostartState.ENABLED,
                // 只有明确"未开启"才算问题；查不出来时保持中性，不误报。
                neutral = autostartState == AutostartState.UNKNOWN,
                detail = when (autostartState) {
                    AutostartState.ENABLED ->
                        "已确认开启，重启后闹钟会自动恢复"
                    AutostartState.DISABLED ->
                        "确认未开启。重启后闹钟不会恢复，务必打开。"
                    AutostartState.UNKNOWN ->
                        "系统未提供读取接口，且 root 查询未命中。" +
                            "若已开启可忽略；关闭时重启后闹钟不会恢复。"
                },
                canFix = autostartState != AutostartState.ENABLED,
            )
        )

        // 7. 锁屏密码是否配置
        val pwdOk = AppConfig.hasPassword.value
        items.add(
            Item(
                key = "password",
                label = "锁屏密码",
                ok = pwdOk,
                detail = if (pwdOk) "已配置，可自动解锁" else "未配置，锁屏下无法自动进入桌面",
                canFix = !pwdOk,
            )
        )

        // 8. 下一个闹钟
        val alarmScheduled = nextSlot != null
        val nextText = nextSlot?.let {
            val label = when (it.kind) {
                com.redmi.autoattend.data.db.PunchKind.CLOCK_IN -> "上班"
                com.redmi.autoattend.data.db.PunchKind.CLOCK_OUT -> "下班"
                com.redmi.autoattend.data.db.PunchKind.MANUAL -> "手动"
            }
            label + " " + PunchSchedule.formatTime(it.targetAtMillis)
        }

        return Report(items = items, alarmScheduled = alarmScheduled, nextSlotText = nextText)
    }

    // ---------- 单项查询 ----------

    fun notificationEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        return nm.areNotificationsEnabled()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isDingTalkInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.alibaba.android.rimet", 0)
        true
    }.getOrDefault(false)

    // ---------- 跳转 ----------

    /** 跳到本应用的通知设置 */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStart(context, intent)
    }

    /** 跳到无障碍设置 */
    fun openAccessibilitySettings(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 跳精确闹钟设置 */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** 请求忽略电池优化 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * 跳到 HyperOS 自启动管理页。
     *
     * 5 级 fallback（成功率从高到低）：
     *   1. 小米自启动管理 Activity（最直接）
     *   2. 应用权限编辑页（部分 HyperOS 版本把自启动并入这里）
     *   3. miui.intent.action.OP_AUTO_START 隐式 action
     *   4. 应用详情页（一定有）
     *   5. 应用列表页（最后的兜底）
     *
     * 每一级都先 queryIntentActivities 确认可解析，再 startActivity——
     * 直接 start 失败会抛 ActivityNotFoundException，在 MIUI 上还可能被
     * 安全中心自己拦一次，先 query 能省掉一轮异常。
     *
     * 注意：AutoStartManagementActivity **不接收定位 extra**；
     * extra_pkgname 属于 PermissionsEditorActivity（另一个页面）。
     */
    fun openAutoStartSettings(context: Context) {
        if (!isMiuiOrHyperOs()) {
            // 非小米设备：直接给应用详情页，别乱跳不存在的组件
            Log.i(TAG, "非 MIUI/HyperOS，跳应用详情页")
            openAppDetails(context)
            return
        }

        val candidates = listOf(
            // 1. 自启动管理
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
            // 2. 应用权限编辑（带包名 extra）
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
            ).putExtra("extra_pkgname", context.packageName),
            // 3. 隐式 action
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            // 4. 省电策略页
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
            ),
        )

        for ((index, intent) in candidates.withIndex()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(context, intent) && tryStart(context, intent)) {
                Log.i(TAG, "自启动管理页跳转成功（第 " + (index + 1) + " 级）")
                return
            }
        }

        Log.w(TAG, "自启动页全部失败，退到应用详情")
        openAppDetails(context)
    }

    /**
     * 尝试**真实检测**自启动状态。
     *
     * Android 官方没有任何 API 能读这个值，所以早先只能标成"无法检测"。
     * 但小米/红米上其实有两条可用的探查路径（都需要 root）：
     *
     * 1. **MIUI 系统设置数据库**：PowerKeeper 把自启动白名单存在
     *    `/data/data/com.miui.powerkeeper/databases/powerkeeper.db` 里，
     *    查 `whiteList` 表能看我们的包在不在。
     *    但直接读数据库有风险（PowerKeeper 会周期性重写），所以只读不写。
     *
     * 2. **应用权限记录**：`dumpsys appops` 里 MIUI 会记录
     *    `RUN_IN_BACKGROUND` / `BOOT_COMPLETED` 的授权状态。
     *
     * 两条都不保证 100% 准确（ROM 版本差异大），所以返回三态：
     * - ENABLED：确认已开启
     * - DISABLED：确认未开启
     * - UNKNOWN：查不出来（此时如实告知，而不是假装"没开"）
     */
    enum class AutostartState { ENABLED, DISABLED, UNKNOWN }

    fun detectAutostart(context: Context): AutostartState {
        val pkg = context.packageName

        // 路径 1：MIUI 自启动白名单（需要 root）
        if (RootShell.isAvailable()) {
            val fromDb = queryMiuiAutostartFromDb(pkg)
            if (fromDb != AutostartState.UNKNOWN) return fromDb

            // 路径 2：appops 里的自启动授权
            val fromOps = queryAutostartFromAppOps(pkg)
            if (fromOps != AutostartState.UNKNOWN) return fromOps
        }

        return AutostartState.UNKNOWN
    }

    /** 从 PowerKeeper 数据库读自启动白名单（只读，不改） */
    private fun queryMiuiAutostartFromDb(pkg: String): AutostartState {
        val out = RootShell.exec(
            "sqlite3 /data/data/com.miui.powerkeeper/databases/powerkeeper.db " +
                "'select pkgName from whiteList' 2>/dev/null",
            8_000,
        )
        if (out.isBlank() || out.contains("not found") || out.contains("Error")) {
            return AutostartState.UNKNOWN
        }
        return if (out.lineSequence().any { it.trim() == pkg }) {
            AutostartState.ENABLED
        } else {
            // 表里能读到内容但没有我们的包 —— 但不能直接断定"没开"，
            // 因为有些版本的白名单在别的表里。保守返回 UNKNOWN。
            AutostartState.UNKNOWN
        }
    }

    /** 从 appops 读后台启动授权 */
    private fun queryAutostartFromAppOps(pkg: String): AutostartState {
        val out = RootShell.exec("appops get " + pkg + " RUN_IN_BACKGROUND", 8_000)
        if (out.isBlank()) return AutostartState.UNKNOWN
        return when {
            out.contains("allow", ignoreCase = true) -> AutostartState.ENABLED
            out.contains("deny", ignoreCase = true) -> AutostartState.DISABLED
            else -> AutostartState.UNKNOWN
        }
    }

    /** 判断是否小米系 ROM */
    private fun isMiuiOrHyperOs(): Boolean {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val hasMiuiProp = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            val raw = get.invoke(null, "ro.miui.ui.version.name") as? String
            raw.isNullOrBlank().not()
        }.getOrDefault(false)
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            manufacturer.contains("xiaomi") ||
            hasMiuiProp
    }

    /** 组件/action 是否可解析 */
    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /** 跳到应用详情页 */
    fun openAppDetails(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** 用 root 把应用加入 Doze 白名单 */
    fun addToBatteryWhitelistViaRoot(context: Context): Boolean {
        if (!RootShell.isAvailable()) return false
        val ok = RootShell.addToDeviceIdleWhitelist(context.packageName)
        if (ok) {
            Log.i(TAG, "已通过 root 加入 Doze 白名单")
        }
        return ok
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun safeStart(context: Context, intent: Intent, fallbackToDetails: Boolean = true) {
        if (tryStart(context, intent)) return
        if (fallbackToDetails) openAppDetails(context)
    }
}