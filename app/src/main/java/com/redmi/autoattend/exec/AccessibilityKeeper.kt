package com.redmi.autoattend.exec

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 用 root 保活无障碍服务。
 *
 * ## 为什么需要这个
 *
 * 实测你这台 K90：无障碍服务会被系统反复关掉。
 * 常见触发点：
 * 1. `am force-stop` 会撤销无障碍绑定（Android 设计，重装 APK 就触发）；
 * 2. HyperOS 省电策略在极端情况下清理；
 * 3. 某些安全类应用会主动断开第三方无障碍。
 *
 * 无障碍一旦掉线，打卡就只剩 root 通道 —— 而 root 通道读不到钉钉
 * 的 WebView 内容（uiautomator 在这个页面上永远拿不到 idle），
 * 于是**打卡成功也会被判失败**。所以保住无障碍是刚需。
 *
 * ## root 能做什么
 *
 * 有 root 就可以直接写 secure settings 重新启用服务：
 * ```
 * settings put secure enabled_accessibility_services <list>
 * settings put secure accessibility_enabled 1
 * ```
 * 这两条是**官方支持的开关**，不是 hack —— 系统设置里点开关走的就是这个。
 *
 * ## 诚实边界
 *
 * - 写 settings 只重建"已启用"状态，系统仍需重新 bind 服务，
 *   通常 1~3 秒内生效，但**不保证瞬时**；
 * - 如果系统把服务进程本身拉黑，写 settings 也没用；
 * - 我们只做**低频自愈**（每 15 分钟检查一次），不做激进轮询拉起，
 *   避免触发 HyperOS 的异常行为检测。
 */
object AccessibilityKeeper {

    private const val TAG = "A11yKeeper"

    /** 本应用的无障碍服务组件名 */
    private const val SERVICE_CLASS = "com.redmi.autoattend.exec.PunchAccessibilityService"

    /** 当前是否已被系统启用 */
    fun isEnabled(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()

        if (raw.isEmpty()) return false
        val target = context.packageName + "/" + SERVICE_CLASS
        // 系统存的形式可能是全名或短名，两种都认
        return raw.split(":")
            .filter { it.isNotEmpty() }
            .any { it == target || it.endsWith(SERVICE_CLASS) }
    }

    /**
     * 尝试把本应用的无障碍服务重新打开。
     *
     * @return true 表示已经处于启用状态（无需操作或已修复）
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context)) return true

        if (!RootShell.isAvailable()) {
            Log.i(TAG, "无障碍未启用且 root 不可用，无法自愈")
            return false
        }

        val target = context.packageName + "/" + SERVICE_CLASS

        // 读出当前已启用的服务列表（可能包含其他无障碍 App，不能覆盖掉）
        val current = RootShell.exec(
            "settings get secure enabled_accessibility_services",
            5_000,
        ).trim()

        val combined = when {
            current.isEmpty() || current == "null" -> target
            current.contains(target) -> return true
            else -> current + ":" + target
        }

        // 用单引号包裹，避免冒号被 shell 解释
        RootShell.exec(
            "settings put secure enabled_accessibility_services '" + combined + "'",
            5_000,
        )
        RootShell.exec("settings put secure accessibility_enabled 1", 5_000)

        // 给系统一点时间重新 bind。
        //
        // 【启动性能】这里原来是 Thread.sleep(1_200) —— 一个纯阻塞的 1.2 秒。
        // 如果调用方在主线程（BootReceiver / KeepAliveService 的某些路径），
        // 就会直接卡住界面 1.2 秒。
        //
        // 改成轮询等待：每 150ms 查一次，一旦生效立刻返回。
        // 大多数情况下系统 200~400ms 内就 bind 好了，平均等待远低于 1.2 秒。
        // 注意调用方必须确保自己在后台线程（见 BootReceiver 的 goAsync 改造）。
        val deadline = System.currentTimeMillis() + 1_800L
        while (System.currentTimeMillis() < deadline) {
            if (isEnabled(context)) break
            runCatching { Thread.sleep(150) }
        }

        val ok = isEnabled(context)
        Log.i(TAG, if (ok) "无障碍已通过 root 重新启用" else "无障碍自动恢复失败，需要手动开启")
        return ok
    }

    /** 跳转到无障碍设置页（用户手动开启用） */
    fun openSettings(context: Context) {
        runCatching {
            val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "打开无障碍设置失败: " + it.javaClass.simpleName) }
    }
}
