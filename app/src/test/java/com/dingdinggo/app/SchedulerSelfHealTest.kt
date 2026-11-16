package com.dingdinggo.app

import com.dingdinggo.app.core.AlarmScheduler
import com.dingdinggo.app.core.PunchSchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 回归防线：闹钟自愈判定。
 *
 * ## 这个文件存在的唯一理由
 *
 * 2026-11-13 审计发现的最严重缺陷是：`AlarmScheduler.scheduleNext()` 里
 * 一个早返回守卫，只要 prefs 里记着"本班次已排过"就直接 return，
 * **从不检查系统侧闹钟是否还在**。
 *
 * 后果：force-stop / 系统重启 / HyperOS 清理闹钟之后，三个打卡闹钟
 * （PREWARM / FIRE / RETRY）全部消失且**永远不会恢复**，当天直接漏卡。
 * 更糟的是 `KeepAliveService` 每 15 分钟的自检走同一个函数，
 * **自愈机制被自己挡死**，跑一整天也救不回来。
 *
 * 这个 bug 在项目里挂了好几天（README §7 记作"未解决"），根本原因是
 * 判定逻辑内联在依赖 Context 的方法里，**没有任何单元测试能覆盖它**。
 *
 * 修复时把判定抽成了纯函数 [AlarmScheduler.reusePreviousAlarms]，
 * 本类就是它的真值表。任何一次改动只要破坏了"未来时刻必须重排"这条，
 * 下面的测试会立刻红。
 */
class SchedulerSelfHealTest {

    private val target = 1_700_000_000_000L
    private val kind = "CLOCK_OUT"

    /** 调用辅助：默认传入"同一班次、无已触发标记" */
    private fun reuse(
        previousTargetAt: Long = target,
        previousKind: String? = kind,
        slotTargetAt: Long = target,
        slotKind: String = kind,
        nowMillis: Long = target - 60_000L,
        firedTargetAt: Long = 0L,
        firedKind: String? = null,
    ): Boolean = AlarmScheduler.reusePreviousAlarms(
        previousTargetAt = previousTargetAt,
        previousKind = previousKind,
        slotTargetAt = slotTargetAt,
        slotKind = slotKind,
        nowMillis = nowMillis,
        firedTargetAt = firedTargetAt,
        firedKind = firedKind,
    )

    // ============ 核心回归：P0 本身就是这四条 ============

    /**
     * 【P0 核心】目标时刻仍在未来时，**必须重排**，不能沿用旧记录。
     *
     * 这正是修复前的 bug：旧代码在这里返回 true（跳过重排），
     * 于是系统侧闹钟被清掉后永远补不回来。
     *
     * 如果有人把这条改回 true，本测试立刻失败 —— 这就是防线。
     */
    @Test
    fun futureTargetMustAlwaysReschedule_evenIfSameSlotRecorded() {
        assertFalse(
            "目标时刻在未来时绝不能因为 prefs 有记录就跳过重排 —— " +
                "系统侧闹钟可能已被 force-stop/系统清理掉，跳过即永久漏卡",
            reuse(nowMillis = target - 60_000L, firedTargetAt = 0L),
        )
    }

    /** 目标时刻在未来，即使有"已触发"标记也必须重排（标记只对已过时刻有意义） */
    @Test
    fun futureTargetMustReschedule_evenIfMarkedFired() {
        assertFalse(
            "未来的班次不可能已经触发过，此标记不应阻止重排",
            reuse(
                nowMillis = target - 60_000L,
                firedTargetAt = target,
                firedKind = kind,
            ),
        )
    }

    /** 目标时刻在未来、临近触发（例如提前 1 秒）也必须重排 */
    @Test
    fun futureTargetOneSecondAheadStillReschedules() {
        assertFalse(
            "哪怕只剩 1 秒也要重排（系统侧闹钟可能已丢）",
            reuse(nowMillis = target - 1_000L),
        )
    }

    /** 目标时刻恰好等于 now：属于"已过"边界，未触发过 → 必须重排补执行 */
    @Test
    fun targetExactlyNowWithoutFiredMarkMustRescheduleForRetry() {
        assertFalse(
            "刚到点但从未触发过 → 必须重排，否则补执行链路断掉",
            reuse(nowMillis = target, firedTargetAt = 0L),
        )
    }

    // ============ 防自激：已过且已触发才可沿用 ============

    /** 目标时刻已过 + 确实触发过 → 保持既有闹钟（原有防自激意图） */
    @Test
    fun pastTargetWithFiredMarkKeepsExistingAlarm() {
        assertTrue(
            "已过且已触发过 → 保持既有闹钟，避免把过去时刻钳到 now 再次触发",
            reuse(
                nowMillis = target + 30_000L,
                firedTargetAt = target,
                firedKind = kind,
            ),
        )
    }

    /** 已过但触发标记的班次不同 → 不能沿用 */
    @Test
    fun firedMarkWithDifferentKindDoesNotCount() {
        assertFalse(
            "触发标记是上班卡，本班是下班卡 → 不算已触发，必须重排",
            reuse(
                nowMillis = target + 30_000L,
                firedTargetAt = target,
                firedKind = "CLOCK_IN",
            ),
        )
    }

    /** 已过但触发标记的时间戳不同 → 不能沿用 */
    @Test
    fun firedMarkWithDifferentTimestampDoesNotCount() {
        assertFalse(
            "触发标记是另一个时刻 → 不算已触发，必须重排",
            reuse(
                nowMillis = target + 30_000L,
                firedTargetAt = target - 86_400_000L,
                firedKind = kind,
            ),
        )
    }

    // ============ 班次不同 → 一律重排 ============

    @Test
    fun differentSlotMustReschedule() {
        assertFalse(
            "换了班次必须重排",
            reuse(slotTargetAt = target + 3_600_000L),
        )
    }

    @Test
    fun differentKindMustReschedule() {
        assertFalse(
            "同一时刻但换个班次也必须重排",
            reuse(slotKind = "CLOCK_IN"),
        )
    }

    /** 没有历史记录（previousTargetAt = 0）→ 必须重排 */
    @Test
    fun noPreviousRecordMustReschedule() {
        assertFalse(
            "冷启动/首次调度没有记录 → 必须注册闹钟",
            reuse(previousTargetAt = 0L),
        )
    }

    /** previousKind 为 null（prefs 损坏）→ 不能沿用 */
    @Test
    fun nullPreviousKindMustReschedule() {
        assertFalse(
            "记录损坏时不能当作同一班次沿用",
            reuse(previousKind = null),
        )
    }

    // ============ 与真实运行路径的一致性 ============

    /**
     * 真机复现路径：force-stop 后系统闹钟被清空，但 prefs 里
     * `last_target_at` 还在。重启 App 时 now < target → 必须重排。
     *
     * 这条用真实的时间关系复刻当时的现场，防止将来"优化"时把
     * 未来分支又合并回同一判断。
     */
    @Test
    fun realWorldForceStopScenarioReschedules() {
        // 场景：下班 18:30，当前 12:40（审计当天真机上的实际时刻关系）
        val dayStart = PunchSchedule.startOfDay(System.currentTimeMillis())
        val targetAt = dayStart + (18 * 60 + 30) * 60_000L
        val now = dayStart + (12 * 60 + 40) * 60_000L

        assertFalse(
            "force-stop 清空闹钟后重启：prefs 有记录但系统已空，必须重排",
            reuse(
                previousTargetAt = targetAt,
                slotTargetAt = targetAt,
                nowMillis = now,
                firedTargetAt = 0L,
            ),
        )
    }
}
