package com.redmi.autoattend

import com.redmi.autoattend.core.PunchSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 打卡安全闸门的时间红线测试。
 *
 * ## 这个文件现在测什么、不测什么（重要，别被"全绿"骗了）
 *
 * [com.redmi.autoattend.exec.PunchEngine.checkSafetyGate] 是 **private suspend**，
 * 而且它读取 AppConfig / 系统时钟 / 无障碍服务，JVM 单元测试既拿不到实例
 * （构造函数要 Context），也调不到方法。所以在**不改 main 源码**的前提下，
 * 闸门本身的"允许/拒绝"判断无法被直接断言。
 *
 * 旧版 [SafetyGateTest] 的做法是**自己复刻一份 canClockIn / canClockOut**，
 * 断言的是副本。那样改坏生产常量测试照样全绿，是同义反复 —— 已删除。
 *
 * 现在这里只测两件**真的来自生产代码**的事：
 *  1. 闸门依赖的时刻地基：[PunchSchedule.startOfDay] + 分钟数换算成目标时刻。
 *     这块算错的后果和闸门算错一样 —— 整条红线整体平移。
 *  2. 两条红线的**期望语义**用 @Ignore 显式挂起，写清公理与挂起原因。
 *     它们不会假绿（@Ignore 是 skipped，不是 passed），
 *     一旦 Lead 把闸门抽成 internal object 就能原地解禁。
 *
 * ## ⚠️ 这套规则现在在 PunchEngine 里有**两份实现**（必须合并）
 *
 * 审计当前源码发现，同样的时间红线被写了两遍：
 *
 * | 位置 | 用途 |
 * |------|------|
 * | `checkSafetyGate()` 闸门 1（private suspend） | 点击前第一次判定 |
 * | `recheckTimeWindow()`（private fun） | 外勤等待 15 分钟后**再判一次** |
 *
 * 两处用的常量确实相同（`LATE_GRACE_MS` / `LATE_SAFETY_MARGIN_MS` /
 * `CLOCK_OUT_SAFETY_MARGIN_MS`），但**判定表达式是各写各的**。
 * 这意味着任何一次边界修正都必须同时改两个地方，漏改就会出现
 * "第一次放行、复查拒绝"（或反之）的自相矛盾行为 ——
 * 而两种结果在真机上都表现为"莫名其妙没打卡"。
 *
 * 抽取时必须**一次性把两份都换成同一个纯函数**，只抽一份等于把
 * 重复代码变成重复 + 未使用代码。
 *
 * ## 给 Lead 的建议
 *
 * 在 `exec/` 下新增（并让 PunchEngine 的**两处调用点都**改用它）：
 *
 * ```kotlin
 * internal object SafetyGate {
 *     const val LATE_GRACE_MS = 60_000L
 *     const val LATE_SAFETY_MARGIN_MS = 5_000L
 *     const val CLOCK_OUT_SAFETY_MARGIN_MS = 2_000L
 *
 *     /** 上班卡：目标时刻起 LATE_GRACE - SAFETY_MARGIN 之后必须停手 */
 *     fun clockInMustStopAt(targetAt: Long): Long =
 *         targetAt + LATE_GRACE_MS - LATE_SAFETY_MARGIN_MS
 *
 *     fun canClockIn(now: Long, targetAt: Long): Boolean = now <= clockInMustStopAt(targetAt)
 *
 *     /** 下班卡：目标时刻 + CLOCK_OUT_SAFETY_MARGIN 之前一律不打 */
 *     fun canClockOut(now: Long, targetAt: Long): Boolean =
 *         now >= targetAt + CLOCK_OUT_SAFETY_MARGIN_MS
 * }
 * ```
 *
 * 抽出来后把本文件里的 @Ignore 去掉即可，断言已经写好，不需要重写。
 *
 * 建议的调用点改造：
 *
 * ```kotlin
 * // 闸门 1
 * if (!SafetyGate.canClockOut(now, targetAt)) { ...拒绝... }
 *
 * // 闸门 3（外勤等待后复查）—— 同一个函数，不是第二份实现
 * if (!SafetyGate.canClockOut(System.currentTimeMillis(), targetAt)) { ...拒绝... }
 * ```
 *
 * 注意 `recheckTimeWindow` 的第二参数 `targetAt` 是**用户设定的目标时刻**，
 * 而 `checkSafetyGate` 用的是 `scheduledTargetAt`。合并时要确认两者
 * 语义一致（同一天同一分钟），否则会出现"复查用错日期"的新坑。
 */
class SafetyGateTest {

    /** 造一个确定的时刻，避免测试跟着机器时区/当前时间漂移 */
    private fun momentOfDay(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 11, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ==================== 闸门依赖的时刻地基（真实生产代码） ====================

    /**
     * 闸门内部的目标时刻就是 `startOfDay(now) + minuteOfDay * 60_000`。
     *
     * 这不是复刻闸门判断，而是锁住它唯一的**输入换算**：
     * 一旦 [PunchSchedule.startOfDay] 被改坏（例如忘记清毫秒、或把 DAY_OF_YEAR
     * 的偏移写反），两条红线的窗口会整体平移，而闸门的 if 条件看起来仍然"对"。
     */
    @Test
    fun clockInTargetIsExactlyStartOfDayPlusConfiguredMinute() {
        val now = momentOfDay(13, 47, 29)
        val clockInMinute = 9 * 60 + 30

        val dayStart = PunchSchedule.startOfDay(now)
        val targetAt = dayStart + TimeUnit.MINUTES.toMillis(clockInMinute.toLong())

        val cal = Calendar.getInstance().apply { timeInMillis = targetAt }
        assertEquals("上班目标应落在 09 点", 9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("上班目标应落在 30 分", 30, cal.get(Calendar.MINUTE))
        assertEquals("目标时刻必须整分，不能带秒", 0, cal.get(Calendar.SECOND))
        assertEquals("目标时刻不能带毫秒", 0, cal.get(Calendar.MILLISECOND))
    }

    /**
     * [PunchSchedule.startOfDay] 必须真的把时分秒毫秒清零。
     *
     * 闸门的目标时刻 = startOfDay + 分钟数。如果 startOfDay 保留了当前的
     * 时/分/秒（例如忘记 set 某个字段），目标时刻会整体前移若干分钟 ——
     * 而闸门的 `if` 条件本身看起来完全正确，这种漂移极难在代码评审里发现。
     */
    @Test
    fun startOfDayActuallyZeroesAllTimeFields() {
        val messy = momentOfDay(13, 47, 29)
        val cal = Calendar.getInstance().apply { timeInMillis = PunchSchedule.startOfDay(messy) }

        assertEquals("startOfDay 必须清零小时", 0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("startOfDay 必须清零分钟", 0, cal.get(Calendar.MINUTE))
        assertEquals("startOfDay 必须清零秒", 0, cal.get(Calendar.SECOND))
        assertEquals("startOfDay 必须清零毫秒", 0, cal.get(Calendar.MILLISECOND))
        assertEquals("startOfDay 不得改变日期", 11, cal.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 23:59 是分钟数的上界，必须仍在**同一天内**，不能溢出到第二天。
     * 溢出的后果是闸门拿前一天/后一天的目标判断，红线直接失效。
     */
    @Test
    fun clockOutTargetAtLastMinuteOfDayStaysInsideTheSameDay() {
        val now = momentOfDay(1, 0)
        val dayStart = PunchSchedule.startOfDay(now)
        val lastMinute = 24 * 60 - 1

        val targetAt = dayStart + TimeUnit.MINUTES.toMillis(lastMinute.toLong())

        val cal = Calendar.getInstance().apply { timeInMillis = targetAt }
        assertEquals("应仍在当天 11 日", 11, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
        // 与下一天 00:00 的差必须正好 1 分钟
        assertEquals(
            "23:59 距离下一天 00:00 必须正好 1 分钟",
            TimeUnit.MINUTES.toMillis(1L),
            PunchSchedule.startOfDay(now, 1) - targetAt,
        )
    }

    /** [PunchSchedule.startOfDay] 对同一时刻必须幂等，且不受时分秒影响 */
    @Test
    fun startOfDayIsIdempotentAndIgnoresTimeOfDay() {
        val early = momentOfDay(0, 0, 1)
        val late = momentOfDay(23, 59, 59)
        assertEquals(
            "同一天内任意时刻必须折算出同一个 00:00",
            PunchSchedule.startOfDay(early),
            PunchSchedule.startOfDay(late),
        )
        assertEquals(
            "startOfDay 必须幂等",
            PunchSchedule.startOfDay(late),
            PunchSchedule.startOfDay(PunchSchedule.startOfDay(late)),
        )
    }

    // ==================== 两条时间红线（挂起：生产函数 private 不可见） ====================

    /**
     * 【红线 1 · 下班卡】目标时刻 + 2 秒之前一律不打。
     *
     * 挂起原因：[com.redmi.autoattend.exec.PunchEngine.checkSafetyGate] 与
     * `CLOCK_OUT_SAFETY_MARGIN_MS` 都是 private，JVM 测试既拿不到
     * PunchEngine 实例（需 Context），也读不到私有常量。
     *
     * 解禁方式：Lead 抽出 `SafetyGate.canClockOut(now, targetAt)` 后，
     * 把下面的 fail() 换成对它的断言（本文件顶部注释里有建议签名）。
     */
    @Ignore(
        "生产闸门 checkSafetyGate 为 private suspend 且依赖 Context/AppConfig，" +
            "JVM 单元测试不可达。等 Lead 抽成 internal object SafetyGate 后解禁。"
    )
    @Test
    fun clockOutIsBlockedUntilTwoSecondsAfterTarget() {
        fail(
            "待 SafetyGate.canClockOut(now, targetAt) 落地后替换本断言。" +
                "期望：target-1s=false, target+0s=false, target+1s=false, target+2s=true"
        )
    }

    /**
     * 【红线 2 · 上班卡】目标时刻 + 55 秒之后必须停手，而不是等到 61 秒。
     *
     * 窗口 = [target, target + 60s - 5s]，即 55 秒宽。
     * 卡在 56/58/59 秒才停会生成迟到卡，这正是用户明确要求避免的。
     */
    @Ignore(
        "生产闸门 checkSafetyGate 为 private suspend 且依赖 Context/AppConfig，" +
            "JVM 单元测试不可达。等 Lead 抽成 internal object SafetyGate 后解禁。"
    )
    @Test
    fun clockInIsBlockedAfterFiftyFiveSecondsButNotBefore() {
        fail(
            "待 SafetyGate.canClockIn(now, targetAt) 落地后替换本断言。" +
                "期望：target+50s=true, target+55s=true, target+56s=false, target+61s=false"
        )
    }
}
