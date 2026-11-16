package com.dingdinggo.app.exec

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
    private const val SERVICE_CLASS = "com.dingdinggo.app.exec.PunchAccessibilityService"

    /**
     * 当前是否已被系统启用。
     *
     * 【审计 C-04 修复】必须同时检查 `accessibility_enabled` 总开关。
     *
     * 旧实现只看 `enabled_accessibility_services` 名单里有没有我们。
     * 但用户在系统设置里关掉「无障碍」总开关时，**名单本身不会被清空** ——
     * 于是 isEnabled() 返回 true、ensureEnabled() 直接 return true，
     * 自愈永远空转，而且日志一声不响。
     * 同工程的 HealthCheck 却是两个都查的，两处判据互相矛盾。
     *
     * 现在统一为"总开关打开 **且** 我们在名单里"才是真的启用。
     */
    fun isEnabled(context: Context): Boolean {
        val master = runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            )
        }.getOrDefault(0)
        // 总开关关闭时，名单里的残留项并不代表服务在运行
        if (master != 1) return false

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
     * 把 `settings get` 的输出净化成合法的服务列表。
     *
     * 【审计 C-03 修复】RootShell.exec 在超时时会把 `\n[timeout]` 追加到输出末尾
     * （见 RootShell.exec 的 sb.append）—— 旧代码把这个原始字符串直接拼进
     * `settings put secure enabled_accessibility_services`，后果是：
     *
     *   1. 把 `[timeout]` 当成一个"服务名"写进**系统级**无障碍列表；
     *   2. 同时可能已经覆盖掉了其它 App 的无障碍项（组合时基于脏数据）；
     *   3. 自己下次 isEnabled() 仍是 true → 静默失败，没人知道写坏了。
     *
     * 现在只保留形如 `包名/类名` 或 `包名/短类名` 的 token，
     * 其余（null、空、[timeout]、各种告警文本）一律丢弃。
     */
    private fun sanitizeServiceList(rawOutput: String): String {
        return rawOutput.lineSequence()
            .joinToString(":") { it.trim() }
            .split(":")
            .asSequence()
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() &&
                    token != "null" &&
                    !token.startsWith("[") &&
                    // 形如 com.pkg/.Service 或 com.pkg/com.pkg.Service
                    token.contains("/") &&
                    token.substringBefore("/").matches(Regex("[A-Za-z0-9_.]+"))
            }
            .distinct()
            .joinToString(":")
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

        // 读出当前已启用的服务列表（可能包含其他无障碍 App，不能覆盖掉）。
        // 必须净化：exec 超时会返回 "[timeout]" 之类的内容，
        // 若 cli 查询异常则回退读取 ContentResolver 中的真实配置，防止误覆盖已有无障碍服务（审计 C-03）。
        val cliRaw = RootShell.exec("settings get secure enabled_accessibility_services", 5_000)
        val rawServices = if (cliRaw.isBlank() || cliRaw.contains("[timeout]")) {
            runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
            }.getOrDefault("")
        } else {
            cliRaw
        }
        val current = sanitizeServiceList(rawServices)

        val combined = when {
            current.isEmpty() -> target
            current.split(":").any { it == target } -> return true
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
