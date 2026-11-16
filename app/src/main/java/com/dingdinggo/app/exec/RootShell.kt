package com.dingdinggo.app.exec

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * root shell 执行通道。
 *
 * 通过 su 执行 Android shell 命令（input / am / dumpsys 等）。
 *
 * 设计约束：
 * - 每次执行都新建 su 进程，避免长连接被系统回收后静默失效；
 * - 所有命令都有超时，避免 su 弹授权框时无限阻塞；
 * - 绝不把用户锁屏密码写入日志。
 */
object RootShell {

    private const val TAG = "RootShell"

    /**
     * 从 stdin 读取密码并输入。
     *
     * ## 协议必须严格配对（真机 bug 修复）
     *
     * `read -r` 把 stdin 的**整行原文**赋给变量，**不会**解析 `K=V` 形式。
     * 旧实现把 `PW=123456` 写进 stdin、又用 `read -r PW` 读，
     * 结果变量值是字符串 `"PW=123456"`，实际执行的是：
     *
     *     input text "PW=123456"      ← 而不是 "123456"
     *
     * 已用设备 shell 实测确认：
     *     echo 'PW=123456' | { read -r PW; echo "[$PW]"; }  →  [PW=123456]
     *     echo '123456'    | { read -r PW; echo "[$PW]"; }  →  [123456]
     *
     * 之前之所以"看起来能用"，是因为 `input text` 遇到 `=` 会跳过、
     * 而 `PW` 两个字母键在数字键盘上大概率无对应键位而失效 —— 纯属侥幸。
     * 一旦 ROM 对未知按键的处置变成"清空输入框"，解锁就会 100% 失败。
     *
     * 现在两端统一为"**只传值**"：stdin 写纯密码，脚本用 read -r 读纯密码。
     * 这样密码依然只存在于 stdin 与 shell 变量里，绝不进入 su 的命令行
     * （/proc/<pid>/cmdline 对其他进程可读，那才是安全红线）。
     */
    private const val SHELL_READ_PW_AND_INPUT =
        "read -r PW; input text \"\$PW\""

    /** root 可用性缓存，避免每次都 fork su 探测 */
    @Volatile
    private var cachedAvailability: Boolean? = null

    /**
     * 探测 root 是否可用。
     *
     * @param force 忽略缓存强制探测
     */
    fun isAvailable(force: Boolean = false): Boolean {
        if (!force) {
            cachedAvailability?.let { return it }
        }
        val ok = runCatching {
            exec("id", timeoutMs = 6_000).contains("uid=0")
        }.getOrDefault(false)
        cachedAvailability = ok
        Log.i(TAG, "root available = " + ok)
        return ok
    }

    fun invalidateCache() {
        cachedAvailability = null
    }

    /**
     * 执行一条 shell 命令并返回 stdout+stderr。
     *
     * @param command 要执行的命令（不含 su 前缀）
     * @param timeoutMs 超时毫秒
     * @return 命令输出；超时或失败返回已读到的部分输出
     */
    fun exec(command: String, timeoutMs: Long = 15_000): String {
        var process: Process? = null
        // reader 必须声明在 try 外面，否则 finally 里访问不到（作用域问题）
        var reader: BufferedReader? = null
        val sb = StringBuilder()
        return try {
            val started = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started

            val rdr = BufferedReader(InputStreamReader(started.inputStream))
            reader = rdr
            val deadline = System.currentTimeMillis() + timeoutMs

            // 用轮询读取，保证超时能生效（su 可能长时间无输出）
            val buffer = CharArray(1024)
            while (System.currentTimeMillis() < deadline) {
                if (rdr.ready()) {
                    val n = rdr.read(buffer)
                    if (n > 0) sb.append(buffer, 0, n) else break
                } else {
                    if (!started.isAlive) {
                        // 进程已退出，把剩余输出读完
                        while (rdr.ready()) {
                            val n = rdr.read(buffer)
                            if (n > 0) sb.append(buffer, 0, n) else break
                        }
                        break
                    }
                    Thread.sleep(20)
                }
            }

            if (started.isAlive) {
                Log.w(TAG, "command timeout after " + timeoutMs + "ms")
                sb.append("\n[timeout]")
            }
            sb.toString()
        } catch (c: InterruptedException) {
            // 协程取消时 Thread.sleep 会被中断。恢复中断位后原样抛出，
            // 不要把它伪装成"命令执行失败"。
            Thread.currentThread().interrupt()
            Log.w(TAG, "exec interrupted")
            throw c
        } catch (t: Throwable) {
            // 只吞运行期异常（IOException / SecurityException / 超时）。
            // VirtualMachineError 之类继续上抛，否则真正的故障会被当成"su 没输出"。
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is VirtualMachineError) throw t
            Log.w(TAG, "exec failed: " + t.javaClass.simpleName)
            sb.toString()
        } finally {
            // 先温和终止，再强制杀。
            // 只 destroy()（SIGTERM）时，su/sh 子进程不响应信号会留下来；
            // 而 reader 不关闭会泄漏一个 FD —— 单次打卡要跑 10+ 次 exec。
            runCatching { reader?.close() }
            runCatching {
                process?.let {
                    if (it.isAlive) it.destroy()
                    it.waitFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (it.isAlive) it.destroyForcibly()
                }
            }
        }
    }

    /** 执行命令但不关心输出 */
    fun execQuiet(command: String, timeoutMs: Long = 15_000) {
        runCatching { exec(command, timeoutMs) }
    }

    // ---------- 常用封装 ----------

    /** 点亮屏幕（KEYCODE_WAKEUP = 224） */
    fun wakeUp() {
        execQuiet("input keyevent 224", 8_000)
    }

    /** 上滑解锁，无密码时使用（避开屏幕中下方屏下指纹区域 ~640,2100） */
    fun swipeUp() {
        execQuiet("input swipe 950 1850 950 750 320", 10_000)
    }

    /**
     * 输入数字密码。
     *
     * 小米数字锁屏在输满位数后自动解锁，不需要额外发回车；
     * 为兼容需要手动确认的 ROM，调用方可用 [sendEnter] 补充。
     *
     * ## 安全红线：密码绝不能出现在命令行里
     *
     * 朴素写法是 su -c "input text 123456"，但这个字符串会成为
     * /proc/<pid>/cmdline 的一部分，Android 上该文件对其他进程可读 ——
     * 任何本机 App 都能抓到锁屏密码。
     *
     * 正确做法：把密码通过 **stdin** 传给 shell，由 shell 变量展开，
     * 这样 su 的命令行里只有变量名：
     *
     *     su -c 'read -r PW; input text "$PW"'
     *     stdin: 123456          ← 只写值，不要写 "PW=123456"
     *
     * stdin 在写入后立即关闭，不留残留。
     * 只有 stdin 方式不可用（少数 su 实现）时才退化为逐位 keyevent。
     */
    fun inputText(text: String) {
        if (text.isEmpty()) return

        val viaStdin = runCatching {
            val process = ProcessBuilder("su", "-c", SHELL_READ_PW_AND_INPUT)
                .redirectErrorStream(true)
                .start()
            process.outputStream.use { os ->
                // 只写密码值本身 —— 与上面的 `read -r PW` 严格配对。
                // 绝不能写成 "PW=" + text：read -r 不解析 K=V，会把前缀
                // 一起当成密码内容传给 input text。
                os.write((text + "\n").toByteArray())
                os.flush()
            }
            val deadline = System.currentTimeMillis() + 10_000L
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val finished = !process.isAlive && process.exitValue() == 0
            runCatching { process.destroy() }
            finished
        }.getOrDefault(false)

        if (viaStdin) {
            Log.i(TAG, "密码已通过 stdin 输入（未进入命令行）")
            return
        }

        // 降级：逐位发送数字键（KEYCODE_0..9 = 7..16）
        Log.w(TAG, "stdin 方式不可用，降级为逐位 keyevent")
        for (ch in text) {
            val digit = ch - '0'
            if (digit !in 0..9) {
                Log.w(TAG, "密码含非数字字符，无法用 keyevent 输入")
                return
            }
            execQuiet("input keyevent " + (7 + digit), 5_000)
        }
    }

    /** 发送回车键 */
    fun sendEnter() {
        execQuiet("input keyevent 66", 8_000)
    }

    /** 灭屏 */
    fun screenOff() {
        invalidatePackageCache()
        execQuiet("input keyevent 223", 8_000)
    }

    /**
     * 启动指定包的主界面。
     *
     * ## 为什么不用 `monkey -p <pkg> ... 1`
     *
     * 真机在 Redmi K90 至尊版（HyperOS 3 / Android 16）实测发现，
     * `monkey` 每次注入启动事件时都会连带执行 `MonkeyRotationEvent#injectEvent`，
     * 先锁定再解锁方向，**直接把用户的「方向锁定」（`accelerometer_rotation`）从 0 改成 1**。
     * 改用 `cmd package resolve-activity` 解析 LAUNCHER 组件后直接 `am start -n`，
     * 全程不经过 monkey 测试通道，绝不触碰方向锁定或任何系统设置。
     */
    fun launchPackage(packageName: String): Boolean {
        invalidatePackageCache()
        val resolved = resolveLauncherComponent(packageName)
        val cmd = if (resolved != null) {
            "am start -n " + resolved +
                " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + packageName
        }
        val out = exec(cmd, 20_000)
        return !out.contains("Error") && !out.contains("Exception") && !out.contains("[timeout]")
    }

    /** 用 cmd package resolve-activity 解析包的主桌面入口组件名 */
    private fun resolveLauncherComponent(packageName: String): String? {
        val out = exec(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + packageName,
            8_000,
        )
        if (out.isBlank() || out.contains("[timeout]") || out.contains("No activity found")) {
            return null
        }
        return out.lineSequence()
            .map { it.trim() }
            .lastOrNull { line ->
                line.contains("/") &&
                    !line.startsWith("[") &&
                    line.substringBefore("/") == packageName
            }
    }

    /** 用 am start 启动 Activity（更精确但需要知道 Activity 名） */
    fun startActivity(packageName: String, activity: String? = null): Boolean {
        invalidatePackageCache()
        if (activity == null) {
            return launchPackage(packageName)
        }
        val out = exec("am start -n " + packageName + "/" + activity, 20_000)
        return !out.contains("Error") && !out.contains("Exception") && !out.contains("[timeout]")
    }

    /** 点击一个坐标 */
    fun tap(x: Int, y: Int) {
        invalidatePackageCache()
        execQuiet("input tap " + x + " " + y, 8_000)
    }

    /** 当前前台 Activity（dumpsys） */
    fun currentFocus(): String {
        val out = exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", 10_000)
        return out
    }

    /**
     * 当前前台包名的短期缓存。
     *
     * ## 为什么必须缓存
     *
     * PunchEngine 里 waitUntil 每 120ms 轮询一次，条件里会调 currentPackage()
     * 和 isKeyguardLocked()。无障碍拿不到包名时（只监听钉钉），
     * 每一步轮询都会 fork 一次 su + dumpsys window：
     * 20 秒的等待 = 约 160 次 su fork。后果是设备发热、轮询周期被 su 拖到
     * 500ms+、判定容易误判。
     *
     * 包名在秒级内不会变，缓存 800ms 足够，且能把 su 调用量降一个数量级。
     */
    @Volatile
    private var cachedPackage: String? = null

    @Volatile
    private var cachedPackageAt: Long = 0L

    private const val PACKAGE_CACHE_MS = 800L

    /** 清掉包名缓存（例如刚执行过会改变前台的动作后） */
    fun invalidatePackageCache() {
        cachedPackage = null
        cachedPackageAt = 0L
    }

    /** 当前前台包名（带 800ms 短期缓存，避免高频轮询反复 fork su） */
    fun currentPackage(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedPackageAt < PACKAGE_CACHE_MS && cachedPackage != null) {
            return cachedPackage
        }
        val focus = currentFocus()
        // 形如：mCurrentFocus=Window{xxx u0 com.alibaba.android.rimet/com.alibaba.android.rimet.biz.LauncherActivity}
        val pkg = Regex("u0 ([a-zA-Z0-9_.]+)/").find(focus)?.groupValues?.get(1)
        cachedPackage = pkg
        cachedPackageAt = now
        return pkg
    }

    /** 屏幕是否点亮 */
    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
    }

    /**
     * 把本应用加入 Doze 白名单。
     *
     * 【审计 C-10 修复】旧实现的兜底条件是 `!out.contains("Exception")` ——
     * 命令超时、su 无输出、甚至什么都没返回时，out 为空字符串，
     * 这个表达式**恒为 true**，于是"加入失败"被报成成功。
     * 而调用方（设置页按钮、健康度自愈）会据此显示"已加入白名单"，
     * 用户以为修好了，实际精确闹钟依旧没有豁免 —— 典型静默失败。
     *
     * 现在改为**回读校验**：写完再查一次白名单里是否真的有这个包名。
     */
    fun addToDeviceIdleWhitelist(packageName: String): Boolean {
        execQuiet("dumpsys deviceidle whitelist +" + packageName, 10_000)
        // 回读：以系统实际状态为准，而不是以"命令没报错"为准
        val check = exec("dumpsys deviceidle whitelist", 10_000)
        if (check.isBlank() || check.contains("[timeout]")) return false
        return check.lineSequence().any {
            val t = it.trim()
            t == packageName || t.endsWith(":" + packageName) || t.endsWith("," + packageName)
        }
    }

    /**
     * 导出当前界面的 UI 层级（uiautomator dump）。
     *
     * 用于 root 通道下定位控件坐标——这是"点击链"降级路径的关键。
     * 输出写到 /sdcard 再读取，避免 stdout 被截断。
     */
    fun dumpUiHierarchy(timeoutMs: Long = 20_000L): String? {
        val path = "/sdcard/autoattend_ui.xml"

        // 必须先删旧文件，再 dump，再确认文件是**本次**产生的。
        //
        // 考勤页是 WebView + 持续动画，`uiautomator dump` 在本页可能会超时失败，
        // 若直接 cat 会误读到上一页残留的 XML —— 被当成新页面的坐标去
        // input tap，点在一个完全错误的位置上。这比"读不到"危险得多：
        // 读不到会走降级分支，读到旧数据会让整条点击链静默点错。
        //
        // 现在：先 remove，dump 后校验文件确实存在且非空（即本次真的写出来了）。
        execQuiet("rm -f " + path, 5_000)
        execQuiet("uiautomator dump " + path, timeoutMs)

        // 校验：文件存在（dump 成功才会创建）
        val exists = exec("test -f " + path + " && echo PRESENT", 5_000).contains("PRESENT")
        if (!exists) {
            Log.w(TAG, "uiautomator dump 未产生文件（页面未 idle），拒绝返回旧数据")
            return null
        }

        val xml = exec("cat " + path, 20_000)
        if (xml.isBlank() || xml.contains("[timeout]") || !xml.contains("<hierarchy")) {
            Log.w(TAG, "UI XML 内容无效，拒绝使用")
            return null
        }
        // 用完即删，避免下次误读
        execQuiet("rm -f " + path, 5_000)
        return xml
    }
}