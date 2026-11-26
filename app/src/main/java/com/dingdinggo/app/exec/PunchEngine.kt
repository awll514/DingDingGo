package com.dingdinggo.app.exec

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.ChannelPreference
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchKind
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
     */
    val rejectedTooEarly: Boolean = false,

    /** 本次上班打卡因超过迟到边界被闸门拦截 */
    val rejectedLate: Boolean = false,

    /** 若被迟到闸门拦截，超出目标上班时刻的秒数 */
    val lateSeconds: Long = 0L,

    /** 从钉钉界面/极速打卡横幅解析出的真实打卡时间戳（毫秒，0 表示未解析到） */
    val actualAt: Long = 0L,

    /** 从钉钉界面解析出的真实打卡时间文本（如 "09:29" / "18:30:08"） */
    val actualTimeText: String? = null,
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
                if (idx < 0) {
                    DingTalkUi.containsAlreadyPunchedDialog(blob)?.let { signal ->
                        if (alreadyPunchedDialogMatchesKind(signal, blob, kind, requireExplicitShift = true)) {
                            return true
                        }
                    }
                    return false
                }

                // ---- 两栏横排卡片配对判定（如 "上班 09:30 下班 18:30 09:30已打卡 18:30未打卡"）----
                //
                // 真实 UCWebView 状态卡常按行平铺：第一行是两栏表头（上班/下班），第二行才是两个状态。
                // 此时 "上班" 与 "下班" 之间没有任何状态词，直接截断到 "下班" 会漏掉上班的 "已打卡"，
                // 而从 "下班" 往后读又会把第一栏的 "09:30已打卡" 错当成下班状态。
                val prevOther = if (idx > 0) blob.lastIndexOf(otherKey, idx - 1) else -1
                if (prevOther >= 0 && idx - prevOther <= 40) {
                    val gap = blob.substring(prevOther + otherKey.length, idx)
                    if (findOrderedStatusTokens(gap).isEmpty()) {
                        val tailEnd = minOf(blob.length, idx + key.length + 120)
                        val tokens = findOrderedStatusTokens(blob.substring(idx + key.length, tailEnd))
                        if (tokens.size >= 2) {
                            if (tokens[1]) return true
                            from = idx + key.length
                            continue
                        }
                        if (tokens.size == 1) {
                            // 两栏表头后目前只渲染出一个状态词，归属通常属于第一栏，不能越界误判第二栏已完成
                            from = idx + key.length
                            continue
                        }
                    }
                }

                // 窗口终点 = min(关键字后 90 字符, 下一个"对侧班次关键字")
                var end = minOf(blob.length, idx + window)
                val otherIdx = blob.indexOf(otherKey, idx + key.length)
                if (otherIdx in 0 until end) end = otherIdx

                val slice = blob.substring(idx, end)

                if (otherIdx >= idx + key.length &&
                    otherIdx - idx <= 40 &&
                    findOrderedStatusTokens(slice).isEmpty()
                ) {
                    val tailEnd = minOf(blob.length, otherIdx + otherKey.length + 120)
                    val tokens = findOrderedStatusTokens(blob.substring(otherIdx + otherKey.length, tailEnd))
                    if (tokens.size >= 2) {
                        if (tokens[0]) return true
                        from = otherIdx + otherKey.length
                        continue
                    }
                }

                // 【关键】"未打卡"优先排除。
                if (DingTalkUi.NOT_DONE_SIGNALS.any { slice.contains(it) }) {
                    from = idx + key.length
                    continue
                }

                // 【关键】把"更新打卡"从判定窗口里剔除后再找"已打卡"。
                val cleaned = slice.replace("更新打卡", "").replace("打卡记录", "")

                if (DONE_MARKERS.any { cleaned.contains(it) }) return true
                from = idx + key.length
            }
        }

        /** 从钉钉界面或极速打卡横幅中解析出的真实打卡时间 */
        data class ExtractedPunchTime(
            val millis: Long,
            val displayText: String,
        )

        private data class StatusTokenHit(
            val done: Boolean,
            val timeText: String?,
        )

        private val TIME_AT_END_REGEX = Regex("([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\s*$")
        private val TIME_AT_START_REGEX = Regex(
            "^\\s*[:：]?\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?" +
                "(?!\\s*(?:未打卡|尚未打卡|已打卡|已签到|上班|下班))"
        )
        private val BANNER_TIME_BEFORE_REGEX = Regex(
            "([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\s*" +
                "(?:上班极速打卡|下班极速打卡|上班打卡|下班打卡|极速打卡|自动打卡|打卡)[·•.\\s]*(?:已?成功|已完成)"
        )
        private val BANNER_TIME_AFTER_REGEX = Regex(
            "(?:上班极速打卡|下班极速打卡|极速上班打卡|极速下班打卡|极速打卡[·•.\\s]*(?:已?成功|已完成)|" +
                "已为您(?:极速|成功)?打卡|上班打卡[·•.\\s]*已?成功|下班打卡[·•.\\s]*已?成功|打卡时间|已打卡)" +
                "\\s*[:：\\s]*(?:[^\\d\\n]{0,8}\\s*)?([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?" +
                "(?!\\s*(?:未打卡|尚未打卡))"
        )

        /**
         * 从钉钉考勤页或极速打卡顶部横幅中提取本次打卡的**真实时间**（纯函数）。
         *
         * 优先级：
         * 1. 目标班次状态卡上明确的「HH:mm 已打卡」（最权威，直接对应钉钉服务端记录）；
         * 2. 顶部横幅 / 结果弹窗里附带的时间（按行逆序，让覆写前一条的第二条横幅优先）；
         * 3. 统计页「上班 09:29 极速打卡」摘要格式。
         *
         * 严格排除状态卡表头（「上班09:30」/「下班18:30」）与按钮大时钟（「09:55:21」）。
         */
        internal fun extractActualPunchTime(
            blob: String,
            lines: List<String> = emptyList(),
            kind: PunchKind,
            referenceMillis: Long,
        ): ExtractedPunchTime? {
            // 1) 状态卡明确带班次的已打卡时间（最准确）
            val cardTexts = buildList {
                lines.forEach { if (it.isNotBlank()) add(it) }
                if (blob.isNotBlank()) add(blob)
            }
            for (text in cardTexts) {
                extractStatusCardTime(text, kind)?.let { t ->
                    return resolveTimeOnDay(t, referenceMillis)
                }
            }

            // 2) 横幅 / 结果弹窗文案里的时间（逆序遍历：后一条覆盖前一条的横幅优先）
            val bannerCandidates = buildList {
                lines.asReversed().forEach { if (it.isNotBlank()) add(it) }
                if (blob.isNotBlank()) add(blob)
            }
            for (raw in bannerCandidates) {
                if (!matchesShiftFilter(raw, kind)) continue
                val norm = DingTalkUi.normalizeSeparators(raw)
                BANNER_TIME_BEFORE_REGEX.find(norm)?.let { m ->
                    val rawTime = formatMatchedTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    return resolveTimeOnDay(rawTime, referenceMillis)
                }
                BANNER_TIME_AFTER_REGEX.find(norm)?.let { m ->
                    val rawTime = formatMatchedTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    return resolveTimeOnDay(rawTime, referenceMillis)
                }
            }

            // 3) 统计/详情卡片摘要（如 "上班 09:29 极速打卡"）
            if (kind == PunchKind.CLOCK_IN || kind == PunchKind.CLOCK_OUT) {
                val shift = if (kind == PunchKind.CLOCK_IN) "上班" else "下班"
                val summaryRe = Regex(shift + "\\s+([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\s*极速打卡")
                for (text in cardTexts) {
                    summaryRe.find(text)?.let { m ->
                        val rawTime = formatMatchedTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                        return resolveTimeOnDay(rawTime, referenceMillis)
                    }
                }
            }

            return null
        }

        private fun extractStatusCardTime(blob: String, kind: PunchKind): String? {
            if (blob.isEmpty() || kind == PunchKind.MANUAL) return null
            val key = if (kind == PunchKind.CLOCK_IN) "上班" else "下班"
            val otherKey = if (key == "上班") "下班" else "上班"
            val window = 90
            var from = 0
            while (true) {
                val idx = blob.indexOf(key, from)
                if (idx < 0) return null

                // 两栏表头第 2 栏（如 "上班 09:30 下班 18:30 09:29已打卡 18:30已打卡"）
                val prevOther = if (idx > 0) blob.lastIndexOf(otherKey, idx - 1) else -1
                if (prevOther >= 0 && idx - prevOther <= 40) {
                    val gap = blob.substring(prevOther + otherKey.length, idx)
                    if (findOrderedStatusHits(gap).isEmpty()) {
                        val tailEnd = minOf(blob.length, idx + key.length + 120)
                        val hits = findOrderedStatusHits(blob.substring(idx + key.length, tailEnd))
                        if (hits.size >= 2) {
                            if (hits[1].done && hits[1].timeText != null) return hits[1].timeText
                            from = idx + key.length
                            continue
                        }
                        if (hits.size == 1) {
                            from = idx + key.length
                            continue
                        }
                    }
                }

                var end = minOf(blob.length, idx + window)
                val otherIdx = blob.indexOf(otherKey, idx + key.length)
                if (otherIdx in 0 until end) end = otherIdx
                val slice = blob.substring(idx, end)

                // 两栏表头第 1 栏
                if (otherIdx >= idx + key.length &&
                    otherIdx - idx <= 40 &&
                    findOrderedStatusHits(slice).isEmpty()
                ) {
                    val tailEnd = minOf(blob.length, otherIdx + otherKey.length + 120)
                    val hits = findOrderedStatusHits(blob.substring(otherIdx + otherKey.length, tailEnd))
                    if (hits.size >= 2) {
                        if (hits[0].done && hits[0].timeText != null) return hits[0].timeText
                        from = otherIdx + otherKey.length
                        continue
                    }
                }

                if (DingTalkUi.NOT_DONE_SIGNALS.any { slice.contains(it) }) {
                    from = idx + key.length
                    continue
                }

                val cleaned = slice.replace("更新打卡", "").replace("打卡记录", "")
                val hits = findOrderedStatusHits(cleaned)
                hits.firstOrNull { it.done && it.timeText != null }?.timeText?.let { return it }

                from = idx + key.length
            }
        }

        private fun findOrderedStatusHits(text: String): List<StatusTokenHit> {
            val cleaned = text.replace("更新打卡", "").replace("打卡记录", "")
            val hits = ArrayList<StatusTokenHit>()
            var pos = 0
            while (pos < cleaned.length) {
                var bestIdx = Int.MAX_VALUE
                var bestDone: Boolean? = null
                var bestLen = 0
                for (m in DingTalkUi.NOT_DONE_SIGNALS) {
                    val i = cleaned.indexOf(m, pos)
                    if (i >= 0 && (i < bestIdx || (i == bestIdx && m.length > bestLen))) {
                        bestIdx = i
                        bestDone = false
                        bestLen = m.length
                    }
                }
                for (m in DONE_MARKERS) {
                    val i = cleaned.indexOf(m, pos)
                    if (i >= 0 && (i < bestIdx || (i == bestIdx && m.length > bestLen))) {
                        bestIdx = i
                        bestDone = true
                        bestLen = m.length
                    }
                }
                if (bestDone == null) break
                val timeText = if (bestDone) {
                    val prefixWindow = cleaned.substring(maxOf(pos, bestIdx - 16), bestIdx)
                    val beforeMatch = TIME_AT_END_REGEX.find(prefixWindow)
                    if (beforeMatch != null) {
                        formatMatchedTime(
                            beforeMatch.groupValues[1],
                            beforeMatch.groupValues[2],
                            beforeMatch.groupValues[3],
                        )
                    } else {
                        val suffixWindow = cleaned.substring(
                            bestIdx + bestLen,
                            minOf(cleaned.length, bestIdx + bestLen + 16),
                        )
                        TIME_AT_START_REGEX.find(suffixWindow)?.let { afterMatch ->
                            formatMatchedTime(
                                afterMatch.groupValues[1],
                                afterMatch.groupValues[2],
                                afterMatch.groupValues[3],
                            )
                        }
                    }
                } else {
                    null
                }
                hits.add(StatusTokenHit(done = bestDone, timeText = timeText))
                pos = bestIdx + bestLen
            }
            return hits
        }

        private fun matchesShiftFilter(text: String, kind: PunchKind): Boolean {
            if (kind == PunchKind.MANUAL) return true
            val hasIn = text.contains("上班")
            val hasOut = text.contains("下班")
            if (!hasIn && !hasOut) return true
            return when (kind) {
                PunchKind.CLOCK_IN -> hasIn
                PunchKind.CLOCK_OUT -> hasOut
                PunchKind.MANUAL -> true
            }
        }

        private fun formatMatchedTime(hStr: String, mStr: String, sStr: String): String {
            val h = hStr.toIntOrNull()?.coerceIn(0, 23) ?: 0
            val m = mStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
            return if (sStr.isNotEmpty()) {
                val s = sStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
                "%02d:%02d:%02d".format(h, m, s)
            } else {
                "%02d:%02d".format(h, m)
            }
        }

        internal fun resolveTimeOnDay(timeText: String, referenceMillis: Long): ExtractedPunchTime? {
            val parts = timeText.trim().split(':')
            if (parts.size < 2 || parts.size > 3) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val s = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
            if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = referenceMillis
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, m)
                set(java.util.Calendar.SECOND, s)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val display = if (parts.size == 3) {
                "%02d:%02d:%02d".format(h, m, s)
            } else {
                "%02d:%02d".format(h, m)
            }
            return ExtractedPunchTime(millis = cal.timeInMillis, displayText = display)
        }

        /** 按文本先后顺序抽取状态词序列：true=已打卡，false=未打卡 */
        private fun findOrderedStatusTokens(text: String): List<Boolean> =
            findOrderedStatusHits(text).map { it.done }

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

            // ---- 2.5 「已经打过卡了」弹窗确认（例如重复打卡/极速打卡后弹窗遮住状态卡）----
            DingTalkUi.containsAlreadyPunchedDialog(blob)?.let { signal ->
                if (alreadyPunchedDialogMatchesKind(signal, blob, kind, requireExplicitShift = false)) {
                    return "钉钉提示已打过卡（" + signal + "）"
                }
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
         * 判断「已经打过卡了」弹窗文案是否归属当前目标班次。
         *
         * @param requireExplicitShift true 用于点击前基线校验（通用弹窗不跳过班次，防止误跳过下班）；
         *        false 用于点击后结果确认（明确属于另一班时仍拒绝）。
         */
        internal fun alreadyPunchedDialogMatchesKind(
            signal: String,
            blob: String,
            kind: PunchKind,
            requireExplicitShift: Boolean,
        ): Boolean {
            if (kind == PunchKind.MANUAL) return !requireExplicitShift

            val signalHasIn = signal.contains("上班")
            val signalHasOut = signal.contains("下班")
            if (signalHasIn || signalHasOut) {
                return when (kind) {
                    PunchKind.CLOCK_IN -> signalHasIn && !signalHasOut
                    PunchKind.CLOCK_OUT -> signalHasOut && !signalHasIn
                    PunchKind.MANUAL -> !requireExplicitShift
                }
            }

            val compact = blob.replace(',', '，').replace(Regex("\\s+"), "")
            val blobHasIn = compact.contains("上班时间以最早打卡时间为准")
            val blobHasOut = compact.contains("下班时间以最晚打卡时间为准")
            if (blobHasIn && !blobHasOut) return kind == PunchKind.CLOCK_IN
            if (blobHasOut && !blobHasIn) return kind == PunchKind.CLOCK_OUT

            return !requireExplicitShift
        }

        /** 判断当前页面文本是否属于考勤打卡页（含弹窗遮挡态，纯函数可单测） */
        internal fun isAttendancePage(text: String): Boolean {
            if (text.isEmpty()) return false

            // 推送格式是 "考勤打卡: 11-11 考勤全部正常"，特征是「考勤打卡」后紧跟冒号。
            if (text.contains("考勤打卡:") || text.contains("考勤打卡：")) {
                val strong = text.contains("上班打卡") || text.contains("下班打卡") ||
                    text.contains("未进入考勤范围") || text.contains("可外勤打卡") ||
                    DingTalkUi.containsAlreadyPunchedDialog(text) != null
                if (!strong) return false
            }

            if (DingTalkUi.containsAlreadyPunchedDialog(text) != null) return true
            if (text.contains("上班打卡") || text.contains("下班打卡")) return true

            val hasIn = text.contains("上班")
            val hasOut = text.contains("下班")
            val hasDone = text.contains("已打卡")
            if (hasIn && hasOut && hasDone) return true
            if ((hasIn || hasOut) && text.contains("更新打卡")) return true

            if (text.contains("未进入考勤范围") || text.contains("可外勤打卡") ||
                text.contains("如需内勤打卡")
            ) {
                return true
            }

            if ((hasIn || hasOut) && (text.contains("打卡记录") || text.contains("补卡"))) return true

            return false
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
         * 上滑后等锁屏 PIN 面板挂载的最长时间。
         *
         * 原来这里是固定 delay(800)，实测不够：面板没挂起来时注入的
         * 按键会被系统丢弃，导致黑屏解锁 100% 失败。改成轮询后给足余量。
         */
        private const val PIN_READY_TIMEOUT_MS = 8_500L

        /**
         * PIN 面板就绪状态的轮询间隔。
         *
         * 一次 dumpsys window windows 约 224ms（真机实测），
         * 所以间隔不能比它小太多，否则只是在排 su 进程的队。
         */
        private const val PIN_READY_POLL_MS = 250L

        /**
         * 每次上滑后连查几次再决定要不要再滑。
         *
         * 给一次滑动约 1 秒的生效窗口（4 × 250ms），
         * 避免滑动还没生效就急着重滑，把已生效的那一次打断。
         */
        private const val PIN_READY_POLL_TIMES = 4

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
         * 外勤等待期间唤醒锁的单次续期时长。
         *
         * 服务侧首次 acquire 用的是 WAKELOCK_TIMEOUT_MS（20 分钟），
         * 但本循环最长可跑 15 分钟且可能更长（页面重导航），
         * 所以每轮都检查并在已释放时续上。取 10 分钟：
         * 远大于一个轮询周期（5 秒），又不会在异常路径上长期占锁。
         */
        private const val WAKELOCK_RENEW_MS = 10 * 60 * 1000L

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

        /**
         * 进入上班时间后多久算迟到（1 分钟）。
         *
         * 【审计 B2】改为**引用** BtPunchRule 里的定义，而不是各写一份。
         *
         * 修复前这里和 BtPunchRule 各有一份数值相同的常量，
         * 两处注释都自称"唯一实现"，实际是两份互不调用的代码。
         * 更危险的是：PunchEngine 这一份在 130 个单元测试里**引用数为 0**
         * —— 也就是说红线数值改坏了，测试一个都不会失败。
         *
         * 现在这里只是转发，真正的数值只有 BtPunchRule 一处。
         */
        private const val LATE_GRACE_MS = com.dingdinggo.app.core.BtPunchRule.LATE_GRACE_MS

        /**
         * 安全余量：在迟到边界之前提前这么多就停手。
         *
         * 5 秒的依据：从检查通过 → 找到按钮 → performAction → 钉钉服务端记账，
         * 实测在 K90 上约 300~800ms。留 5 秒是 6~15 倍冗余，
         * 既够安全，又不会让可打窗口窄到不可用（实际窗口仍有 55 秒）。
         */
        private const val LATE_SAFETY_MARGIN_MS =
            com.dingdinggo.app.core.BtPunchRule.LATE_SAFETY_MARGIN_MS

        /**
         * 下班卡的提前量。
         *
         * 用户要求「下班之前绝不打下班卡」，这里同样留 2 秒余量：
         * 早退比缺卡严重，宁可晚 2 秒也不能早。
         */
        private const val CLOCK_OUT_SAFETY_MARGIN_MS =
            com.dingdinggo.app.core.BtPunchRule.CLOCK_OUT_SAFETY_MARGIN_MS

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

    /**
     * 本次执行持有的唤醒锁（由外部注入，可能为 null）。
     *
     * 之所以存成字段而不是层层传参：长等待循环（外勤等待最多 15 分钟）
     * 需要在中途续期，而它位于 [waitUntilInRange] 这样的深层私有方法里，
     * 逐层加参数会污染一长串签名。唤醒锁本身是"本次执行"级别的资源，
     * 放在实例字段上语义也更准确。
     *
     * 用 @Volatile：由调用方（服务协程）写入、在引擎执行线程读取。
     */
    @Volatile
    private var currentWakeLock: PowerManager.WakeLock? = null

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
        /**
         * 上班卡可打窗口的**额外提前量**（毫秒）。
         *
         * ## 为什么需要它
         *
         * 闹钟链路的窗口下界是 target-75s，那 75 秒是给"点亮屏幕→解锁→
         * 拉钉钉→进考勤页"留的准备时间，属于技术性约束。
         *
         * 但**蓝牙触发**的语义完全不同：检测到考勤机蓝牙 ＝ 人已经站在
         * 机器旁边了。此时 08:47 到公司是正常且想要的记录，不应该被
         * 75 秒的窗口挡掉。用户明确要求「检测到就当作今日实际上班时间」，
         * 早到必须放行。
         *
         * 所以蓝牙来源会传一个较大的值（默认 2 小时，可配置）。
         *
         * ## 红线不变
         *
         * 只放宽**下界**（早到窗口），上界（迟到停手点）与下班最早时刻
         * 一个字都不动 —— 早到可以，迟到卡绝不打；早退卡绝不打。
         *
         * 非蓝牙来源传 0，行为与原来**完全一致**。
         */
        earlyWindowMs: Long = 0L,
        /**
         * 是否经用户显式确认放行迟到红线（仅对 [PunchKind.CLOCK_IN] 生效）。
         */
        allowLate: Boolean = false,
    ): PunchResult {
        trace.clear()

        // 登记唤醒锁，供外勤等待等长循环续期（见 currentWakeLock 注释）。
        currentWakeLock = wakeLock

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

            // ---- 2. 点亮屏幕 & 3. 解锁（持有临时亮屏锁防止锁屏 10 秒自动息屏） ----
            val unlockScreenLock = acquireScreenWakeLockForUnlock()
            try {
                if (!prepareScreen()) {
                    return fail(PunchStage.WAKE, "无法点亮屏幕")
                }

                if (!unlock()) {
                    return fail(PunchStage.UNLOCK, "解锁失败，请检查锁屏密码设置")
                }
            } finally {
                runCatching {
                    if (unlockScreenLock != null && unlockScreenLock.isHeld) {
                        unlockScreenLock.release()
                    }
                }
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
                // 下班卡安全闸门要求不早于 clockOutEarliestAt(targetAt)（即 targetAt + 2 秒）；
                // 若只等到 targetAt 就放行，紧接着就会被 checkSafetyGate 误拒 1 秒（#31 真机复现）。
                val releaseAt = if (kind == PunchKind.CLOCK_OUT) {
                    clockOutEarliestAt(targetAt) + 200L
                } else {
                    targetAt
                }
                val waitMs = releaseAt - System.currentTimeMillis()
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

                // 预热等待可能持续数十秒：若期间屏幕自动熄灭或重新上锁，先恢复亮屏与解锁
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive || !isKeyguardUnlocked()) {
                    log("预热结束前检测到息屏/上锁，重新点亮并解锁")
                    val rewakeLock = acquireScreenWakeLockForUnlock()
                    try {
                        if (!prepareScreen()) return fail(PunchStage.WAKE, "预热后无法重新点亮屏幕")
                        if (!unlock()) return fail(PunchStage.UNLOCK, "预热后重新解锁失败")
                    } finally {
                        runCatching {
                            if (rewakeLock != null && rewakeLock.isHeld) rewakeLock.release()
                        }
                    }
                    if (!launchDingTalk()) {
                        return fail(PunchStage.LAUNCH, "预热恢复后钉钉切回失败")
                    }
                }
            }

            // ---- 5.5 安全闸门（绝不能跳过） ----
            val guard = checkSafetyGate(kind, targetAt, earlyWindowMs, allowLate)
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
                val actual = extractActualTimeWithPopupCleanup(kind)
                val evidence = "极速打卡（无需点击）：进入考勤页时目标时段已是已打卡状态"
                log("判定成功，依据: " + evidence + (actual?.let { "（真实时间=" + it.displayText + "）" } ?: ""))
                return PunchResult(
                    success = true,
                    stage = PunchStage.FINISH,
                    message = "该时段已完成打卡（钉钉极速打卡）",
                    evidence = evidence,
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                    actualAt = actual?.millis ?: 0L,
                    actualTimeText = actual?.displayText,
                )
            }

            // ---- 6. 点击打卡（保留进入考勤页期间触发的极速打卡横幅，不再在点击前清空） ----
            val clicked = clickPunchButton(kind, pref, alreadyDoneBefore)

            if (!clicked) {
                // 点不到按钮时轮询检测：覆盖基线后极速打卡抢先完成、两条顶部横幅覆写消退或 H5 状态延迟刷新
                var fastEvidence: String? = null
                for (pollIdx in 0..2) {
                    fastEvidence = checkQuickPunchOrDone(kind, alreadyDoneBefore)
                    if (fastEvidence != null) break
                    if (pollIdx < 2) delay(700)
                }
                if (fastEvidence != null) {
                    ControlHudOverlay.hide()
                    val actual = extractActualTimeWithPopupCleanup(kind)
                    val evidence = "点击阶段检测到打卡已完成（" + fastEvidence + "）"
                    log("判定成功，依据: " + evidence + (actual?.let { "（真实时间=" + it.displayText + "）" } ?: ""))
                    return PunchResult(
                        success = true,
                        stage = PunchStage.FINISH,
                        message = "打卡成功",
                        evidence = evidence,
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                        actualAt = actual?.millis ?: 0L,
                        actualTimeText = actual?.displayText,
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
            val actual = extractActualTimeWithPopupCleanup(kind)
            log("判定成功，依据: " + verdict + (actual?.let { "（真实时间=" + it.displayText + "）" } ?: ""))
            return PunchResult(
                success = true,
                stage = PunchStage.FINISH,
                message = "打卡成功",
                evidence = verdict,
                trace = trace.toList(),
                channelUsed = channelUsed,
                actualAt = actual?.millis ?: 0L,
                actualTimeText = actual?.displayText,
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
            // 协程取消是控制流，不是业务失败。暂停态下保留悬浮条让用户点「▶」或「X」。
            if (!ControlHudOverlay.isPausedState()) {
                ControlHudOverlay.hide()
            }
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

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLockForUnlock(): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "autoattend:unlock_screen",
        ).apply {
            setReferenceCounted(false)
            acquire(35_000L)
        }
    }.getOrNull()

    /** 提取真实打卡时间，并在存在「已经打过卡了」弹窗时顺手关闭，便于读取底层状态卡并保持界面干净 */
    private suspend fun extractActualTimeWithPopupCleanup(kind: PunchKind): ExtractedPunchTime? {
        var actual = extractLiveActualTime(kind)
        if (DingTalkUi.containsAlreadyPunchedDialog(readCurrentPageText()) != null) {
            runCatching { PunchAccessibilityService.get()?.let { dismissPopups(it) } }
            if (actual == null) {
                actual = extractLiveActualTime(kind)
            }
        }
        return actual
    }

    /** 从当前考勤页及最近横幅事件缓存里提取本次打卡的真实时间戳 */
    private fun extractLiveActualTime(kind: PunchKind): ExtractedPunchTime? {
        val service = PunchAccessibilityService.get()
        val pageLines = service?.readTextLines().orEmpty()
        val recentLines = service?.recentTextLines().orEmpty()
        val blob = buildString {
            if (recentLines.isNotEmpty()) append(recentLines.joinToString("\n")).append('\n')
            append(readCurrentPageText())
        }
        return extractActualPunchTime(
            blob = blob,
            lines = pageLines + recentLines,
            kind = kind,
            referenceMillis = System.currentTimeMillis(),
        )
    }

    /** 点亮屏幕：优先临时亮屏锁 + root KEYCODE_WAKEUP，失败时重试与 Activity 兜底 */
    private suspend fun prepareScreen(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val initiallyInteractive = pm.isInteractive
        if (initiallyInteractive) {
            log("屏幕唤醒：初始已点亮（pm.isInteractive=true）")
            return true
        }
        if (waitUntil(600L) { pm.isInteractive }) {
            log("屏幕唤醒：WakeLock 点亮成功")
            return true
        }

        // 方案 A：root 发 KEYCODE_WAKEUP（224），若息屏过渡期首击被吞则补发第 2 次
        if (RootShell.isAvailable()) {
            repeat(2) { idx ->
                log("root 点亮屏幕 input keyevent 224（第 " + (idx + 1) + " 次）")
                channelUsed = "root"
                RootShell.wakeUp()
                if (waitUntil(2_000L) { pm.isInteractive }) {
                    log("屏幕唤醒：root keyevent 224 成功")
                    return true
                }
            }
        } else {
            log("root 不可用，跳过 input keyevent 224")
        }

        // 方案 B：若前面仍未唤醒，尝试拉起主界面触发 turnScreenOn（优先走 root am start 绕开 BAL）
        log("尝试通过 Activity 唤醒屏幕")
        val startedByRoot = if (RootShell.isAvailable()) {
            RootShell.startActivity(context.packageName, "com.dingdinggo.app.MainActivity")
        } else {
            false
        }
        if (!startedByRoot) {
            runCatching {
                val intent = android.content.Intent(
                    context,
                    com.dingdinggo.app.MainActivity::class.java,
                ).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra("wake_only", true)
                }
                context.startActivity(intent)
            }
        }

        if (waitUntil(4_000L) { pm.isInteractive }) return true

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

        // 从 Dozing 刚亮屏时 Keyguard 触摸窗口约需 400-500ms 就绪，避免首轮上滑被吞
        delay(450)

        var password: String? = AppConfig.readLockPassword()
        val hasPassword = !password.isNullOrEmpty()

        // 【P0 修复】上滑 + 等 PIN 面板就绪，失败就再滑一次。
        //
        // 原来这里是「swipeUpOnce() + delay(800)」，实测在黑屏+锁屏下有
        // 两个独立的失败窗口（都已在真机复现）：
        //
        //   1. 唤醒后立刻上滑（实测只隔 44ms）→ 锁屏根本还没起来，
        //      滑动被丢掉，PIN 面板永远不会出现；
        //   2. 上滑后固定等 800ms → 面板还没挂载，注入的按键进虚空。
        //
        // 所以不能靠固定时长，必须「滑 → 验证 → 没就绪就再滑」。
        // 详见本函数头部的完整真机记录。
        val pinPadReady = swipeUntilPinPadReady(pm)

        if (!hasPassword) {
            log("无密码模式，仅上滑")
            return waitUntil(5_000L) { isKeyguardUnlocked() }
        }

        if (!pinPadReady) {
            log("PIN 面板未在超时内出现，仍尝试输入（可能已自动解锁或面板结构变化）")
        }

        // 有密码：输入数字。
        //
        // 安全约束：密码只在这个函数栈上存活，用完立即置 null（String 无法真正擦除，
        // 但清掉引用能显著缩短它在堆上的可寻址时间）。
        // 绝不写日志、绝不进 trace、绝不进 Room。
        try {
            repeat(MAX_RETRY) { attempt ->
                // 若期间屏幕熄灭（锁屏 10s userActivityTimeout 超时）先重唤醒
                if (!pm.isInteractive) {
                    log("解锁前屏幕已息屏，重新唤醒")
                    if (RootShell.isAvailable()) RootShell.wakeUp()
                    waitUntil(1_500L) { pm.isInteractive }
                    delay(450)
                }
                if (attempt > 0 && isKeyguardLocked() && !isPinPadReady()) {
                    var reopened = false
                    if (RootShell.isAvailable()) {
                        RootShell.dismissKeyguard()
                        delay(350)
                        reopened = isKeyguardUnlocked() || isPinPadReady()
                    }
                    if (!reopened) {
                        swipeUpOnce(attempt + 2)
                        repeat(PIN_READY_POLL_TIMES) {
                            delay(PIN_READY_POLL_MS)
                            if (isKeyguardUnlocked() || isPinPadReady()) return@repeat
                        }
                    }
                }

                // 仅在失败重试时清空残留数字：首次弹起面板为空，省去 ~5 秒的 keyevent 67 删除耗时
                if (attempt > 0) {
                    clearPinField(password.length)
                }

                // 注意：日志里只说"第 N 次"，绝不带密码值
                log("输入锁屏密码，第 " + (attempt + 1) + " 次")
                if (inputPassword(password)) {
                    waitUntil(5_000L) { isKeyguardUnlocked() || !pm.isInteractive }
                    if (isKeyguardUnlocked()) return true
                }

                // 已经解锁就别再重试（少数 ROM 会自动确认）
                if (isKeyguardUnlocked()) return true
                delay(350)
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

    /**
     * 锁屏 PIN 面板是否已经挂载完成。
     *
     * ## 判据（真机实测得出，不是猜的）
     *
     * HyperOS 3 的锁屏 PIN 面板**不是独立窗口**，而是挂在 SystemUI 的
     * NotificationShade 窗口里，所以拿不到专门的窗口名，只能看窗口属性：
     *
     * | 状态                   | ty=NOTIFICATION_SHADE 的 flags                        |
     * |------------------------|-------------------------------------------------------|
     * | 壁纸页（未上滑/上滑中）  | `... INPUT_FEATURE_DISABLE_USER_ACTIVITY`             |
     * | **PIN 面板已挂起**      | `... INPUT_FEATURE_DISABLE_USER_ACTIVITY | 8`         |
     *
     * 那个 `| 8` 就是 FLAG_SECURE：面板一旦要求安全输入就会带上它。
     * 实测三次取样稳定区分两个状态（`ty=NOTIFICATION_SHADE` 只匹配到一行）。
     *
     * ## 为什么不用无障碍
     *
     * accessibility_service_config.xml 的 packageNames 只监听钉钉
     * （隐私 + 功耗考虑），拿不到 SystemUI 的节点树。
     * 这个约束是有意为之，不该为了解锁去放宽它。
     *
     * ## 开销
     *
     * 一次 `dumpsys window windows` 约 224ms（真机实测），
     * 通常 1~2 次命中；最坏 PIN_READY_TIMEOUT_MS / PIN_READY_POLL_MS 次。
     * 只在解锁这一步发生，不影响其它阶段。
     */
    private fun isPinPadReady(): Boolean {
        val out = RootShell.exec("dumpsys window windows", timeoutMs = 5_000)
        if (out.isBlank()) return false
        return out.lineSequence()
            .filter { it.contains("ty=NOTIFICATION_SHADE") }
            .any { line ->
                val flagsPart = line.substringAfter("if=", "")
                Regex("\\|\\s*8\\b").containsMatchIn(flagsPart)
            }
    }

    /**
     * 反复上滑，直到锁屏 PIN 面板真的挂起来。
     *
     * ## 为什么不能「滑一次 + 等固定时间」
     *
     * 真机实测两个独立失败窗口（都是稳定复现的）：
     *
     * 1. **唤醒后立刻上滑**（PunchEngine id=26 的 trace 显示 wake→swipe 只隔 44ms）：
     *    锁屏界面本身还没起来，这一滑被系统丢掉，之后无论等多久
     *    PIN 面板都不会出现 —— 这正是 id=26 等满 5 秒仍失败的原因。
     * 2. **上滑后等 800ms**（原实现）：锁屏起来了，但面板还没挂载，
     *    注入的按键没有接收者，直接进虚空（id=24 三次重试全废）。
     *
     * 所以唯一可靠的做法是闭环：滑一次 → 检测 → 没就绪就再滑。
     *
     * @return 面板是否成功挂起；false 表示超时（调用方决定是否仍然尝试）
     */
    private suspend fun swipeUntilPinPadReady(pm: PowerManager): Boolean {
        if (isKeyguardUnlocked()) return true
        if (isPinPadReady()) {
            log("PIN 面板已处于就绪态")
            return true
        }

        // 优先使用 wm dismiss-keyguard 直接唤起 PIN 面板（免触摸手势，不受锁屏通知或防误触吞滑动影响）
        if (RootShell.isAvailable()) {
            RootShell.dismissKeyguard()
            delay(350)
            if (isKeyguardUnlocked()) return true
            if (isPinPadReady()) {
                log("PIN 面板就绪（wm dismiss-keyguard）")
                return true
            }
        }

        val deadline = System.currentTimeMillis() + PIN_READY_TIMEOUT_MS
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            if (!pm.isInteractive) {
                log("上滑前屏幕已息屏，重新唤醒")
                if (RootShell.isAvailable()) RootShell.wakeUp()
                waitUntil(1_200L) { pm.isInteractive }
                delay(400)
            }
            round++
            swipeUpOnce(round)

            // 给这一次滑动一点时间生效，然后连查几次。
            // 检测本身约 224ms（dumpsys 开销），所以间隔离不开这个量级。
            repeat(PIN_READY_POLL_TIMES) {
                delay(PIN_READY_POLL_MS)
                if (isKeyguardUnlocked()) return true // 无密码/已被别的方式解开
                if (isPinPadReady()) {
                    log("PIN 面板就绪（第 " + round + " 次上滑后）")
                    return true
                }
            }

            if (round % 2 == 0 && RootShell.isAvailable()) {
                RootShell.dismissKeyguard()
            }
        }
        return false
    }

    /**
     * 清空锁屏密码输入框（仅用于失败重试，首次弹起无需调用）。
     * 仅发送与密码等长 + 2 个 KEYCODE_DEL（67），避免固定 12 次消耗 5 秒以上。
     */
    private suspend fun clearPinField(digitCount: Int = 6) {
        if (!RootShell.isAvailable()) return
        val count = (digitCount + 2).coerceIn(4, 12)
        val keys = List(count) { "67" }.joinToString(" ")
        RootShell.execQuiet("input keyevent " + keys, timeoutMs = 8_000)
        delay(120)
    }

    /** 上滑一次（多轮重试时交替尝试 root input swipe，提升锁屏拉起 PIN 面板稳定性） */
    private suspend fun swipeUpOnce(round: Int = 1) {
        if (round >= 2 && round % 2 == 0 && RootShell.isAvailable()) {
            channelUsed = "root"
            RootShell.swipeUp()
            log("root 上滑（第 " + round + " 轮兜底）")
            return
        }
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
        // 工作台本身列有“日志/审批/请假/汇报工作”等应用图标，不可用 WRONG_PAGE_MARKERS 误拒工作台
        if (tapAndVerify(service, DingTalkUi.TAB_WORK, "工作台 tab", checkWrongPage = false)) {
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
        checkWrongPage: Boolean = true,
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
        if (checkWrongPage && DingTalkUi.looksLikeWrongPage(after) && !looksLikeAttendancePage(after)) {
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
        val lines = runCatching { PunchAccessibilityService.get()?.readTextLines() }.getOrNull().orEmpty()

        // 【联调诊断】确认无障碍到底能不能读到考勤页文本。
        //
        // 真机结论（investigator 实测）：打卡页是 UCWebView/H5，
        // 且中间有每秒跳动的时钟 → uiautomator dump 永远 could not get idle state。
        // 所以判定**只能**走无障碍实时节点树（主动 rootInActiveWindow + windows 遍历）。
        runCatching {
            Log.i(
                TAG,
                "诊断 a11y: kind=" + kind.name +
                    " readAllText.len=" + text.length +
                    " readTextLines.size=" + lines.size +
                    " doneMarkers=" + DONE_MARKERS.count { text.contains(it) },
            )
        }

        if (lines.any { targetAlreadyDone(it, kind) }) return true
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
        val ok = isAttendancePage(text)
        if (!ok && (text.contains("考勤打卡:") || text.contains("考勤打卡："))) {
            log("疑似消息列表推送（含考勤打卡:），不判定为考勤页")
        }
        return ok
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
            var currentXml = xml
            val workNode = UiHierarchy.findNode(currentXml, DingTalkUi.TAB_WORK)
            if (workNode != null) {
                RootShell.tap(workNode.centerX, workNode.centerY)
                log("root 点击工作 tab @ " + workNode.centerX + "," + workNode.centerY)
                delay(2200)
                currentXml = RootShell.dumpUiHierarchy() ?: currentXml
            }

            // 找考勤入口并校验是否真的进入考勤页
            val entry = UiHierarchy.findNode(currentXml, DingTalkUi.ENTRY_ATTENDANCE)
            if (entry != null) {
                RootShell.tap(entry.centerX, entry.centerY)
                log("root 点击考勤入口 @ " + entry.centerX + "," + entry.centerY)
                delay(3000)
                val after = RootShell.dumpUiHierarchy()
                if (after != null && looksLikeAttendancePage(UiHierarchy.allText(after))) {
                    log("root 已进入考勤页")
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
    private suspend fun clickPunchButton(
        kind: PunchKind,
        pref: ChannelPreference,
        alreadyDoneBefore: Boolean,
    ): Boolean {
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
            if (checkQuickPunchOrDone(kind, alreadyDoneBefore) != null) {
                log("点击前检测到页面已出现成功或完成状态，停止找按钮")
                return false
            }

            if (pref != ChannelPreference.ROOT_ONLY) {
                val service = PunchAccessibilityService.get()
                if (service != null) {
                    // 第 1/3 次优先手势坐标点击（绕过 H5/UCWebView 吞 ACTION_CLICK）；
                    // 第 2 次改走 ACTION_CLICK 优先（clickNode 失败时仍会双路互备）。
                    val preferGesture = attempt != 1
                    if (service.clickByText(candidates, preferGesture = preferGesture)) {
                        log(
                            "无障碍点击打卡按钮（第 " + (attempt + 1) + " 次，" +
                                service.lastClickDiagnostic + "）",
                        )
                        delay(1500)
                        return true
                    }
                    log(
                        "无障碍第 " + (attempt + 1) + " 次未击中打卡按钮（" +
                            service.lastClickDiagnostic + "）",
                    )
                    // 没点到按钮时立即复检是否因极速打卡刚完成并覆盖了按钮，避免后续 dump 卡住导致横幅消逝
                    if (checkQuickPunchOrDone(kind, alreadyDoneBefore) != null) {
                        log("无障碍未找到按钮，但检测到极速打卡已完成")
                        return false
                    }
                    // 若未命中当前班次，但存在「已经打过卡了」弹窗遮挡（例如残留的早班弹窗挡住下班按钮），先关闭弹窗再重试
                    if (DingTalkUi.containsAlreadyPunchedDialog(service.readAllText()) != null) {
                        dismissPopups(service)
                    }
                }
            }

            // 注意：在钉钉 UCWebView（动态时钟每秒刷新）下执行 uiautomator dump 不仅会卡 3 秒超时，
            // 还会通过 UiTestAutomationService 强制解绑并重连 PunchAccessibilityService。
            // 因此只要无障碍在线且能读到当前界面文本，就不再在重试循环里跑 uiautomator dump；
            // 仅在 ROOT_ONLY、无障碍离线、或无障碍读不到任何文本时才走 root dump 兜底。
            val currentService = PunchAccessibilityService.get()
            val hasLiveAccessibilityUi = currentService != null &&
                currentService.readAllText().isNotBlank()
            val needRootFallback = pref == ChannelPreference.ROOT_ONLY || !hasLiveAccessibilityUi
            if (needRootFallback && pref != ChannelPreference.ACCESSIBILITY_ONLY && RootShell.isAvailable()) {
                // 限制 dump 超时为 3 秒，避免长时间阻塞让极速打卡顶部横幅消失
                val xml = RootShell.dumpUiHierarchy(timeoutMs = 3_000L)
                if (xml != null) {
                    val node = UiHierarchy.findNode(xml, candidates)
                    if (node != null) {
                        channelUsed = "root"
                        RootShell.tap(node.centerX, node.centerY)
                        log("root 点击打卡按钮 @ " + node.centerX + "," + node.centerY)
                        delay(1500)
                        return true
                    }
                }
            }
            delay(800)
        }
        val sampleLines = PunchAccessibilityService.get()
            ?.readTextLines()
            ?.take(12)
            ?.joinToString(" | ")
            .orEmpty()
        if (sampleLines.isNotBlank()) {
            log("点击打卡按钮失败时页面文本摘要：" + sampleLines.take(240))
        }
        return false
    }

    /** 检查当前页面/事件缓存是否已出现成功信号或目标时段已打卡（用于未点到按钮时的极速打卡/弹窗兜底） */
    private fun checkQuickPunchOrDone(kind: PunchKind, alreadyDoneBefore: Boolean): String? {
        val service = PunchAccessibilityService.get()
        val blob = buildString {
            service?.recentTextBlob()?.let { append(it) }
            append(' ')
            append(readCurrentPageText())
            append(' ')
            service?.readTextLines()?.forEach { append(it).append('\n') }
        }
        val doneNow = readTargetDoneState(kind)
        return judgeResult(blob, alreadyDoneBefore, doneNow, kind)
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
    private suspend fun checkSafetyGate(
        kind: PunchKind,
        scheduledTargetAt: Long,
        earlyWindowMs: Long = 0L,
        allowLate: Boolean = false,
    ): PunchResult? {
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

        // 蓝牙来源会放宽上班卡的下界（早到窗口），其余分支完全不变。
        // 【窗口口径必须与 BtPunchRule 完全一致】
        //
        // 第一版写成 `clockInEarliestAt(targetAt) - extendEarly`，
        // 也就是 target - 75s - earlyWindow —— 比规则多放了 75 秒。
        // 那 75 秒是"预热准备时间"，与"允许多早到"是两个概念：
        // 两边口径不一致会让"接收器判定太早、引擎却放行"这种矛盾出现。
        //
        // 现在：蓝牙来源（earlyWindowMs > 0）的下界**就是**
        // `target - earlyWindow`，与 BtPunchRule.clockInEarliestAt 同一算式；
        // 非蓝牙来源（earlyWindowMs == 0）完全保持原来的 target-75s 语义。
        val extendEarly = if (kind == PunchKind.CLOCK_IN) earlyWindowMs else 0L
        val adjustEarly = { v: TimeWindowVerdict? ->
            if (v is TimeWindowVerdict.TooEarlyForClockIn && extendEarly > 0L) {
                val btEarliest = targetAt - extendEarly
                if (now >= btEarliest) null else v
            } else {
                v
            }
        }

        // 走唯一实现判定（见 companion 里的 timeWindowVerdict）。
        // 这里的职责只剩「把判定结果翻译成 PunchResult」。
        when (val verdict = adjustEarly(timeWindowVerdict(kind, now, targetAt))) {
            null -> log("时间窗校验通过（kind=" + kind.name + "）")

            is TimeWindowVerdict.Early -> {
                val remainText = PunchSchedule.formatRemainSeconds(verdict.remainSec)
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
                val aheadText = PunchSchedule.formatRemainSeconds(verdict.aheadSec)
                log("拒绝：离上班时间还差 " + aheadText + "（预热窗口之外），不提前打卡")
                return PunchResult(
                    success = false,
                    stage = PunchStage.CLICK,
                    message = "距上班时间还有 " + aheadText + "，已阻止提前打上班卡",
                    trace = trace.toList(),
                    channelUsed = channelUsed,
                    abandonedByGate = true,
                    rejectedTooEarly = true,
                )
            }

            is TimeWindowVerdict.Late -> {
                val lateText = PunchSchedule.formatRemainSeconds(verdict.lateSec)
                if (com.dingdinggo.app.core.LateConfirmRule.isLateAllowedForKind(kind, allowLate)) {
                    log("用户已确认补打迟到卡，放行迟到边界（已过 " + lateText + "）")
                } else {
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
                        rejectedLate = true,
                        lateSeconds = verdict.lateSec,
                    )
                }
            }
        }

        // ---------- 闸门 2：外勤检查（需要读界面，可能要等） ----------
        val outdoorVerdict = waitUntilInRange(kind, targetAt, allowLate)
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
        return recheckTimeWindow(kind, targetAt, earlyWindowMs, allowLate)
    }

    /**
     * 时间窗复查（纯判定，不涉及界面）。
     *
     * 与 [checkSafetyGate] 里的闸门 1 完全同口径 ——
     * 因为它们本来就该是同一套规则，只是执行时刻不同。
     */
    private fun recheckTimeWindow(
        kind: PunchKind,
        targetAt: Long,
        earlyWindowMs: Long = 0L,
        allowLate: Boolean = false,
    ): PunchResult? {
        val now = System.currentTimeMillis()

        // 【唯一实现】直接走 companion 里的边界函数，不再手写算式。
        //
        // 这里曾经内联重写了 `targetAt + CLOCK_OUT_SAFETY_MARGIN_MS` 与
        // `targetAt + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS`，
        // 与 companion 的注释「唯一实现，不许再手写一遍」直接矛盾。
        // 改一处漏一处就会重现「第一次放行、复查拒绝」的漏卡事故。
        when (kind) {
            PunchKind.CLOCK_OUT -> {
                val allowedAt = clockOutEarliestAt(targetAt)
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
                val mustStopAt = clockInMustStopAt(targetAt)
                if (now > mustStopAt) {
                    val lateSec = (now - targetAt) / 1000L
                    if (com.dingdinggo.app.core.LateConfirmRule.isLateAllowedForKind(kind, allowLate)) {
                        log("外勤等待后复查：用户已确认打迟到卡，放行迟到边界（已过 " + lateSec + " 秒）")
                    } else {
                        log("外勤等待后复查：已超出迟到停手点 " + lateSec + " 秒，放弃本次打卡")
                        return PunchResult(
                            success = false,
                            stage = PunchStage.CLICK,
                            message = "外勤等待期间已越过迟到边界（已过 " + lateSec +
                                " 秒），为避免生成迟到记录已跳过。如需打卡请手动操作。",
                            trace = trace.toList(),
                            channelUsed = channelUsed,
                            abandonedByGate = true,
                            rejectedLate = true,
                            lateSeconds = lateSec,
                        )
                    }
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
    private suspend fun waitUntilInRange(
        kind: PunchKind,
        targetAt: Long,
        allowLate: Boolean = false,
    ): PunchResult? {
        val service = PunchAccessibilityService.get()
        val startedAt = System.currentTimeMillis()
        var attempt = 0

        while (true) {
            attempt++
            // 上班卡已超过迟到停手点时不必再等 15 分钟外勤轮询，交由 recheckTimeWindow 立即按红线中止
            if (kind == PunchKind.CLOCK_IN &&
                !com.dingdinggo.app.core.LateConfirmRule.isLateAllowedForKind(kind, allowLate) &&
                targetAt > 0L &&
                System.currentTimeMillis() > clockInMustStopAt(targetAt)
            ) {
                log("外勤检查：已超出上班迟到停手点，停止等待并交由时间窗复查中止")
                return null
            }
            val blob = readCurrentPageText()

            if (blob.isEmpty()) {
                log("外勤检查：读不到页面文本，按可打处理（由后续步骤兜底）")
                return null
            }

            // 已打卡状态：不需要再打
            val alreadySignal = DingTalkUi.containsAlreadyDone(blob)
                ?: DingTalkUi.containsAlreadyPunchedDialog(blob)
            alreadySignal?.let { signal ->
                // 但要排除"今天的上班已打卡，现在该打下班"这种正常情况：
                // 只有当目标按钮本身已经是"已打卡"时才中止。
                if (targetAlreadyDone(blob, kind)) {
                    ControlHudOverlay.hide()
                    val actual = extractActualTimeWithPopupCleanup(kind)
                    log("外勤检查：目标时段已打卡（" + signal + "），无需重复" +
                        (actual?.let { "（真实时间=" + it.displayText + "）" } ?: ""))
                    return PunchResult(
                        success = true,
                        stage = PunchStage.FINISH,
                        message = "今日该时段已打过卡，跳过重复打卡",
                        evidence = "考勤页显示目标时段已完成：" + signal,
                        trace = trace.toList(),
                        channelUsed = channelUsed,
                        actualAt = actual?.millis ?: 0L,
                        actualTimeText = actual?.displayText,
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

            // 【审计 B1】续期唤醒锁。
            //
            // 服务侧 acquire 的唤醒锁有超时上限；而本循环最长可跑
            // OUTDOOR_WAIT_TOTAL_MS(15 分钟)。如果中途锁到期释放、
            // 屏幕又恰好熄灭，设备会进入 suspend —— 下面的 delay(5s)
            // 会被拉长到十几分钟，"最多等 15 分钟"的保护彻底失效，
            // 本班次静默漏卡。
            //
            // 这里是全流程唯一的长等待循环，续期放在这里最自然。
            // 用 try/catch 包住：续期失败不应中断打卡主流程。
            runCatching {
                val wl = currentWakeLock
                if (wl != null && !wl.isHeld) {
                    wl.acquire(WAKELOCK_RENEW_MS)
                    log("外勤等待中：唤醒锁已续期")
                }
            }

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

        // 注：无障碍事件缓存已在点击前清空，这里直接保留点击动作起至今的全部瞬时提示/浮层事件
        var lastBlobLen = 0
        var lastLinesCount = 0
        repeat(5) { attempt ->
            delay(1500)

            val recent = service?.recentTextBlob().orEmpty()
            val fullText = service?.readAllText().orEmpty()
            val lines = service?.readTextLines().orEmpty()
            lastLinesCount = lines.size

            val blob = buildString {
                if (recent.isNotEmpty()) append(recent).append(' ')
                if (fullText.isNotEmpty()) append(fullText).append(' ')
                if (lines.isNotEmpty()) {
                    lines.forEach { append(it).append('\n') }
                }
                if (service == null && RootShell.isAvailable()) {
                    RootShell.dumpUiHierarchy(timeoutMs = 6_000L)?.let {
                        append(UiHierarchy.allText(it))
                    }
                }
            }
            lastBlobLen = blob.length

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
                    "（点击前=" + alreadyDoneBefore + " 现在=" + doneNow +
                    " 文本长度=" + lastBlobLen + " 行数=" + lastLinesCount + "），继续等待"
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
    private suspend fun waitUntil(
        timeoutMs: Long,
        intervalMs: Long = 120L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(intervalMs)
        }
        return condition()
    }
}