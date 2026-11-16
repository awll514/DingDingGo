package com.dingdinggo.app

import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.exec.PunchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * ### 已更新（2026-11-13 审计后）
 *
 * 两条**最重要的红线断言已解挂并直接测试**：
 *   · [clockOutIsBlockedUntilTwoSecondsAfterTarget]
 *   · [clockInIsBlockedAfterFiftyFiveSecondsButNotBefore]
 *
 * 原因是它们依赖的 `PunchEngine.timeWindowVerdict` 与三个边界函数
 * 现在都是 **internal 纯函数** —— 不碰 Context、不读系统时钟，
 * JVM 测试可以直接调用。之前"private suspend 依赖 Context 不可达"
 * 的挂起理由已经过期。
 *
 * 覆盖范围：上班卡双边窗口 [target-75s, target+55s]、下班卡下界 target+2s
 * 且无上界、迟到 56 秒即停手 —— 这些是"绝不打迟到卡/早退卡"的全部保证。
 *
 * ### 仍不覆盖的部分
 *
 * [com.dingdinggo.app.exec.PunchEngine.checkSafetyGate] 本身仍是
 * private suspend 且读取 AppConfig / 无障碍服务，JVM 测试拿不到实例。
 * 但它现在只是**调用** [PunchEngine.timeWindowVerdict] 做判定，
 * 判定逻辑本身已被上面的测试钉死。
 *
 * 旧版 [SafetyGateTest] 的做法是**自己复刻一份 canClockIn / canClockOut**，
 * 断言的是副本。那样改坏生产常量测试照样全绿，是同义反复 —— 已删除。
 *
 * ## 当前状态（2026-11-13 审计后已全部落地）
 *
 * 本文件现在测两件**真的来自生产代码**的事：
 *
 *  1. 闸门依赖的时刻地基：[PunchSchedule.startOfDay] + 分钟数换算成目标时刻。
 *     这块算错的后果和闸门算错一样 —— 整条红线整体平移。
 *  2. **两条红线本身**（已解挂，不再是占位）：
 *     · [clockOutIsBlockedUntilTwoSecondsAfterTarget]
 *     · [clockInIsBlockedAfterFiftyFiveSecondsButNotBefore]
 *
 * 能做到第 2 点，是因为审计期间把判定逻辑收敛成了唯一实现：
 * [com.dingdinggo.app.exec.PunchEngine.timeWindowVerdict] 及三个
 * internal 边界函数（`clockInEarliestAt` / `clockInMustStopAt` /
 * `clockOutEarliestAt`）。它们不碰 Context、不读系统时钟，
 * JVM 测试可以直接断言 —— 之前"private suspend 依赖 Context 不可达"
 * 的挂起理由已经过期。
 *
 * ## 历史上这里提过一个建议（已实施，保留记录）
 *
 * 审计前本文件曾建议"新增 internal object SafetyGate 并让两处调用点都改用它"。
 * 实际采用的方案更彻底：直接把判定收敛到 [PunchEngine.timeWindowVerdict]
 * 这一个纯函数，`checkSafetyGate` 与 `recheckTimeWindow` 都只调用它，
 * 不再各写一份表达式。常量也统一为 [com.dingdinggo.app.core.BtPunchRule]
 * 的单一来源（PunchEngine 侧改为引用，不再重复定义）。
 *
 * ⚠️ 仍需注意：`recheckTimeWindow` 的第二参数是**用户设定的目标时刻**，
 * 而 `checkSafetyGate` 用的是 `scheduledTargetAt`。两者语义必须一致
 * （同一天同一分钟），否则会出现"复查用错日期"的新坑。
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
     * 挂起原因：[com.dingdinggo.app.exec.PunchEngine.checkSafetyGate] 与
     * `CLOCK_OUT_SAFETY_MARGIN_MS` 都是 private，JVM 测试既拿不到
     * PunchEngine 实例（需 Context），也读不到私有常量。
     *
     * 解禁方式：Lead 抽出 `SafetyGate.canClockOut(now, targetAt)` 后，
     * 把下面的 fail() 换成对它的断言（本文件顶部注释里有建议签名）。
     */
    @Test
    fun clockOutIsBlockedUntilTwoSecondsAfterTarget() {
        // 【已解挂】挂起理由已过期：PunchEngine.timeWindowVerdict 现在是
        // internal 纯函数（不碰 Context、不读系统时钟），JVM 测试可直接断言。
        //
        // 这条红线是"绝不提前打下班卡"的唯一保证。它一旦被改坏
        // （例如把 +2s 改成 +0s 或去掉下界），会直接生成早退记录，
        // 而没有任何其他测试会发现 —— 所以必须钉死。
        val target = 1_700_000_000_000L

        // target - 1s / target + 0s / target + 1s：都必须拒绝（Early）
        for (offset in listOf(-1_000L, 0L, 1_000L)) {
            val verdict = PunchEngine.timeWindowVerdict(
                PunchKind.CLOCK_OUT, target + offset, target,
            )
            assertTrue(
                "下班卡在 target" + (offset / 1000) + "s 必须被拒绝，实际=" + verdict,
                verdict is PunchEngine.TimeWindowVerdict.Early,
            )
        }

        // target + 2s 起放行
        assertNull(
            "下班卡在 target+2s 必须放行",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_OUT, target + 2_000L, target),
        )

        // 且下班卡**没有上界**（这是已知语义：晚走总是允许的）
        assertNull(
            "下班卡在 target+2h 仍应放行（晚走不算早退）",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_OUT, target + 7_200_000L, target),
        )
    }

    /**
     * 【红线 2 · 上班卡】目标时刻 + 55 秒之后必须停手，而不是等到 61 秒。
     *
     * 窗口 = [target, target + 60s - 5s]，即 55 秒宽。
     * 卡在 56/58/59 秒才停会生成迟到卡，这正是用户明确要求避免的。
     */
    @Test
    fun clockInIsBlockedAfterFiftyFiveSecondsButNotBefore() {
        // 【已解挂】同上方理由，现在可直测 internal 纯函数。
        //
        // 这是"绝不打迟到卡"的唯一保证：窗口上界 = target + 60s - 5s = +55s。
        // 注意 56 秒就要停手，而不是等到 61 秒 —— 用户明确要求
        // "满 1 分钟才算迟到"，但同时要求预留安全余量。
        val target = 1_700_000_000_000L

        // 窗口内（含 55 秒整）放行
        for (offsetSec in listOf(0L, 30L, 50L, 55L)) {
            assertNull(
                "上班卡在 target+" + offsetSec + "s 必须放行",
                PunchEngine.timeWindowVerdict(
                    PunchKind.CLOCK_IN, target + offsetSec * 1000L, target,
                ),
            )
        }

        // 56 秒起必须停手（Late）
        for (offsetSec in listOf(56L, 58L, 61L, 120L)) {
            val verdict = PunchEngine.timeWindowVerdict(
                PunchKind.CLOCK_IN, target + offsetSec * 1000L, target,
            )
            assertTrue(
                "上班卡在 target+" + offsetSec + "s 必须被拒绝（防迟到卡），实际=" + verdict,
                verdict is PunchEngine.TimeWindowVerdict.Late,
            )
        }

        // 下界：target-75s 之前太早
        assertTrue(
            "上班卡在 target-76s 必须被拒绝（prewarm 窗口之外）",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_IN, target - 76_000L, target)
                is PunchEngine.TimeWindowVerdict.TooEarlyForClockIn,
        )
        assertNull(
            "上班卡在 target-75s 必须放行（预热窗口下界）",
            PunchEngine.timeWindowVerdict(PunchKind.CLOCK_IN, target - 75_000L, target),
        )
    }
}
