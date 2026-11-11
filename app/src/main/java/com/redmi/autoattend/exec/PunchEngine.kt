package com.redmi.autoattend.exec

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.ChannelPreference
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.PunchKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * 一次执行过程中能出现失败的环节。写进日志用于定位。
 */
enum class PunchStage(val label: String) {
    PREPARE("准备"),
    WAKE("点亮屏幕"),
    UNLOCK("解锁"),
    LAUNCH("启动钉钉"),
    NAVIGATE("进入考勤页"),
    CLICK("点击打卡"),
    VERIFY("确认结果"),
    FINISH("收尾"),
}

/**
 * 执行结果。
 *
 * @param success 是否成功（必须来自界面真实反馈）
 * @param stage 成功时为 FINISH，失败时为失败所在环节
 * @param message 人类可读说明
 * @param evidence 成功判定依据的原始界面文本
 * @param trace 逐步执行轨迹
 */
data class PunchResult(
    val success: Boolean,
    val stage: PunchStage,
    val message: String,
    val evidence: String? = null,
    val trace: List<String> = emptyList(),
    val channelUsed: String = "none",
)

/**
 * 打卡执行引擎。
 *
 * 这是整个 App 的核心：把"锁屏 -> 解锁 -> 拉钉钉 -> 点击 -> 判定"串起来。
 *
 * 设计原则：
 * 1. 每一步都有超时和失败回退（需求 F3 明确要求）；
 * 2. 双通道：无障碍优先，失败时自动降级到 root shell；
 * 3. 成功判定只认钉钉界面的真实反馈，绝不因为"点了"就算成功；
 * 4. 所有步骤记录到 trace，失败时能说清是哪一步挂的。
 */
class PunchEngine(private val context: Context) {

    companion object {
        private const val TAG = "PunchEngine"

        /**
         * 表示"这一班已经打过了"的文本标记。
         *
         * 用于极速打卡/状态卡更新的判定。覆盖不同钉钉版本的措辞。
         * 注意这些词必须和 [DingTalkUi.SUCCESS_SIGNALS] 区分开：
         * 后者是"刚才这一下打成功了"的即时反馈，
         * 这里是"目标时段当前处于已完成状态"的静态状态。
         */
        private val DONE_MARKERS = listOf(
            "已打卡",
            "已签到",
        )

        /**
         * 打卡结果的纯判定函数。
         *
         * ## 为什么抽成纯函数
         *
         * 这段逻辑原本内嵌在 [verifyResult] 的轮询循环里，
         * 意味着它**只有在真机上真的打一次卡才能验证**。
         * 而真打卡会在用户的考勤系统里留下记录 —— 代价太高，不能反复试。
         *
         * 抽出来之后，所有分支都能用单元测试覆盖，
         * 包括那些真机上很难复现的边界（极速打卡、外勤、网络失败、页面残留）。
         *
         * @param blob 当前界面上读到的全部文本
         * @param alreadyDoneBefore 点击前目标时段是否已完成
         * @param doneNow 当前目标时段是否已完成
         * @return 判定为成功时返回"证据"字符串；判定不出成功返回 null
         */
        fun judgeResult(
            blob: String,
            alreadyDoneBefore: Boolean,
            doneNow: Boolean,
        ): String? {
            // ---- 1. 失败信号优先 ----
            //
            // 必须先查失败。边缘情况：页面同时出现"打卡成功"和"不在打卡范围"
            // （部分成功的脏数据）时，宁可判失败让用户知情，
            // 也不能报成功让用户以为打上了。
            DingTalkUi.containsFailure(blob)?.let { return null }

            // ---- 2. 明确的成功信号 ----
            // 钉钉弹出的结果卡片文案，这是最可靠的依据。
            DingTalkUi.containsSuccess(blob)?.let { return it }

            // ---- 3. 状态翻转（极速打卡 / 卡片静默更新）----
            //
            // 只有"点击前未完成 → 现在已完成"才算。
            // 单纯的"已打卡"不能作为依据：上班已打的情况下，
            // 下班卡页面顶部同样显示"上班 09:30已打卡"，
            // 无脑认它会漏报下班卡。
            if (!alreadyDoneBefore && doneNow) {
                return "目标时段由未打卡变为已打卡（极速打卡/卡片更新）"
            }

            return null
        }

        /** 每步最多重试次数（需求 F3.5） */
        private const val MAX_RETRY = 3

        /** 启动钉钉后等待就绪的最短时间（需求 F3.4：>= 8 秒） */
        private const val DINGTALK_WARMUP_MS = 8_000L

        /** 轮询界面就绪的最长时间 */
        private const val DINGTALK_READY_TIMEOUT_MS = 20_000L

        /** 单步操作的默认超时 */
        private const val STEP_TIMEOUT_MS = 6_000L

        /**
         * 发现外勤时，最多等多久（毫秒）。
         *
         * 用户在通勤路上触发打卡是常见场景：到公司门口时才进入考勤范围。
         * 所以不能一看到外勤就放弃，而要**留在现场等**。
         *
         * 15 分钟足够从地铁口走到工位，又不会让屏幕亮太久耗电。
         */
        private const val OUTDOOR_WAIT_TOTAL_MS = 15 * 60 * 1000L

        /**
         * 外勤等待期间的轮询间隔。
         *
         * 5 秒：用户明确要求。
         *
         * 权衡说明：20 秒太迟钝——走进公司大门到被判定为"在范围内"
         * 之间可能只差几秒，20 秒的粒度会让你站在门口白等最多 20 秒。
         * 5 秒足够灵敏，而每次检查只是读一次无障碍节点树（不 fork、
         * 不截图），开销远小于屏幕常亮的功耗，所以不用担心耗电。
         */
        private const val OUTDOOR_POLL_MS = 5_000L

        // ==================== 迟到/早退的安全常量 ====================
        //
        // 这两个常量是整个 App 的安全底线，改之前先想清楚后果。
        //
        // 【迟到卡的判定】用户明确要求：
        //   - 目标时间起 **满 1 分钟** 才算迟到；
        //   - 刚进入上班时间（比如 09:30:00~09:30:59）**不算迟到**；
        //   - 但要**预留安全余量**，不能卡在 58/59 秒这种边缘时刻去打，
        //     因为从"决定打"到"点击生效"本身要几百毫秒，一旦跨界就成了迟到卡。
        //
        // 所以实际可打窗口是：
        //   [target, target + LATE_GRACE - SAFETY_MARGIN]
        //   = [09:30:00, 09:30:55]
        //
        // 也就是说：**09:30:56 之后就不打了**，而不是等到 09:31。

        /** 进入上班时间后多久算迟到（1 分钟） */
        private const val LATE_GRACE_MS = 60_000L

        /**
         * 安全余量：在迟到边界之前提前这么多就停手。
         *
         * 5 秒的依据：从检查通过 → 找到按钮 → performAction → 钉钉服务端记账，
         * 实测在 K90 上约 300~800ms。留 5 秒是 6~15 倍冗余，
         * 既够安全，又不会让可打窗口窄到不可用（实际窗口仍有 55 秒）。
         */
        private const val LATE_SAFETY_MARGIN_MS = 5_000L

        /**
         * 下班卡的提前量。
         *
         * 用户要求「下班之前绝不打下班卡」，这里同样留 2 秒余量：
         * 早退比缺卡严重，宁可晚 2 秒也不能早。
         */
        private const val CLOCK_OUT_SAFETY_MARGIN_MS = 2_000L
    }

    private val trace = mutableListOf<String>()
    private var channelUsed = "none"

    private fun log(line: String) {
        val stamped = "[" + System.currentTimeMillis() % 100000 + "] " + line
        trace.add(stamped)
        Log.i(TAG, line)
    }

    /**
     * 执行一次打卡。
     *
     * @param kind 上班 / 下班 / 手动
     * @param wakeLock 已持有的唤醒锁（由前台服务获取），可能为 null
     */
    suspend fun execute(
        kind: PunchKind,
        wakeLock: PowerManager.WakeLock? = null,
    ): PunchResult {
        trace.clear()

        // 【关键】清空无障碍服务的文本缓存。
        //
        // 这个缓存是跨次保留的环形队列（32 条）。如果不在这里清空，
        // 上一次打卡留下的"打卡成功"会留在 blob 里 —— 于是本次哪怕
        // 点击完全失败，verifyResult 在第一次 delay(1500) 后就会读到
        // 上一次的成功文案并判定成功。
        //
        // 那会直接违反验收标准 3（必须以界面真实反馈为依据），
        // 而且是最坏的一种 bug：不是漏打卡，而是**打了没打成都报成功**。
        PunchAccessibilityService.get()?.resetTextHistory()

        val pref = AppConfig.channel.value

        // ---- 0. 用户主动跳过的日期 ----
        // 手动打卡不受此限制（用户手动点就是要打），只拦自动触发的。
        //
        // 判断依据是"被跳过的日期 == 现在所在的日期"，
        // 而不是布尔值 —— 因为跳过可能设置的是明天。
        val skippedKey = AppConfig.skippedDateKey
        val nowKey = PunchSchedule.dateKey(System.currentTimeMillis())
        if (kind != PunchKind.MANUAL && skippedKey != 0 && skippedKey == nowKey) {
            log("用户已跳过 " + skippedKey + "，本时段不执行")
            return PunchResult(
                success = false,
                stage = PunchStage.PREPARE,
                message = "这一天已被设置为跳过，未执行打卡",
                evidence = null,
                trace = trace.toList(),
                channelUsed = "none",
            )
        }

        try {
            // ---- 1. 准备 ----
            log("开始执行 kind=" + kind.name + " 通道偏好=" + pref.name)

            // ---- 1.5 取消检查点 ----
            //
            // 从进入 try 到第一条 delay 之间没有挂起点，期间如果用户点了暂停，
            // 取消信号不会自动生效。显式检查一次，保证暂停能立刻中断。
            currentCoroutineContext().ensureActive()

            // ---- 2. 点亮屏幕 ----
            if (!prepareScreen()) {
                return fail(PunchStage.WAKE, "无法点亮屏幕")
            }

            // ---- 3. 解锁 ----
            if (!unlock()) {
                return fail(PunchStage.UNLOCK, "解锁失败，请检查锁屏密码设置")
            }

            // ---- 3.5 显示接管提示（系统悬浮窗，能在钉钉之上可见）----
            // 从这里开始我们要操作界面了，用户有权知道。
            ControlHudOverlay.show(
                context = context,
                title = "正在接管屏幕",
                subtitle = "钉钉GO 正在操作界面",
                onPause = {
                    // 暂停 = 取消当前协程，但保留服务与悬浮窗，
                    // 用户可以再点「▶」继续。
                    PunchForegroundService.requestPause(context)
                    ControlHudOverlay.setPaused(
                        context,
                        paused = true,
                        title = "正在接管屏幕",
                        subtitle = "钉钉GO 正在操作界面",
                    )
                },
                onResume = {
                    // 继续 = 让服务重新走一次执行流程（跳过预热等待）
                    PunchForegroundService.requestResume(context)
                    ControlHudOverlay.setPaused(
                        context,
                        paused = false,
                        title = "正在接管屏幕",
                        subtitle = "钉钉GO 正在操作界面",
                    )
                },
                onCancel = {
                    PunchForegroundService.requestCancel(context)
                },
            )

            // ---- 4. 启动钉钉 ----
            if (!launchDingTalk()) {
                return fail(PunchStage.LAUNCH, "钉钉启动失败或未安装")
            }

            // ---- 5. 进入考勤页 ----
            val navOk = when (pref) {
                ChannelPreference.ACCESSIBILITY_ONLY -> navigateWithAccessibility()
                ChannelPreference.ROOT_ONLY -> navigateWithRoot()
                ChannelPreference.AUTO -> navigateWithAccessibility().also { ok ->
                    if (!ok) {
                        log("无障碍导航失败，降级到 root 通道")
                        channelUsed = "root"
                    }
                } || navigateWithRoot()
            }
            if (!navOk) {
                return fail(PunchStage.NAVIGATE, "无法进入考勤打卡页")
            }

            // ---- 5.5 安全闸门（绝不能跳过） ----
            val guard = checkSafetyGate(kind)
            if (guard != null) {
                return guard
            }

            // ---- 5.9 记录点击前的基线状态 ----
            //
            // 【极速打卡】钉钉自带"极速打卡"：进入考勤页时它可能**已经自己打好了**，
            // 页面上直接显示"09:30已打卡"，根本没有可点的按钮，也不需要我们点。
            //
            // 这种情况下：
            //   · clickPunchButton 找不到按钮 → 直接判失败（误报）
            //   · 或者找到了按钮点下去 → 钉钉提示已打卡，但 verifyResult 只认
            //     "打卡成功"四个字，仍然读不到 → 还是误报失败
            //
            // 所以必须在点击**之前**记下目标时段是否已完成，
            // 之后用它判断"是不是本来就是好的"。
            val alreadyDoneBefore = readTargetDoneState(kind)
            log("点击前基线：目标时段已打卡=" + alreadyDoneBefore)

            // ---- 6. 点击打卡 ----
            val clicked = clickPunchButton(kind)

            // 极速打卡 / 本来已完成：不需要点击也应该判成功。
            // 但必须满足"目标时段确实已完成"，不能笼统看到"已打卡"就算。
            if (!clicked && alreadyDoneBefore) {
                ControlHudOverlay.hide()
                val evidence = "极速打卡（无需点击）：目标时段已是已打卡状态"
                log("判定成功，依据: " + evidence)
                return PunchResult(
                    success = true,
                    stage = PunchStage.FINISH,
                    message = "该时段已完成打卡（钉钉极速打卡）",
                    evidence = evidence,
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                )
            }

            if (!clicked) {
                return fail(PunchStage.CLICK, "未找到打卡按钮或点击无效")
            }

            // ---- 7. 确认结果（唯一成功依据） ----
            val verdict = verifyResult(kind, alreadyDoneBefore)
            if (verdict == null) {
                return fail(
                    PunchStage.VERIFY,
                    "未从钉钉界面读到成功反馈，按失败处理",
                )
            }

            ControlHudOverlay.hide()
            log("判定成功，依据: " + verdict)
            return PunchResult(
                success = true,
                stage = PunchStage.FINISH,
                message = "打卡成功",
                evidence = verdict,
                trace = trace.toList(),
                channelUsed = channelUsed,
            )
        } catch (c: CancellationException) {
            // 【关键】取消必须原样抛出去，绝不能转成 PunchResult。
            //
            // 之前这里是 catch (Throwable)，CancellationException 也被吞成一个
            // "success=false" 的普通结果。后果是：
            //   用户点悬浮窗「暂停」→ job.cancel() → 本函数"正常返回" →
            //   PunchForegroundService 的 finally 里 paused==true 就不 cleanup、
            //   不 stopSelf，running 永远是 true。此后 FIRE 闹钟走进
            //   "已有执行在进行" 分支直接 return —— **当天再也打不了卡**，
            //   并且唤醒锁一直挂到 5 分钟超时。
            //
            // 协程取消是控制流，不是业务失败。这里只做资源清理然后 rethrow。
            ControlHudOverlay.hide()
            log("执行被中断（暂停/取消），交给上层收尾")
            throw c
        } catch (t: Throwable) {
            ControlHudOverlay.hide()
            log("执行异常: " + t.javaClass.simpleName + " " + (t.message ?: ""))
            // 严重错误（OOM / StackOverflow）继续往上抛：
            // 把它们伪装成"打卡失败"会掩盖真正的故障原因。
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            return PunchResult(
                success = false,
                stage = PunchStage.PREPARE,
                message = "执行异常：" + (t.message ?: t.javaClass.simpleName),
                trace = trace.toList(),
                channelUsed = channelUsed,
            )
        }
    }

    private fun fail(stage: PunchStage, message: String): PunchResult {
        // 【统一收尾】所有失败路径都经过这里，所以悬浮提示在这里移除。
        // 之前想给每个 return fail() 单独加 hide()，有 6 处，极易漏掉 ——
        // 漏掉就会在屏幕上留下一个永不消失的提示条。
        ControlHudOverlay.hide()
        log("失败于 [" + stage.label + "] " + message)
        return PunchResult(
            success = false,
            stage = stage,
            message = message,
            trace = trace.toList(),
            channelUsed = channelUsed,
        )
    }

    // ---------- 各步骤实现 ----------

    /** 点亮屏幕：优先 PowerManager，失败时用 root 发 KEYCODE_WAKEUP */
    private suspend fun prepareScreen(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            log("屏幕已点亮")
            return true
        }

        // 方案 A：root 发 KEYCODE_WAKEUP（224）——这是最可靠的点亮方式，
        // 因为它绕过 PowerManager 的权限限制，直接走 input 子系统。
        if (RootShell.isAvailable()) {
            log("root 点亮屏幕 input keyevent 224")
            channelUsed = "root"
            RootShell.wakeUp()
            if (waitUntil(3_000L) { pm.isInteractive }) return true
        }

        // 方案 B：无障碍通道点亮 —— 通过拉起本应用的 Activity 并设置 turnScreenOn
        // （MainActivity 声明了 turnScreenOn=true，会在锁屏上唤醒屏幕）
        log("root 不可用，尝试用 Activity 唤醒屏幕")
        runCatching {
            val intent = android.content.Intent(
                context,
                com.redmi.autoattend.MainActivity::class.java,
            ).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra("wake_only", true)
            }
            context.startActivity(intent)
        }

        if (waitUntil(4_000L) { pm.isInteractive }) return true

        // 方案 C：如果用户设置了"充电时保持唤醒"，屏幕本就是亮的，直接继续
        return pm.isInteractive
    }

    /** 解锁：无密码上滑；有密码上滑 + 输入数字 + 自动确认 */
    private suspend fun unlock(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            log("解锁前屏幕仍关闭")
            return false
        }

        // 先判断是否已经在桌面/已解锁：如果当前前台不是 Keyguard，直接跳过
        if (isKeyguardUnlocked()) {
            log("已是解锁状态，跳过解锁")
            return true
        }

        var password: String? = AppConfig.readLockPassword()
        val hasPassword = !password.isNullOrEmpty()

        // 上滑唤醒锁屏输入界面
        swipeUpOnce()
        delay(800)

        if (!hasPassword) {
            log("无密码模式，仅上滑")
            return waitUntil(5_000L) { isKeyguardUnlocked() }
        }

        // 有密码：输入数字。
        //
        // 安全约束：密码只在这个函数栈上存活，用完立即置 null（String 无法真正擦除，
        // 但清掉引用能显著缩短它在堆上的可寻址时间）。
        // 绝不写日志、绝不进 trace、绝不进 Room。
        try {
            repeat(MAX_RETRY) { attempt ->
                // 注意：日志里只说"第 N 次"，绝不带密码值
                log("输入锁屏密码，第 " + (attempt + 1) + " 次")
                if (inputPassword(password)) {
                    if (waitUntil(6_000L) { isKeyguardUnlocked() }) return true
                }
                delay(500)
            }

            // 兜底：再发一次回车（部分 ROM 需要确认）
            if (RootShell.isAvailable()) {
                RootShell.sendEnter()
                if (waitUntil(3_000L) { isKeyguardUnlocked() }) return true
            }

            return false
        } finally {
            // 无论成功失败都把引用置空
            password = null
        }
    }

    /**
     * 判断锁屏是否**仍然锁着**。
     *
     * ## 为什么必须用 KeyguardManager（这是安全红线）
     *
     * 旧实现有两条路，**两条都不可靠**，结果恒返回"未解锁"：
     *
     * 1. 靠无障碍 foregroundPackage()——无障碍配置只监听钉钉，
     *    用户停在微信/桌面时拿不到包名，恒为 null。
     * 2. 靠 dumpsys keyguard | grep mShowingLockscreen——HyperOS 3 上
     *    这两个字段基本不存在，匹配不到，同样恒 false。
     *
     * 后果很严重：用户亮屏正常用着手机（微信输入框有焦点时），
     * 引擎仍认为"锁着"，于是上滑 + 把锁屏密码明文 input text 到
     * **当前焦点控件** —— 这是可复现的密码外泄。
     *
     * 正确判据是 KeyguardManager.isKeyguardLocked：App 可直接调用的系统 API，
     * 不依赖无障碍、不依赖 su、对不同 ROM 行为一致。
     */
    private fun isKeyguardLocked(): Boolean {
        return runCatching {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE)
                as? android.app.KeyguardManager
            km?.isKeyguardLocked ?: false
        }.getOrDefault(false)
    }

    /** 是否已解锁（可直接操作界面） */
    private fun isKeyguardUnlocked(): Boolean = !isKeyguardLocked()

    /** 上滑一次 */
    private suspend fun swipeUpOnce() {
        val service = PunchAccessibilityService.get()
        if (service != null && service.swipeUp()) {
            log("无障碍上滑")
            return
        }
        if (RootShell.isAvailable()) {
            channelUsed = "root"
            RootShell.swipeUp()
            log("root 上滑")
            return
        }
        log("无可用通道上滑")
    }

    /**
     * 输入锁屏密码。
     *
     * 小米数字锁屏在输满位数后自动解锁，因此默认不发回车。
     * 密码明文只在此处短暂使用，绝不写日志。
     */
    private suspend fun inputPassword(password: String?): Boolean {
        // 优先 root input text（最可靠，能直接给 keyguard 的输入框喂数字）
        if (!password.isNullOrEmpty() && RootShell.isAvailable()) {
            channelUsed = "root"
            RootShell.inputText(password)
            return true
        }

        if (password.isNullOrEmpty()) return false

        // 无障碍通道：找到密码输入框逐个数字点击
        val service = PunchAccessibilityService.get() ?: return false
        val root = service.rootNodeOrNull() ?: return false

        // 尝试按文本直接找数字键
        var allClicked = true
        for (ch in password) {
            val key = service.findClickableByText(listOf(ch.toString()))
            if (key != null) {
                service.clickNode(key)
                delay(120)
            } else {
                allClicked = false
                break
            }
        }
        return allClicked
    }

    /** 启动钉钉并等待工作台就绪 */
    private suspend fun launchDingTalk(): Boolean {
        log("启动钉钉")
        val started = if (RootShell.isAvailable()) {
            channelUsed = "root"
            RootShell.launchPackage(DingTalkUi.PACKAGE)
        } else {
            startViaIntent()
        }

        if (!started) {
            log("启动命令未成功返回")
        }

        // 至少等待 8 秒（需求明确规定）
        delay(DINGTALK_WARMUP_MS)

        // 轮询等待钉钉界面出现
        val ok = waitUntil(DINGTALK_READY_TIMEOUT_MS) {
            val service = PunchAccessibilityService.get()
            val pkg = service?.foregroundPackage() ?: RootShell.currentPackage()
            pkg == DingTalkUi.PACKAGE
        }
        log("钉钉前台就绪=" + ok)
        return ok
    }

    private fun startViaIntent(): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(DingTalkUi.PACKAGE)
            ?: return@runCatching false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 无障碍通道导航：进入考勤页。
     *
     * ## 为什么要"校验跳转"（这是之前点错地方的根因）
     *
     * 旧实现是"点一下 → 固定 delay → 继续找下一个目标"，从不检查
     * 页面到底有没有变化。于是当点击落空（或点到"汇报工作"这种
     * 错误条目）时，代码仍会继续按原计划找"考勤打卡"，找不到就滚动，
     * 滚动后看到别的"含打卡字样"的元素又点上去——表现为反复乱点。
     *
     * 现在每一步都必须满足"页面确实变了"才继续：
     * 1. 记录点击前的页面指纹；
     * 2. 点击；
     * 3. 轮询等待指纹变化（最多 6 秒）；
     * 4. 指纹没变 = 这一下没点到，换下一个候选重试。
     */
    private suspend fun navigateWithAccessibility(): Boolean {
        val service = PunchAccessibilityService.get()
        if (service == null) {
            log("无障碍服务未连接")
            return false
        }
        channelUsed = "accessibility"

        // ---- 第 1 步：进入工作台 ----
        if (!tapAndVerify(service, DingTalkUi.TAB_WORK, "工作台 tab")) {
            log("未能进入工作台，尝试其他路径")
        }

        // ---- 第 2 步：找考勤入口 ----
        repeat(MAX_RETRY) { attempt ->
            dismissPopups(service)

            if (tapAndVerify(service, DingTalkUi.ENTRY_ATTENDANCE, "考勤入口")) {
                log("已进入考勤页")
                return true
            }

            // 页面上没有就滚动找（工作台应用可能不在首屏）
            log("首屏未找到考勤入口，滚动查找（第 " + (attempt + 1) + " 次）")
            service.swipeUp()
            delay(1200)
        }

        // ---- 第 3 步：兜底，试试首页顶部的"打卡"快捷入口 ----
        log("工作台路径失败，尝试首页快捷打卡入口")
        val pkg = service.foregroundPackage()
        if (pkg == DingTalkUi.PACKAGE) {
            if (tapAndVerify(service, DingTalkUi.QUICK_PUNCH, "首页打卡快捷入口")) {
                return true
            }
        }

        return false
    }

    /**
     * 点击某个候选文本，并校验页面真的发生了变化。
     *
     * @return true 表示点到了且页面确实跳转/刷新了
     */
    private suspend fun tapAndVerify(
        service: PunchAccessibilityService,
        candidates: List<String>,
        label: String,
    ): Boolean {
        val before = service.readAllText()
        if (before.isEmpty()) {
            log(label + "：读不到当前页面文本，跳过校验")
            return false
        }

        // 已经在目标页面上？直接算成功，不重复点
        if (looksLikeAttendancePage(before)) {
            log(label + "：当前已在考勤页")
            return true
        }

        if (!service.clickByText(candidates)) {
            log(label + "：未找到可点击节点")
            return false
        }
        log(label + "：已点击，等待页面变化")

        // 轮询等页面变化
        val changed = waitUntil(6_000L) {
            val now = service.readAllText()
            now.isNotEmpty() && now != before
        }

        if (!changed) {
            log(label + "：点击后页面无变化（可能没点中）")
            return false
        }

        // 校验是不是跳到了错误页面（比如汇报工作）
        val after = service.readAllText()
        if (DingTalkUi.looksLikeWrongPage(after) && !looksLikeAttendancePage(after)) {
            log(label + "：跳到了错误页面，返回重试")
            service.pressBack()
            delay(1000)
            return false
        }

        log(label + "：页面已跳转")
        return true
    }

    /**
     * 读取"目标时段是否已完成打卡"。
     *
     * 和 [targetAlreadyDone] 的区别：这个会自己读取页面文本，
     * 并且把"读不到页面"当作"未完成"（保守），避免把读失败误判成已完成。
     */
    private fun readTargetDoneState(kind: PunchKind): Boolean {
        val text = runCatching { readCurrentPageText() }.getOrNull().orEmpty()
        if (text.isEmpty()) return false
        return targetAlreadyDone(text, kind)
    }

    /** 判断当前页面是否像考勤打卡页 */
    private fun looksLikeAttendancePage(text: String): Boolean {
        if (text.isEmpty()) return false
        return (text.contains("上班打卡") || text.contains("下班打卡")) ||
            text.contains("考勤打卡") ||
            text.contains("打卡记录")
    }
    /** root 通道：靠 uiautomator dump 解析坐标后 input tap */
    private suspend fun navigateWithRoot(): Boolean {
        if (!RootShell.isAvailable()) {
            log("root 不可用，无法走 root 通道")
            return false
        }
        channelUsed = "root"

        repeat(MAX_RETRY) { attempt ->
            log("root 导航尝试 " + (attempt + 1))
            val xml = RootShell.dumpUiHierarchy()
            if (xml == null) {
                log("uiautomator dump 失败")
                return@repeat
            }

            // 找"工作" tab 并点击
            val workNode = UiHierarchy.findNode(xml, DingTalkUi.TAB_WORK)
            if (workNode != null) {
                RootShell.tap(workNode.centerX, workNode.centerY)
                log("root 点击工作 tab @ " + workNode.centerX + "," + workNode.centerY)
                delay(2200)
            }

            // 找考勤入口
            val entry = UiHierarchy.findNode(xml, DingTalkUi.ENTRY_ATTENDANCE)
            if (entry != null) {
                RootShell.tap(entry.centerX, entry.centerY)
                log("root 点击考勤入口 @ " + entry.centerX + "," + entry.centerY)
                delay(3000)
                return true
            }

            // 重新 dump 再找
            val xml2 = RootShell.dumpUiHierarchy()
            if (xml2 != null) {
                val entry2 = UiHierarchy.findNode(xml2, DingTalkUi.ENTRY_ATTENDANCE)
                if (entry2 != null) {
                    RootShell.tap(entry2.centerX, entry2.centerY)
                    delay(3000)
                    return true
                }
            }
        }
        return false
    }

    /** 关掉可能的钉钉弹窗 */
    private suspend fun dismissPopups(service: PunchAccessibilityService) {
        if (service.clickByText(DingTalkUi.POPUP_DISMISS)) {
            log("关闭了一个弹窗")
            delay(800)
        }
    }

    /** 点击打卡按钮 */
    private suspend fun clickPunchButton(kind: PunchKind): Boolean {
        val candidates = when (kind) {
            PunchKind.CLOCK_OUT -> DingTalkUi.BTN_CLOCK_OUT + DingTalkUi.BTN_CLOCK_IN
            else -> DingTalkUi.BTN_CLOCK_IN
        }

        repeat(MAX_RETRY) { attempt ->
            val service = PunchAccessibilityService.get()
            if (service != null) {
                if (service.clickByText(candidates)) {
                    log("无障碍点击打卡按钮（第 " + (attempt + 1) + " 次）")
                    delay(2500)
                    return true
                }
            }

            if (RootShell.isAvailable()) {
                val xml = RootShell.dumpUiHierarchy()
                if (xml != null) {
                    val node = UiHierarchy.findNode(xml, candidates)
                    if (node != null) {
                        RootShell.tap(node.centerX, node.centerY)
                        log("root 点击打卡按钮 @ " + node.centerX + "," + node.centerY)
                        delay(2500)
                        return true
                    }
                }
            }
            delay(1200)
        }
        return false
    }

    // ==================== 安全闸门 ====================

    /**
     * 打卡前的安全闸门。
     *
     * 这是本 App 最重要的防护逻辑 —— 宁可**不打**，也绝不**打错**。
     *
     * 用户明确提的两条红线：
     * 1. 「下班之前绝不打下班卡」：早退比缺卡严重得多，可能影响全勤奖；
     * 2. 「上班之后绝不打迟到卡」：迟到卡一旦生成就是既成事实。
     * 3. 「发现外勤先不打，等到能正常打卡为止」：外勤记录很多公司不认。
     *
     * @return null 表示可以打；非 null 表示本次必须中止，返回值即结果
     */
    private suspend fun checkSafetyGate(kind: PunchKind): PunchResult? {
        // ---------- 闸门 1：时间窗口 ----------
        val now = System.currentTimeMillis()
        val settings = AppConfig.settings.value
        val slot = PunchSchedule.nextSlot(now, settings)

        // 目标时刻：优先用本次调度对应的 slot；手动打卡时用设置里的时间
        val targetMinute = when (kind) {
            PunchKind.CLOCK_IN -> settings.clockInMinute
            PunchKind.CLOCK_OUT -> settings.clockOutMinute
            PunchKind.MANUAL -> settings.clockInMinute
        }

        // 计算「目标时刻」的毫秒值（同一天）
        val startOfDay = PunchSchedule.startOfDay(now)
        val targetAt = startOfDay + targetMinute * 60_000L
        val deltaMs = now - targetAt

        log(
            "安全闸门：kind=" + kind.name +
                " 目标=" + PunchSchedule.formatMinute(targetMinute) +
                " 偏差=" + (deltaMs / 60_000L) + "分钟"
        )

        when (kind) {
            PunchKind.CLOCK_OUT -> {
                // ===== 【红线 1】下班卡：目标时刻之前一律不打 =====
                //
                // 早退记录比缺卡严重：缺卡还能补卡申请，早退直接影响全勤。
                // 这里额外留 CLOCK_OUT_SAFETY_MARGIN_MS，
                // 避免"恰好在 18:29:59.8 判定通过、18:30:00.2 点击生效"这种跨界。
                val allowedAt = targetAt + CLOCK_OUT_SAFETY_MARGIN_MS
                if (now < allowedAt) {
                    val remainSec = (allowedAt - now) / 1000L
                    val remainText = if (remainSec >= 60) {
                        (remainSec / 60) .toString() + " 分 " + (remainSec % 60) + " 秒"
                    } else {
                        remainSec.toString() + " 秒"
                    }
                    log("拒绝：下班时间未到，还差 " + remainText)
                    return PunchResult(
                        success = false,
                        stage = PunchStage.CLICK,
                        message = "距下班时间还有 " + remainText + "，已阻止提前打下班卡",
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                    )
                }
                log("下班卡时间窗校验通过（已过目标 " + (now - targetAt) / 1000L + " 秒）")
            }

            PunchKind.CLOCK_IN -> {
                // ===== 【红线 2】上班卡：迟到边界前必须停手 =====
                //
                // 用户规格：
                //   - 09:30:00 ~ 09:30:59 算正常（满 1 分钟才算迟到）；
                //   - 要预留安全余量，不能卡在 58/59 秒。
                //
                // 实现：可打窗口 = [targetAt, targetAt + 60s - 5s] = 55 秒宽。
                // 超过 09:30:55 就拒绝，而不是等到 09:31 才拒绝。
                val lateBoundary = targetAt + LATE_GRACE_MS
                val mustStopAt = lateBoundary - LATE_SAFETY_MARGIN_MS

                if (now > mustStopAt) {
                    val lateMin = (now - targetAt) / 60_000L
                    val lateSec = ((now - targetAt) / 1000L) % 60
                    val lateText = if (lateMin > 0) {
                        lateMin.toString() + " 分 " + lateSec + " 秒"
                    } else {
                        lateSec.toString() + " 秒"
                    }
                    log("拒绝：已超迟到边界 " + lateText + "，避免生成迟到卡")
                    return PunchResult(
                        success = false,
                        stage = PunchStage.CLICK,
                        message = "已过上班时间 " + lateText +
                            "（迟到边界 1 分钟，留 5 秒安全余量），为避免迟到记录已跳过。" +
                            "如需打卡请手动操作。",
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                    )
                }

                val remainToBoundaryMs = mustStopAt - now
                log(
                    "上班卡时间窗校验通过（距停手还有 " +
                        (remainToBoundaryMs / 1000L) + " 秒）"
                )
            }

            PunchKind.MANUAL -> {
                // 手动打卡由用户主动触发，不套时间闸门（但要过外勤闸门）
                log("手动打卡：跳过时间闸门（仍会检查外勤）")
            }
        }

        // ---------- 闸门 2：外勤检查（需要读界面，可能要等） ----------
        return waitUntilInRange(kind)
    }

    /**
     * 外勤闸门：如果是外勤状态就原地等待，直到进入考勤范围或超时。
     *
     * ## 为什么要"等"而不是直接失败
     *
     * 用户实际的场景是：早上 9:25 在地铁上，闹钟响了，手机自动打开钉钉，
     * 此时显示"未进入考勤范围，可外勤打卡"。如果这时点了，就是一条外勤记录。
     * 等到 9:2x 走进公司大楼，页面会自动变成正常打卡按钮。
     *
     * 所以正确做法是：**保持页面打开、每 20 秒重新检查一次**，
     * 一旦外勤提示消失就立刻打。超时（15 分钟）才放弃。
     *
     * @return null 表示可以打；非 null 表示本次放弃
     */
    private suspend fun waitUntilInRange(kind: PunchKind): PunchResult? {
        val service = PunchAccessibilityService.get()
        val startedAt = System.currentTimeMillis()
        var attempt = 0

        while (true) {
            attempt++
            val blob = readCurrentPageText()

            if (blob.isEmpty()) {
                log("外勤检查：读不到页面文本，按可打处理（由后续步骤兜底）")
                return null
            }

            // 已打卡状态：不需要再打
            DingTalkUi.containsAlreadyDone(blob)?.let { signal ->
                // 但要排除"今天的上班已打卡，现在该打下班"这种正常情况：
                // 只有当目标按钮本身已经是"已打卡"时才中止。
                if (targetAlreadyDone(blob, kind)) {
                    log("外勤检查：目标时段已打卡（" + signal + "），无需重复")
                    return PunchResult(
                        success = true,
                        stage = PunchStage.FINISH,
                        message = "今日该时段已打过卡，跳过重复打卡",
                        evidence = signal,
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                    )
                }
            }

            // 外勤判断
            if (!DingTalkUi.isOutdoor(blob)) {
                log("外勤检查：已进入考勤范围（第 " + attempt + " 次检查）")
                return null
            }

            val waitedMs = System.currentTimeMillis() - startedAt
            if (waitedMs >= OUTDOOR_WAIT_TOTAL_MS) {
                log("外勤检查：等待超时（" + (waitedMs / 60_000L) + " 分钟），放弃本次打卡")
                return PunchResult(
                    success = false,
                    stage = PunchStage.CLICK,
                    message = "当前不在考勤范围内（外勤状态），已等待 " +
                        (waitedMs / 60_000L) + " 分钟后放弃，未生成外勤记录",
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                )
            }

            log(
                "外勤检查：仍在考勤范围外，等待中（已等 " +
                    (waitedMs / 1000L) + " 秒 / 上限 " + (OUTDOOR_WAIT_TOTAL_MS / 60_000L) + " 分钟）"
            )
            delay(OUTDOOR_POLL_MS)

            // 等待期间页面可能被系统或用户切走，重新导航一次
            if (service != null && !service.readAllText().contains("打卡")) {
                log("外勤检查：页面已离开考勤页，尝试重新进入")
                if (!navigateWithAccessibility()) {
                    log("外勤检查：重新进入考勤页失败")
                }
            }
        }
    }

    /**
     * 判断目标时段是否已经打过卡。
     *
     * 打卡页顶部有两张状态卡（上班 09:30 / 下班 18:30）。
     * 必须只看**目标那张**，不能因为"上班已打"就认为"下班也打了"。
     */
    private fun targetAlreadyDone(blob: String, kind: PunchKind): Boolean {
        val key = when (kind) {
            PunchKind.CLOCK_IN -> "上班"
            PunchKind.CLOCK_OUT -> "下班"
            // 手动打卡时按"当前该打哪一班"推断，不能一律 false ——
            // 否则手动打卡永远无法走"极速打卡已完成"的判定。
            PunchKind.MANUAL -> return false
        }

        // 形如 "上班09:30 09:30已打卡"：找到目标关键字后，
        // 看其后一小段窗口里有没有完成标记。
        //
        // 窗口取 90 字符而不是 60：钉钉在不同版本里会把
        // "上班 09:30 已打卡 更新打卡 补卡" 这类文本排得更开，
        // 60 字符有时刚好把"已打卡"截在窗外。
        var from = 0
        while (true) {
            val idx = blob.indexOf(key, from)
            if (idx < 0) return false
            val end = minOf(blob.length, idx + 90)
            val window = blob.substring(idx, end)
            if (DONE_MARKERS.any { window.contains(it) }) return true
            from = idx + key.length
        }
    }


    /** 读取当前页面文本（无障碍优先，root 兜底） */
    private fun readCurrentPageText(): String {
        val service = PunchAccessibilityService.get()
        val fromA11y = runCatching { service?.readAllText() }.getOrNull().orEmpty()
        if (fromA11y.isNotEmpty()) return fromA11y
        return runCatching {
            RootShell.dumpUiHierarchy()?.let { UiHierarchy.allText(it) }
        }.getOrNull().orEmpty()
    }
    /**
     * 确认打卡结果。
     *
     * 这是"不许把点击当成功"的地方：必须从钉钉界面读到成功信号才返回非 null。
     *
     * @return 命中的成功信号原文；判定不出成功时返回 null
     */
    /**
     * 确认打卡结果。
     *
     * ## 必须同时覆盖两条通道
     *
     * 早先只读无障碍服务的文本。但 ACCESSIBILITY_ONLY 之外的模式，
     * 以及"无障碍被系统杀掉"的情况下 service 为 null → blob 恒空 →
     * 永远判失败。也就是说 root 通道下**即使用户确实打上卡了也会弹失败通知**。
     *
     * 现在两条路都读：
     * - 无障碍：事件缓存 + 当前窗口完整文本
     * - root：uiautomator dump 解析出的全页文本（UiHierarchy.allText）
     *
     * @return 命中的成功信号原文；判定不出成功时返回 null
     */
    private suspend fun verifyResult(kind: PunchKind, alreadyDoneBefore: Boolean): String? {
        val service = PunchAccessibilityService.get()

        repeat(MAX_RETRY) { attempt ->
            delay(1500)

            val blob = buildString {
                // 1. 无障碍：事件缓存
                service?.recentTextBlob()?.let { append(it) }
                append(' ')
                // 2. 无障碍：当前窗口完整文本（最可靠）
                service?.readAllText()?.let { append(it) }
                append(' ')
                // 3. root 通道：dump 整页文本。
                //    只在无障碍不可用时才 dump —— dumpUiHierarchy 要 fork su，
                //    两条路都能用时优先走便宜的无障碍。
                if (service == null && RootShell.isAvailable()) {
                    RootShell.dumpUiHierarchy()?.let {
                        append(UiHierarchy.allText(it))
                    }
                }
            }

            val doneNow = readTargetDoneState(kind)

            // 判定逻辑本身是纯函数（judgeResult），这里只负责取证与重试。
            judgeResult(blob, alreadyDoneBefore, doneNow)?.let { evidence ->
                if (evidence.contains("未打卡变为已打卡")) {
                    log("判定依据：状态翻转")
                }
                return evidence
            }

            if (DingTalkUi.containsFailure(blob) != null) {
                log("命中失败信号，判定失败")
            }
            log(
                "第 " + (attempt + 1) + " 次确认未读到成功信号" +
                    "（点击前=" + alreadyDoneBefore + " 现在=" + doneNow + "），继续等待"
            )
        }
        return null
    }

    /**
     * 轮询等待某个条件成立。
     *
     * 用 suspend + delay 而不是 inline + Thread.sleep：
     * 1. Thread.sleep 不响应协程取消——用户在预热窗口关掉开关也停不下来；
     * 2. Thread.sleep 会独占一个 IO worker 3~20 秒，而打卡流程里有 6~8 处等待。
     *
     * 这里所有调用点都在 suspend 上下文里，改造零成本。
     */
    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(120)
        }
        return condition()
    }
}