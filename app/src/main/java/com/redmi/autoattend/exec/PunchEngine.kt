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
    /**
     * 本次是**安全闸门主动放弃**，不是执行失败。
     *
     * ## 为什么要区分
     *
     * 迟到边界、下班未到、外勤超时这几种情况，App 是**故意不打**的：
     * 「宁可缺卡也不打迟到卡」是用户明确要求的红线。
     *
     * 旧实现把它们和其它失败一视同仁：
     *   · 写一条 FAILURE 日志；
     *   · 弹一条「⚠️ 打卡失败」高优先级通知；
     *   · 重排时不标记本班（因为不是 success）→ 补执行窗口内可能再撞一次同样的拒绝。
     *
     * 现在单独标记出来：日志记成"已跳过"，通知语气改为解释而非告警，
     * 并且不再为这一班保留补执行机会（本来就不该打）。
     */
    val abandonedByGate: Boolean = false,

    /**
     * 本次是「来得太早」而不是「这一班已经交代完毕」。
     *
     * ## 为什么必须和 abandonedByGate 分开（真机复现的 P0 漏卡）
     *
     * 服务在结果收尾时用 `markFired = result.success || result.abandonedByGate`
     * 决定是否把本班标记为「已处理」，标记后 scheduleNext 会跳到**下一班**。
     *
     * 原来「距下班时间还有 341 分 19 秒，已阻止提前打下班卡」也带
     * abandonedByGate=true，于是：
     *   · 今天 12:48 触发了一次下班卡（提前，正确拒绝）
     *   · 结果被当成「这一班处理完了」→ 重排时把**今天 18:30** 的
     *     PREWARM / FIRE / RETRY 三段闹钟全部取消，排到了明天 09:30
     *   · 当天真正的下班卡**静默漏打**
     *
     * 真机证据（2026-11-12 12:48）：拒绝后 dumpsys alarm 里 18:30 三段消失，
     * 只剩 RTC_WAKEUP 明天 09:30。
     *
     * 语义修正：太早被拒 = 什么都没改变，该班的闹钟必须原样保留。
     */
    val rejectedTooEarly: Boolean = false,
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
         * 目标时段是否已完成打卡（纯函数，可直接单测）。
         *
         * ## 判定口径
         *
         * 打卡页顶部有两张状态卡（上班 / 下班），必须只看**目标那张**，
         * 不能因为「上班已打卡」就认为「下班也打了」。
         *
         * 真实文本形态（uiautomator dump 里相邻节点 text 用空格拼接后）：
         *
         *     "上班 09:00 已打卡 更新打卡"           ← 已完成
         *     "上班打卡 下班打卡 09:00"              ← 未完成（只有按钮）
         *     "上班 09:00 未打卡 下班 18:30 已打卡"   ← 只有下班完成
         *
         * ## 为什么窗口是 90 字符而不是 60
         *
         * 钉钉不同版本会把「上班 09:30 已打卡 更新打卡 补卡」这组文本
         * 排得更开，60 字符有时刚好把「已打卡」截在窗外。
         *
         * ## 为什么还要截断到下一个班次关键字（关键修正）
         *
         * 只取 90 字符窗口会串味：若「上班」后面紧跟「下班 18:30 已打卡」，
         * 上班卡会被误判成已完成 → 该打的时候不打（漏卡）。
         * 所以窗口终点取 min(关键字后 90 字符, 下一个对侧关键字位置)。
         */
        internal fun isTargetDone(blob: String, kind: PunchKind): Boolean {
            if (blob.isEmpty()) return false

            val key = when (kind) {
                PunchKind.CLOCK_IN -> "上班"
                PunchKind.CLOCK_OUT -> "下班"
                // 手动打卡不按班次推断：调用方自己决定打哪一班。
                PunchKind.MANUAL -> return false
            }
            val otherKey = if (key == "上班") "下班" else "上班"
            val window = 90

            var from = 0
            while (true) {
                val idx = blob.indexOf(key, from)
                if (idx < 0) return false

                // 窗口终点 = min(关键字后 90 字符, 下一个"对侧班次关键字")
                //
                // 为什么要截到对侧关键字：真实状态卡里
                // "上班09:30 09:19已打卡 下班18:30 18:30已打卡 更新打卡"
                // 是连在一起的。若不截断，取"上班"后 90 字符会把
                // **下班卡**的"已打卡"算成上班卡的完成状态 → 该打时不打。
                var end = minOf(blob.length, idx + window)
                val otherIdx = blob.indexOf(otherKey, idx + key.length)
                if (otherIdx in 0 until end) end = otherIdx

                val slice = blob.substring(idx, end)

                // 【关键】"未打卡"优先排除。
                //
                // "未打卡" 不含 "已打卡"，理论上不冲突；但有些钉钉版本会渲染成
                // "未 打卡"、或用图形+文字混合，显式排除能挡掉一整类误判。
                if (DingTalkUi.NOT_DONE_SIGNALS.any { slice.contains(it) }) {
                    from = idx + key.length
                    continue
                }

                // 【关键】把"更新打卡"从判定窗口里剔除后再找"已打卡"。
                //
                // 真实文案 "18:30已打卡  更新打卡" —— "更新打卡"是"再打一次"的
                // 入口，不是完成标记。虽然它不含"已打卡"（所以直接 contains 不会
                // 误命中），但为了后续维护者不踩坑，这里显式声明并剔除。
                val cleaned = slice.replace("更新打卡", "").replace("打卡记录", "")

                if (DONE_MARKERS.any { cleaned.contains(it) }) return true
                from = idx + key.length
            }
        }

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
            /**
             * 本次要打的是哪一班。
             *
             * ## 为什么必须传进来（真机踩到的"下班卡被识别成上班卡"）
             *
             * 成功文案是**带班次**的：`18:30 下班打卡·成功` / `09:19 上班打卡·成功`。
             * 旧实现只问 containsSuccess()，谁的成功文案都算数 —— 于是：
             *   · 页面上残留一条上班卡的成功卡片时，打下班卡会读到它并判成功；
             *   · 反之亦然。结果就是用户看到"下班卡识别成上班卡"。
             *
             * 现在要求：命中的成功文案必须**明确属于**目标班次。
             * 不带班次的通用文案（如"打卡·成功"）仍然接受，
             * 因为它们本身就出现在本次操作之后。
             */
            kind: PunchKind = PunchKind.MANUAL,
        ): String? {
            // ---- 1. 失败信号优先 ----
            //
            // 必须先查失败。边缘情况：页面同时出现"打卡成功"和"不在打卡范围"
            // （部分成功的脏数据）时，宁可判失败让用户知情，
            // 也不能报成功让用户以为打上了。
            DingTalkUi.containsFailure(blob)?.let { return null }

            // ---- 2. 明确的成功信号（必须与目标班次一致）----
            // 钉钉弹出的结果卡片文案，这是最可靠的依据。
            DingTalkUi.containsSuccess(blob)?.let { signal ->
                if (successMatchesKind(signal, blob, kind)) return signal
                // 命中的成功文案属于**另一班**：不能算本次成功，继续往下走。
            }

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

        /**
         * 命中的成功文案是否属于本次要打的那一班。
         *
         * ## 判定口径
         *
         * 1. 文案里带**具体班次词**时，必须与目标班次一致：
         *      · 目标是下班卡 → 只接受含「下班」的文案；
         *      · 目标是上班卡 → 只接受含「上班」的文案。
         *    出现「上班打卡·成功」而目标是下班 → 判定为**不属于本次**。
         *
         * 2. 文案里**没有**任何班次词（如通用的「打卡·成功」）→ 接受。
         *    这种文案只会出现在本次操作刚完成之后。
         *
         * 3. 目标未知（MANUAL 且未指定）→ 不做班次校验，退回旧行为。
         *
         * @param signal 命中的成功文案原文
         * @param blob 整个页面文本（用于判断是否两班都出现过）
         */
        internal fun successMatchesKind(
            signal: String,
            blob: String,
            kind: PunchKind,
        ): Boolean {
            if (kind == PunchKind.MANUAL) return true

            val signalHasIn = signal.contains("上班")
            val signalHasOut = signal.contains("下班")

            // 文案自带班次词 → 必须与目标一致
            if (signalHasIn || signalHasOut) {
                return when (kind) {
                    PunchKind.CLOCK_IN -> signalHasIn && !signalHasOut
                    PunchKind.CLOCK_OUT -> signalHasOut && !signalHasIn
                    PunchKind.MANUAL -> true
                }
            }

            // 文案不带班次词（通用「打卡·成功」）→ 接受。
            // 但要防止一种脏情况：页面上同时出现两班的成功文案，
            // 而命中的是通用那条 —— 此时无法区分，仍然接受
            // （因为通用文案通常就是本次弹出的）。
            return true
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

        // ==================== 时间红线：唯一实现 ====================
        //
        // ## 为什么必须是「唯一实现」
        //
        // 这条红线一度被写成两份：
        //   · checkSafetyGate 里的闸门 1（点击前第一次判定）
        //   · recheckTimeWindow（外勤等待 15 分钟后复查）
        //
        // 两份常量相同但表达式各写各的。任何边界修正只改一处，就会出现
        // 「第一次放行、复查拒绝」（或反之）的矛盾行为 —— 真机表现就是
        // 莫名其妙漏打卡，而且日志两边都「看起来对」。
        //
        // 现在统一走下面的纯函数。它们不碰 Context、不碰 UI、不读系统时钟，
        // 全部由调用方注入 now/targetAt，因此可以被单元测试精确钉住边界。

        /** 上班卡可打窗口的下界（target 之前多久允许开始） */
        internal fun clockInEarliestAt(targetAt: Long): Long =
            targetAt - PunchSchedule.PRE_WARM_SECONDS * 1000L

        /**
         * 上班卡可打窗口的上界（过此即停止，避免生成迟到卡）。
         *
         * = target + 60s - 5s = target + 55s
         */
        internal fun clockInMustStopAt(targetAt: Long): Long =
            targetAt + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS

        /** 下班卡最早可打时刻（= target + 2s，绝不提前） */
        internal fun clockOutEarliestAt(targetAt: Long): Long =
            targetAt + CLOCK_OUT_SAFETY_MARGIN_MS

        /**
         * 判定某个时刻能否打某一班（纯函数，唯一真值来源）。
         *
         * @return null 表示可以打；否则返回拒绝原因
         */
        internal fun timeWindowVerdict(
            kind: PunchKind,
            now: Long,
            targetAt: Long,
        ): TimeWindowVerdict? {
            when (kind) {
                PunchKind.CLOCK_OUT -> {
                    val allowedAt = clockOutEarliestAt(targetAt)
                    if (now < allowedAt) {
                        return TimeWindowVerdict.Early((allowedAt - now) / 1000L)
                    }
                }

                PunchKind.CLOCK_IN -> {
                    val earliestAt = clockInEarliestAt(targetAt)
                    if (now < earliestAt) {
                        return TimeWindowVerdict.TooEarlyForClockIn((earliestAt - now) / 1000L)
                    }
                    val mustStopAt = clockInMustStopAt(targetAt)
                    if (now > mustStopAt) {
                        return TimeWindowVerdict.Late((now - targetAt) / 1000L)
                    }
                }

                // 手动打卡由用户主动触发，不套时间闸门（但仍会过外勤闸门）。
                PunchKind.MANUAL -> Unit
            }
            return null
        }
    }

    /** 时间窗口判定结果（与 UI/日志文案解耦，便于单测） */
    sealed interface TimeWindowVerdict {
        /** 下班时间未到，还差 [remainSec] 秒 */
        data class Early(val remainSec: Long) : TimeWindowVerdict

        /** 上班时间还没到（预热窗口之外），还差 [aheadSec] 秒 */
        data class TooEarlyForClockIn(val aheadSec: Long) : TimeWindowVerdict

        /** 已过迟到停手点，已过 [lateSec] 秒 */
        data class Late(val lateSec: Long) : TimeWindowVerdict
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
        /**
         * 本次调度的**真实目标时刻**（由闹钟 Intent 传入）。
         *
         * ## 为什么必须由外部传入（P0 修复）
         *
         * 旧实现里安全闸门自己算目标时刻：
         *
         *     val targetAt = startOfDay(now) + settings.clockInMinute * 60_000L
         *
         * 这有两个致命问题：
         * 1. **单日临时覆盖（F1）被完全忽略** —— PunchSchedule.Override 只在
         *    排班计算里生效，闸门压根看不到它，覆盖日会按常规时间去判迟到；
         * 2. 它把"哪一班"变成了全局推算，而不是"这次闹钟让我打的是哪一班"。
         *
         * 现在目标是调度事实（Intent 里的 EXTRA_TARGET_AT），闸门只做判定。
         * 传 0 时（例如手动打卡）才回退到按设置推算。
         */
        targetAt: Long = 0L,
        /**
         * 预热模式：把「点亮 → 解锁 → 拉起钉钉 → 进入考勤页」提前做完，
         * 然后**停在考勤页等目标时刻**，到点只做"过闸门 → 点击 → 确认"。
         *
         * ## 为什么必须这样（这是上班卡能不能打成的关键）
         *
         * 上班卡的合法窗口是 [target, target + 55s]（迟到边界 60s 减 5s 余量）。
         * 而这条链路的固定开销是：
         *
         *     点亮屏幕   ≤ 4s 轮询
         *     解锁       0.8s + 最多 3×6s（三次密码尝试）
         *     硬等钉钉    8s（DINGTALK_WARMUP_MS，需求 F3.4 明确要求）
         *     就绪轮询   ≤ 20s
         *     导航点击   每步 6s，最多 3 次尝试
         *
         * 真机日志实测：18:30 那次从"开始执行"到"进入考勤页"用了 27.7 秒。
         * 如果这些都从 target 时刻才开始，55 秒窗口连"还没点到按钮"都撑不住 ——
         * 上班卡会稳定被自己的闸门拒绝，用户看到的是"已过上班时间…已跳过"。
         *
         * 所以 PREWARM（target−75s）必须**真的预热**：
         * 把 27 秒的开销挪到窗口之外，窗口内只剩下几百毫秒的点击。
         *
         * 预热失败也不要紧：FIRE / RETRY 闹钟仍然在系统里（P0-1 已修），
         * 会各自完整重跑一遍。
         */
        holdUntilTarget: Boolean = false,
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
            //
            // 【必须判断开关】设置里关掉"屏幕接管提示"后就不能再弹 ——
            // 之前这里是无条件 show()，开关完全是空转的。
            if (AppConfig.showControlHud.value) ControlHudOverlay.show(
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

            // ---- 5.4 预热：停在考勤页等目标时刻 ----
            //
            // 走到这里说明"点亮 / 解锁 / 拉起钉钉 / 进入考勤页"都完成了，
            // 现在离 target 可能还有几十秒。**先不判闸门**——
            // 闸门要看时间，提前判必然被判成"距上班时间还有 N 秒"而放弃。
            //
            // 这段时间里只做两件事：
            //   1. 每秒醒一次检查取消（用户可能点了暂停/取消）；
            //   2. 到点（或已在窗口内）立刻放行。
            //
            // 同时守住一条：**绝不在 target 之前点击**。
            if (holdUntilTarget && targetAt > 0L) {
                val waitMs = targetAt - System.currentTimeMillis()
                if (waitMs > 0L) {
                    log("预热完成，停在考勤页等待目标时刻（还有 " + (waitMs / 1000L) + " 秒）")
                    // 分段等待，保证暂停/取消能立刻生效
                    var remaining = waitMs
                    while (remaining > 0) {
                        val slice = minOf(remaining, 1_000L)
                        delay(slice)
                        remaining -= slice
                        currentCoroutineContext().ensureActive()
                    }
                    log("已到目标时刻，开始点击")
                } else {
                    log("预热期间已过目标时刻（超出 " + (-waitMs / 1000L) + " 秒），直接进入点击")
                }
            }

            // ---- 5.5 安全闸门（绝不能跳过） ----
            val guard = checkSafetyGate(kind, targetAt)
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

            // ---- 5.95 极速打卡：进页面时就已打好，绝不能再去点 ----
            //
            // ## 这是真机上真实发生的情况（用户明确反馈）
            //
            // 钉钉自带「极速打卡」：进入考勤页的瞬间它就自己把卡打完了，
            // 页面上显示「已打卡」+ 打卡时间，**没有可点的按钮**。
            //
            // 旧代码把这一步放在点击**之后**：先 clickPunchButton（点了个空），
            // 再判断 !clicked && alreadyDoneBefore。顺序颠倒带来两个后果：
            //   1. 明明已经打好了，还要去点一下 —— 点到的可能是状态卡或记录入口；
            //   2. 判断完又不 return，继续走 verifyResult 等"打卡成功"四个字，
            //      而极速打卡根本不会弹那个卡片 → 判失败（正是用户看到的现象）。
            //
            // 正确顺序：先看基线 → 已完成就直接收工 → 否则才点。
            if (alreadyDoneBefore) {
                ControlHudOverlay.hide()
                val evidence = "极速打卡（无需点击）：进入考勤页时目标时段已是已打卡状态"
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

            // ---- 6. 点击打卡 ----
            val clicked = clickPunchButton(kind)

            if (!clicked) {
                // 点不到按钮时再确认一次：可能是基线读取与点击之间
                // 钉钉的极速打卡正好完成（这个时间差在真机上确实存在）。
                if (readTargetDoneState(kind)) {
                    ControlHudOverlay.hide()
                    val evidence = "点击阶段发现目标时段已完成（极速打卡抢先）"
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

        // ==================== 导航顺序（按用户实测的真实路径）====================
        //
        // 用户明确说明：**打开钉钉默认在「消息」页，消息页顶部就有一个「打卡」
        // 快捷入口，点它才是真正的打卡页面。**
        //
        // 所以主路径是：消息页顶部「打卡」→ 考勤页。
        // 旧实现反着来：先进「工作台」→ 找「考勤打卡」应用图标，
        // 把顶部快捷入口当兜底。真机日志里那句
        // 「工作台 tab：当前已在考勤页」说明这条链路本来就走得磕磕绊绊。
        //
        // 现在的顺序：
        //   1. 先试顶部「打卡」快捷入口（最短、最稳，正是用户说的那条）；
        //   2. 不行再走「工作台」→「考勤打卡」；
        //   3. 都不行返回 false，由调用方降级到 root 通道。

        dismissPopups(service)

        // ---- 路径 A：消息页顶部「打卡」快捷入口 ----
        if (tryQuickPunchEntry(service)) return true

        // ---- 路径 B：工作台 → 考勤打卡 ----
        if (tapAndVerify(service, DingTalkUi.TAB_WORK, "工作台 tab")) {
            repeat(MAX_RETRY) { attempt ->
                dismissPopups(service)
                if (tapAndVerify(service, DingTalkUi.ENTRY_ATTENDANCE, "考勤入口")) {
                    log("已通过工作台进入考勤页")
                    return true
                }
                log("首屏未找到考勤入口，滚动查找（第 " + (attempt + 1) + " 次）")
                service.swipeUp()
                delay(1200)
            }
        } else {
            log("未能进入工作台")
        }

        // ---- 路径 C：从工作台返回后再试一次顶部快捷入口 ----
        service.pressBack()
        delay(800)
        if (tryQuickPunchEntry(service)) return true

        return false
    }

    /**
     * 尝试点击消息页顶部的「打卡」快捷入口。
     *
     * ## 为什么要单独抽出来
     *
     * 这个入口的节点本身常常 **不可点击**（text 挂在内层 TextView 上），
     * 必须点它的可点击父节点 —— 这正是原注释里记录的坑。
     * 而且它会和页面上其它含「打卡」字样的元素（打卡记录、打卡提醒）混淆，
     * 所以匹配要**精确**，不能包含匹配。
     *
     * @return true 表示已经进入考勤页
     */
    private suspend fun tryQuickPunchEntry(service: PunchAccessibilityService): Boolean {
        val before = service.readAllText()
        // 已经在这页了就别再点
        if (looksLikeAttendancePage(before)) {
            log("顶部快捷入口：当前已在考勤页")
            return true
        }

        // 「打卡」二字太短，用的是精确相等匹配（findClickableByText 第一轮），
        // 不会命中「打卡记录」「打卡提醒」这类长文案。
        if (!service.clickByText(DingTalkUi.QUICK_PUNCH)) {
            log("顶部快捷入口：未找到「打卡」入口")
            return false
        }
        log("顶部快捷入口：已点击「打卡」，等待进入考勤页")

        val entered = waitUntil(15_000L) {
            looksLikeAttendancePage(service.readAllText())
        }
        if (entered) {
            log("顶部快捷入口：已进入考勤页")
        } else {
            log("顶部快捷入口：点击后未进入考勤页")
        }
        return entered
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

        // 【联调诊断】确认无障碍到底能不能读到考勤页文本。
        //
        // 真机结论（investigator 实测）：打卡页是 UCWebView/H5，
        // 且中间有每秒跳动的时钟 → uiautomator dump 永远 could not get idle state。
        // 所以判定**只能**走无障碍实时节点树（主动 rootInActiveWindow 遍历），
        // 不能走 dump、也不能依赖事件缓存（事件里 Text 常常全空）。
        //
        // 这里打长度而不是内容：既不泄露页面内容，又足以判断读通没读通。
        runCatching {
            val svc = PunchAccessibilityService.get()
            val lines = svc?.readTextLines()?.size ?: -1
            Log.i(
                TAG,
                "诊断 a11y: kind=" + kind.name +
                    " readAllText.len=" + text.length +
                    " readTextLines.size=" + lines +
                    " doneMarkers=" + DONE_MARKERS.count { text.contains(it) },
            )
        }

        if (text.isEmpty()) return false
        return targetAlreadyDone(text, kind)
    }

    /** 判断当前页面是否像考勤打卡页 */
    /**
     * 当前页面是否像"考勤打卡页"。
     *
     * ## 这里曾是整条链路失败的起点（真机定位结论）
     *
     * 旧判据有一句 text.contains("考勤打卡")，看似合理，实际必错：
     *
     * **消息列表里有一条推送，文案是「考勤打卡: 11-11 考勤全部正常」。**
     *
     * 于是：
     *   1. tapAndVerify 第一步读页面 -> looksLikeAttendancePage 返回 true
     *      -> 直接认定"当前已在考勤页"，两次点击（工作台 tab、考勤入口）
     *      **一次都没发生**；
     *   2. 但页面仍停在消息列表；
     *   3. 后续时间闸门 / 基线 / 点击 / 判定全部在**错误页面**上执行。
     *
     * 真机铁证：18:30 那次日志里的 root 点击打卡按钮 @ 737,785，
     * 反查消息页 dump，中心点恰为 (737,785) 的节点是
     *   resource-id = session_content_tv
     *   text        = "考勤打卡: 11-11 考勤全部正常"
     * 即它点的是**推送条目**，不是打卡按钮。逐像素吻合。
     *
     * ## 现在的判据：只认可考勤页独有的**结构性**证据
     *
     * 单靠"某个词出现过"不够，必须出现只有在考勤页才有的组合：
     *   1. 打卡按钮（上班打卡 / 下班打卡）
     *   2. 状态卡组合（上班 + 下班 + 已打卡 同现，或出现更新打卡）
     *   3. 外勤提示（未进入考勤范围 / 可外勤打卡 / 如需内勤打卡）
     *   4. 班次词 + 打卡记录/补卡
     */
    private fun looksLikeAttendancePage(text: String): Boolean {
        if (text.isEmpty()) return false

        // ---- 先排除消息列表的推送文案 ----
        // 推送格式是 "考勤打卡: 11-11 考勤全部正常"，特征是「考勤打卡」后紧跟冒号。
        // 真正的考勤页不会这么写 —— 那里是按钮和状态卡。
        if (text.contains("考勤打卡:") || text.contains("考勤打卡：")) {
            val strong = text.contains("上班打卡") || text.contains("下班打卡") ||
                text.contains("未进入考勤范围") || text.contains("可外勤打卡")
            if (!strong) {
                log("疑似消息列表推送（含考勤打卡:），不判定为考勤页")
                return false
            }
        }

        // ---- 强证据 1：打卡按钮 ----
        if (text.contains("上班打卡") || text.contains("下班打卡")) return true

        // ---- 强证据 2：状态卡组合 ----
        // 真实状态卡："上班09:30 09:19已打卡 下班18:30 18:30已打卡 更新打卡"
        val hasIn = text.contains("上班")
        val hasOut = text.contains("下班")
        val hasDone = text.contains("已打卡")
        if (hasIn && hasOut && hasDone) return true
        if ((hasIn || hasOut) && text.contains("更新打卡")) return true

        // ---- 强证据 3：外勤提示 ----
        if (text.contains("未进入考勤范围") || text.contains("可外勤打卡") ||
            text.contains("如需内勤打卡")
        ) {
            return true
        }

        // ---- 弱证据：班次词 + 打卡记录/补卡 ----
        if ((hasIn || hasOut) && (text.contains("打卡记录") || text.contains("补卡"))) return true

        return false
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

            // ---- 优先：消息页顶部「打卡」快捷入口（用户实测的真实路径）----
            val quickNode = UiHierarchy.findNode(xml, DingTalkUi.QUICK_PUNCH)
            if (quickNode != null) {
                RootShell.tap(quickNode.centerX, quickNode.centerY)
                log("root 点击顶部「打卡」入口 @ " + quickNode.centerX + "," + quickNode.centerY)
                delay(4000)
                val after = RootShell.dumpUiHierarchy()
                if (after != null && looksLikeAttendancePage(UiHierarchy.allText(after))) {
                    log("root 已进入考勤页")
                    return true
                }
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

    /**
     * 点击打卡按钮。
     *
     * ## 两个必须修的问题
     *
     * 1. **候选拼接方向错了**：旧代码是
     *
     *        CLOCK_OUT -> BTN_CLOCK_OUT + BTN_CLOCK_IN
     *
     *    下班卡的候选列表里塞了上班按钮当兜底。真机上如果页面上
     *    「下班打卡」不可点（已打完/不在范围），就会去点「上班打卡」，
     *    可能触发"更新上班卡"之类的错误操作。
     *    **打下班绝不应该去点上班按钮**，宁可失败。
     *
     * 2. **裸 2 字候选危险**：DingTalkUi.BTN_* 里带 "上班"/"下班"，
     *    而打卡页顶部状态卡文案就是「上班 09:00」，会被当成按钮命中，
     *    点它的可点击祖先可能跳到打卡记录/详情页。
     *    这里在调用前把裸 2 字候选过滤掉，只保留 "上班打卡" 这种明确按钮文案。
     */
    private suspend fun clickPunchButton(kind: PunchKind): Boolean {
        val raw = when (kind) {
            PunchKind.CLOCK_OUT -> DingTalkUi.BTN_CLOCK_OUT
            else -> DingTalkUi.BTN_CLOCK_IN
        }
        // 只保留长度 >= 3 的明确按钮文案（"上班打卡" 是 4 字）。
        val candidates = raw.filter { it.length >= 3 }
        if (candidates.isEmpty()) {
            log("打卡按钮候选为空（配置错误）")
            return false
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
    private suspend fun checkSafetyGate(kind: PunchKind, scheduledTargetAt: Long): PunchResult? {
        // ---------- 闸门 1：时间窗口 ----------
        val now = System.currentTimeMillis()
        val settings = AppConfig.settings.value

        // 目标时刻来源（优先级从高到低）：
        //   1. 闹钟 / 手动打卡带来的真实 targetAt —— 唯一正确来源；
        //      - 单日临时覆盖（PunchSchedule.Override）只有这条路径能看到；
        //      - 手动打卡现在传的是「该班次的计划时刻」，不再传 now，
        //        否则上班卡窗口 [target-75s, target+55s] 会永远放行（见 MainViewModel）。
        //   2. MANUAL：没有 targetAt 时沿用 now（MANUAL 本来就不套时间闸门）；
        //   3. 兜底：按今天的设置时间推算（只应在旧调用方漏传时命中）。
        val targetAt = when {
            scheduledTargetAt > 0L -> scheduledTargetAt
            kind == PunchKind.MANUAL -> now
            else -> {
                val startOfDay = PunchSchedule.startOfDay(now)
                val minute = when (kind) {
                    PunchKind.CLOCK_OUT -> settings.clockOutMinute
                    else -> settings.clockInMinute
                }
                startOfDay + minute * 60_000L
            }
        }
        val deltaMs = now - targetAt

        log(
            "安全闸门：kind=" + kind.name +
                " 目标=" + PunchSchedule.formatTime(targetAt) +
                " 偏差=" + (deltaMs / 1000L) + "秒"
        )

        // 走唯一实现判定（见 companion 里的 timeWindowVerdict）。
        // 这里的职责只剩「把判定结果翻译成 PunchResult」。
        when (val verdict = timeWindowVerdict(kind, now, targetAt)) {
            null -> log("时间窗校验通过（kind=" + kind.name + "）")

            is TimeWindowVerdict.Early -> {
                val remainText = if (verdict.remainSec >= 60) {
                    (verdict.remainSec / 60).toString() + " 分 " + (verdict.remainSec % 60) + " 秒"
                } else {
                    verdict.remainSec.toString() + " 秒"
                }
                log("拒绝：下班时间未到，还差 " + remainText)
                return PunchResult(
                    success = false,
                    stage = PunchStage.CLICK,
                    message = "距下班时间还有 " + remainText + "，已阻止提前打下班卡",
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                    abandonedByGate = true,
                    // 【P0】太早 ≠ 这一班已处理。带上这个标记，收尾时不会取消
                    // 今天 18:30 的闹钟（真机实测会漏打，见字段注释）。
                    rejectedTooEarly = true,
                )
            }

            is TimeWindowVerdict.TooEarlyForClockIn -> {
                log("拒绝：离上班时间还差 " + verdict.aheadSec + " 秒（预热窗口之外），不提前打卡")
                return PunchResult(
                    success = false,
                    stage = PunchStage.CLICK,
                    message = "距上班时间还有 " + verdict.aheadSec + " 秒，已阻止提前打上班卡",
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                    abandonedByGate = true,
                    rejectedTooEarly = true,
                )
            }

            is TimeWindowVerdict.Late -> {
                val lateMin = verdict.lateSec / 60
                val lateRestSec = verdict.lateSec % 60
                val lateText = if (lateMin > 0) {
                    lateMin.toString() + " 分 " + lateRestSec + " 秒"
                } else {
                    lateRestSec.toString() + " 秒"
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
                    abandonedByGate = true,
                )
            }
        }

        // ---------- 闸门 2：外勤检查（需要读界面，可能要等） ----------
        val outdoorVerdict = waitUntilInRange(kind)
        if (outdoorVerdict != null) return outdoorVerdict

        // ---------- 闸门 3：等待结束后**必须重新过一遍时间闸门** ----------
        //
        // ## 这是一个真实的漏卡/打错卡漏洞
        //
        // 外勤等待最长 15 分钟（OUTDOOR_WAIT_TOTAL_MS）。旧实现里：
        //
        //     闸门1 时间窗（此刻 09:30:10 通过）
        //       → 闸门2 外勤等待（一直等到 09:44）
        //       → 返回 null（可以打）→ 直接点击
        //
        // 也就是说：时间闸门只判了**进入等待之前**的那一刻。
        // 09:30 目标、09:44 才走出地铁进公司范围 —— 仍然会去打，
        // 生成一张迟到卡。这正好击穿「宁可缺卡也不打迟到卡」的红线。
        //
        // 同样地，下班卡等 15 分钟本来没问题（越等越晚），
        // 但上班卡的 55 秒窗口根本撑不住这种等待。
        //
        // 所以这里用等待结束后的真实时间**再判一次**，语义与首次完全一致。
        return recheckTimeWindow(kind, targetAt)
    }

    /**
     * 时间窗复查（纯判定，不涉及界面）。
     *
     * 与 [checkSafetyGate] 里的闸门 1 完全同口径 ——
     * 因为它们本来就该是同一套规则，只是执行时刻不同。
     */
    private fun recheckTimeWindow(kind: PunchKind, targetAt: Long): PunchResult? {
        val now = System.currentTimeMillis()

        when (kind) {
            PunchKind.CLOCK_OUT -> {
                val allowedAt = targetAt + CLOCK_OUT_SAFETY_MARGIN_MS
                if (now < allowedAt) {
                    val remainSec = (allowedAt - now) / 1000L
                    log("外勤等待后复查：下班时间仍未到（还差 " + remainSec + " 秒），放弃")
                    return PunchResult(
                        success = false,
                        stage = PunchStage.CLICK,
                        message = "距下班时间还有 " + remainSec + " 秒，已阻止提前打下班卡",
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                        abandonedByGate = true,
                        // 同闸门 1：外勤等待后仍然"太早"也不代表这一班已处理，
                        // 不能让收尾逻辑把这班标记掉（否则当天闹钟被取消）。
                        rejectedTooEarly = true,
                    )
                }
            }

            PunchKind.CLOCK_IN -> {
                val mustStopAt = targetAt + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS
                if (now > mustStopAt) {
                    val lateSec = (now - targetAt) / 1000L
                    log("外勤等待后复查：已超出迟到停手点 " + lateSec + " 秒，放弃本次打卡")
                    return PunchResult(
                        success = false,
                        stage = PunchStage.CLICK,
                        message = "外勤等待期间已越过迟到边界（已过 " + lateSec +
                            " 秒），为避免生成迟到记录已跳过。如需打卡请手动操作。",
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                        abandonedByGate = true,
                    )
                }
            }

            PunchKind.MANUAL -> Unit
        }
        return null
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
                        evidence = "考勤页显示目标时段已完成：" + signal,
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
                    abandonedByGate = true,
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
    private fun targetAlreadyDone(blob: String, kind: PunchKind): Boolean =
        isTargetDone(blob, kind)


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

        // 【关键】记录点击时刻，并且把"点击之前"的历史文本丢掉重新开始记。
        //
        // 旧实现直接读 recentTextBlob()（32 条历史事件的累积），
        // 只在整个 execute() 开头清空一次。后果很严重：
        //   · 本次运行中只要某一瞬间出现过"网络异常"/"定位失败"，
        //     containsFailure() 一票否决 → 之后**即使打成功也永远判失败**，
        //     三次重试全部无效；
        //   · 反向也成立：历史里残留的"打卡成功"可能顶替本次证据，
        //     没打上也报成功（这是最坏的一种 bug）。
        //
        // 现在点击完成的那一刻清空历史，只让"点击之后"的界面事件参与判定。
        service?.resetEventHistoryOnly()

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
            judgeResult(blob, alreadyDoneBefore, doneNow, kind)?.let { evidence ->
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