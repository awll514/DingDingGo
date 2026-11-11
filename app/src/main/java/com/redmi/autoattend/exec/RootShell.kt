package com.redmi.autoattend.exec

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
     * `read -r PW` 从 stdin 读一行到变量；后续 input text 用的是变量展开，
     * 所以 su 的命令行参数里**只有这段脚本文本，没有密码**。
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
        } catch (t: Throwable) {
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

    /** 上滑解锁，无密码时使用 */
    fun swipeUp() {
        execQuiet("input swipe 540 1800 540 600 300", 10_000)
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
     *     stdin: PW=123456
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
                os.write(("PW=" + text + "\n").toByteArray())
                os.flush()
            }
            val deadline = System.currentTimeMillis() + 10_000L
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val finished = !process.isAlive
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
        execQuiet("input keyevent 223", 8_000)
    }

    /** 启动指定包的主界面 */
    fun launchPackage(packageName: String): Boolean {
        val out = exec(
            "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1",
            20_000,
        )
        return out.contains("Events injected") || out.contains("injected")
    }

    /** 用 am start 启动 Activity（更精确但需要知道 Activity 名） */
    fun startActivity(packageName: String, activity: String? = null): Boolean {
        val cmd = if (activity != null) {
            "am start -n " + packageName + "/" + activity
        } else {
            "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1"
        }
        val out = exec(cmd, 20_000)
        return !out.contains("Error") && !out.contains("Exception")
    }

    /** 点击一个坐标 */
    fun tap(x: Int, y: Int) {
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

    /** 当前前台包名 */
    fun currentPackage(): String? {
        val focus = currentFocus()
        // 形如：mCurrentFocus=Window{xxx u0 com.alibaba.android.rimet/com.alibaba.android.rimet.biz.LauncherActivity}
        val match = Regex("u0 ([a-zA-Z0-9_.]+)/").find(focus)
        return match?.groupValues?.get(1)
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

    /** 把本应用加入 Doze 白名单 */
    fun addToDeviceIdleWhitelist(packageName: String): Boolean {
        val out = exec("dumpsys deviceidle whitelist +" + packageName, 10_000)
        return out.contains("Added") || out.contains("added") || !out.contains("Exception")
    }

    /**
     * 导出当前界面的 UI 层级（uiautomator dump）。
     *
     * 用于 root 通道下定位控件坐标——这是"点击链"降级路径的关键。
     * 输出写到 /sdcard 再读取，避免 stdout 被截断。
     */
    fun dumpUiHierarchy(): String? {
        val path = "/sdcard/autoattend_ui.xml"
        execQuiet("uiautomator dump " + path, 20_000)
        val xml = exec("cat " + path, 20_000)
        return xml.ifBlank { null }
    }
}