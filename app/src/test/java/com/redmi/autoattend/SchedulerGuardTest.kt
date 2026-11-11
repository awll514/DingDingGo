package com.redmi.autoattend

import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.core.ScheduleSettings
import com.redmi.autoattend.data.db.PunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.util.Calendar

/**
 * 调度抗自激 + "刚触发标记"的测试。
 *
 * ## 这一组测试要防的是什么
 *
 * [PunchSchedule.nextSlot] 为了支持"补执行"，会把**仍在补执行窗口内**的
 * 已过时间点也算作"下一次打卡"（effectiveTrigger = now）。
 *
 * 这带来一个危险的耦合：闹钟触发 → 重排 → nextSlot 又返回
 * **同一个班次** → 立刻再排一个闹钟 → 再触发……
 *
 * 正常情况下这个循环被"成功后跳过已完成班次"打断。但如果本次执行失败、
 * 或服务因"已有执行在进行"被忽略，就会空转到窗口耗尽。
 *
 * 修法有两层：
 * 1. [PunchSchedule.nextSlot] 支持 `completedSlots`，命中就跳过（纯函数，本类完整覆盖）；
 * 2. [AlarmScheduler.markJustFired] 记住"这一班刚触发过"，重排时跳过
 *    （**只有入口可测**，见下方 @Ignore 说明）。
 *
 * ## 覆盖不到的部分（已显式挂起，不做假绿）
 *
 * `AlarmScheduler` 的 `render()` / `cancelAll()` / `readLastScheduled()` /
 * `isJustFired()` / `scheduleNext()` 全部依赖 `Context`
 * （AlarmManager、SharedPreferences、AppConfig、Room），
 * 本项目的 JVM 测试没有 Robolectric/Mockito 依赖（见 app/build.gradle.kts），
 * 因此这几块**无法在此覆盖**。相关用例全部 @Ignore 并写明解禁条件。
 */
class SchedulerGuardTest {

    /** 上班 09:30，下班 18:00，周一至周五 */
    private val settings = ScheduleSettings(
        clockInMinute = 9 * 60 + 30,
        clockOutMinute = 18 * 60,
        activeDays = ScheduleSettings.WEEKDAYS,
    )

    /** 找一个确定是工作日的"当天 00:00"，避免测试跑在周末时行为不确定 */
    private fun weekdayStart(): Long {
        var millis = PunchSchedule.startOfDay(System.currentTimeMillis())
        while (Calendar.getInstance().apply { timeInMillis = millis }
                .get(Calendar.DAY_OF_WEEK) !in settings.activeDays
        ) {
            millis += 24 * 60 * 60 * 1000L
        }
        return millis
    }

    private fun clockInAt(dayStart: Long): Long = dayStart + settings.clockInMinute * 60_000L

    // ==================== 自激循环的两个前提（纯函数，完整覆盖） ====================

    @Test
    fun withinRetryWindowStillReturnsTheSameSlotWhichIsTheSelfTriggeringCause() {
        // 目标 09:30，当前 09:31 —— 仍在 120 秒补执行窗口内，
        // nextSlot 会返回**同一个上班班次**。这正是自激循环的成因，
        // 先确认它真实存在，否则下面的修复无从验证。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + 60_000L

        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_IN, slot!!.kind)
        assertEquals(target, slot.targetAtMillis)
    }

    @Test
    fun markingSlotCompletedMakesNextSlotAdvanceToTheFollowingKind() {
        // 把上面那个班次标记为已完成（AlarmScheduler.markJustFired 最终走这条路），
        // 下一次计算必须顺延到**下班**，而不是继续返回上班。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + 60_000L

        val first = PunchSchedule.nextSlot(now, settings)!!
        val dateKey = PunchSchedule.dateKey(first.targetAtMillis)

        val afterMark = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(PunchSchedule.CompletedSlot(dateKey, first.kind)),
        )

        assertNotNull(afterMark)
        assertEquals(PunchKind.CLOCK_OUT, afterMark!!.kind)
        assertTrue(afterMark.targetAtMillis > first.targetAtMillis)
    }

    @Test
    fun afterRetryWindowClosesTheSlotIsNoLongerReturned() {
        // 过了补执行窗口（目标 + 120 秒）后，该班次不应再被返回，
        // 否则"补执行"会变成"无限补执行"。
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)
        val now = target + (PunchSchedule.RETRY_WINDOW_SECONDS + 1) * 1000L

        val slot = PunchSchedule.nextSlot(now, settings)
        assertNotNull(slot)
        assertEquals(PunchKind.CLOCK_OUT, slot!!.kind)
    }

    @Test
    fun completedSlotsDoNotLeakBetweenClockInAndClockOut() {
        // 早上打了上班卡，不能影响下午的下班卡。
        // 这是"手动打卡后不重复触发"修复里最容易写错的一条。
        val dayStart = weekdayStart()
        val dateKey = PunchSchedule.dateKey(dayStart)
        val now = dayStart + 8 * 60 * 60 * 1000L

        val onlyClockInDone = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_IN)),
        )
        assertNotNull(onlyClockInDone)
        assertEquals(PunchKind.CLOCK_OUT, onlyClockInDone!!.kind)

        val bothDone = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = settings,
            completedSlots = setOf(
                PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_IN),
                PunchSchedule.CompletedSlot(dateKey, PunchKind.CLOCK_OUT),
            ),
        )
        // 今天两班都打完了，下一个必须是**之后的某一天**
        assertNotNull(bothDone)
        assertTrue(bothDone!!.targetAtMillis >= dayStart + 24 * 60 * 60 * 1000L)
    }

    /**
     * 已完成槽位的判定粒度是 **(日期, 班次)**，日期键算错就会跳错班。
     *
     * 生产里 markJustFired 记的是 targetAt（毫秒），重排时要把它折成
     * `PunchSchedule.dateKey(targetAt)` 才能和数据库里的 SUCCESS 记录对齐。
     * 这条把那个折算钉住：同一个 targetAt 必须落在它自己的那一天。
     */
    @Test
    fun dateKeyOfTargetAtIdentifiesTheDayTheSlotBelongsTo() {
        val dayStart = weekdayStart()
        val target = clockInAt(dayStart)

        assertEquals(
            "上班卡的目标时刻必须与它所在的那一天同属一个 dateKey",
            PunchSchedule.dateKey(dayStart),
            PunchSchedule.dateKey(target),
        )
        assertFalse(
            "不能错误地等同于前一天的 dateKey",
            PunchSchedule.dateKey(dayStart) == PunchSchedule.dateKey(dayStart - 1L),
        )
    }

    // ==================== withinRetryWindow 的窗口边界（纯函数，完整覆盖） ====================

    /**
     * [AlarmScheduler.withinRetryWindow] 的窗口是
     * `[target - PRE_WARM_SECONDS, target + RETRY_WINDOW_SECONDS]`，两端都含。
     *
     * 服务用它判断"这次闹钟还值不值得启动"：
     * 左边少了 → 预热闹钟到点时被判过期，预热链路白排；
     * 右边少了 → 补执行那一次被丢弃，兜底失效。
     */
    @Test
    fun withinRetryWindowIncludesBothEndpointsAndExcludesOneMillisecondOutside() {
        val target = weekdayStart() + 9 * 60 * 60 * 1000L
        val prewarmMs = PunchSchedule.PRE_WARM_SECONDS * 1000L
        val retryMs = PunchSchedule.RETRY_WINDOW_SECONDS * 1000L

        assertTrue("左端点必须含", AlarmScheduler.withinRetryWindow(target - prewarmMs, target))
        assertTrue("目标时刻必须含", AlarmScheduler.withinRetryWindow(target, target))
        assertTrue("右端点必须含", AlarmScheduler.withinRetryWindow(target + retryMs, target))

        assertFalse(
            "左端点外 1ms 必须判否",
            AlarmScheduler.withinRetryWindow(target - prewarmMs - 1L, target),
        )
        assertFalse(
            "右端点外 1ms 必须判否",
            AlarmScheduler.withinRetryWindow(target + retryMs + 1L, target),
        )
    }

    // ==================== markJustFired 入口（仅能测到"不抛异常"这一层） ====================

    /**
     * [AlarmScheduler.markJustFired] / [AlarmScheduler.clearJustFired] 的
     * **调用安全契约**：任意顺序、任意次数调用都不得抛异常。
     *
     * 这不是形式主义 —— 它们的调用点是 `PunchAlarmReceiver` 的冷启动路径
     * 和 `onPunchFinished` 的 `runCatching` 里。一旦在这里抛异常，
     * 重排链路直接中断，当天下一次打卡不会再有闹钟。
     *
     * 也验证了"标记后立刻清除"的跨天场景不会把状态留成脏值。
     */
    @Test
    fun markJustFiredAndClearJustFiredAreSafeInAnyCallOrder() {
        val target = weekdayStart() + 9 * 60 * 60 * 1000L

        AlarmScheduler.clearJustFired()
        AlarmScheduler.markJustFired(target, PunchKind.CLOCK_IN)
        AlarmScheduler.markJustFired(target, PunchKind.CLOCK_OUT)
        AlarmScheduler.markJustFired(target, PunchKind.MANUAL)
        AlarmScheduler.markJustFired(target, PunchKind.CLOCK_IN)
        AlarmScheduler.clearJustFired()
        AlarmScheduler.clearJustFired()

        // 清除后重新标记也必须正常（跨天后新一班）
        AlarmScheduler.markJustFired(target + 24 * 60 * 60 * 1000L, PunchKind.CLOCK_IN)
        AlarmScheduler.clearJustFired()
    }

    /**
     * 标记值的真实效果：`targetAt` 必须能被无损折算成调度器使用的槽位身份。
     *
     * 这是 markJustFired 与 nextSlot 之间唯一的公共语言。
     * 若有人在 markJustFired 里存了 `triggerAtMillis` 而不是 `targetAtMillis`，
     * `isJustFired` 将永远匹配不上，自激防护静默失效 —— 而代码看起来完全正确。
     */
    @Test
    fun justFiredTargetAtMustBeTargetAtNotTriggerAt() {
        val dayStart = weekdayStart()
        val slot = PunchSchedule.nextSlot(dayStart + 6 * 60 * 60 * 1000L, settings)!!

        val targetAt = slot.targetAtMillis
        val triggerAt = slot.triggerAtMillis

        assertTrue("这两个时刻必须不是同一个值，否则本用例没有区分力", targetAt != triggerAt)
        assertEquals(
            "triggerAt 必须等于 targetAt 减去预热闹钟提前量",
            triggerAt,
            targetAt - PunchSchedule.PRE_WARM_SECONDS * 1000L,
        )
        // markJustFired 存的是 targetAt；把它折成 CompletedSlot 后必须能跳过该班
        val afterMarkWithTargetAt = PunchSchedule.nextSlot(
            nowMillis = targetAt + 30_000L,
            settings = settings,
            completedSlots = setOf(
                PunchSchedule.CompletedSlot(PunchSchedule.dateKey(targetAt), slot.kind),
            ),
        )
        assertNotNull(afterMarkWithTargetAt)
        assertEquals(
            "用 targetAt 折算出的槽位必须能正确跳过本班",
            PunchKind.CLOCK_OUT,
            afterMarkWithTargetAt!!.kind,
        )
    }

    // ==================== 因 visibility / Context 受阻的部分（显式挂起） ====================

    /**
     * `AlarmScheduler.isJustFired(slot)` 是判定自激防护是否生效的核心，
     * 但它是 **private fun**，且只能通过 `scheduleNext(context)` 间接观察，
     * 而后者需要 Context（AlarmManager + SharedPreferences + Room）。
     *
     * ## 建议 Lead 这样改（二选一）
     *
     * **方案 A（最小改动，推荐）**：把 `isJustFired` 提升为 `internal`，
     * 并保留 `markJustFired` / `clearJustFired` 的 present 签名：
     *
     * ```kotlin
     * internal fun isJustFired(slot: NextPunchSlot): Boolean =
     *     slot.targetAtMillis == justFiredTargetAt && slot.kind.name == justFiredKind
     * ```
     *
     * 解禁后本用例改为：
     * ```kotlin
     * AlarmScheduler.clearJustFired()
     * AlarmScheduler.markJustFired(t, PunchKind.CLOCK_IN)
     * assertTrue(AlarmScheduler.isJustFired(slot(t, PunchKind.CLOCK_IN)))
     * assertFalse(AlarmScheduler.isJustFired(slot(t, PunchKind.CLOCK_OUT)))  // 二维键
     * assertFalse(AlarmScheduler.isJustFired(slot(t + 1, PunchKind.CLOCK_IN))) // 时间不同
     * AlarmScheduler.clearJustFired()
     * assertFalse(AlarmScheduler.isJustFired(slot(t, PunchKind.CLOCK_IN)))
     * ```
     *
     * **方案 B（更彻底）**：把它抽成纯函数放到 `PunchSchedule` 旁边：
     * ```kotlin
     * fun isSameSlot(a: NextPunchSlot, b: NextPunchSlot): Boolean =
     *     a.targetAtMillis == b.targetAtMillis && a.kind == b.kind
     * ```
     * 然后 `isJustFired` 改为 `isSameSlot(slot, lastFiredSlot)` 的封装，
     * 纯函数部分即可直接单测。
     */
    @Ignore(
        "AlarmScheduler.isJustFired 为 private，且唯一观察路径 scheduleNext(context) 需要 Context" +
            "（AlarmManager/SharedPreferences/Room），本项目无 Robolectric 依赖。" +
            "建议把 isJustFired 提升为 internal，或抽成纯函数 isSameSlot(a, b)。"
    )
    @Test
    fun isJustFiredMatchesOnBothTargetAtAndKind() {
        fail("待 isJustFired 提升为 internal 后解禁，见上方 KDoc 中的断言模板")
    }

    /**
     * `AlarmScheduler.cancelAll(context, lastSlot)` 是对外公开的，
     * 但它内部要构造 PendingIntent 并调用 `AlarmManager.cancel`，必须有 Context。
     *
     * ## 它为什么必须被覆盖
     *
     * 这里曾出过一个真实 P0：`render()` 先 `cancelAll(readLastScheduled())`
     * 再注册新一轮三段闹钟。当读到的是**同一个 targetAt** 时，
     * cancelAll 把刚注册的 FIRE / RETRY 一起取消了，
     * 三段闹钟退化成"一个凭空等待 75 秒的前台服务"，兜底链路整条断掉。
     *
     * ## 建议 Lead 这样改
     *
     * 把"该取消哪些 PendingIntent"从"如何取消"里拆出来，纯函数化：
     *
     * ```kotlin
     * internal data class AlarmHandles(
     *     val prewarmRequestCode: Int,
     *     val fireRequestCode: Int,
     *     val retryRequestCode: Int,
     *     val uriKey: String,          // action + kind + targetAt
     * )
     *
     * internal fun handlesFor(slot: NextPunchSlot): AlarmHandles
     * ```
     *
     * 这样"同一 targetAt 的 cancel 与 render 撞车"这个问题就能在纯函数层断言：
     * 只要 `handlesFor(slotA) == handlesFor(slotB)` iff 两个 slot 完全相同，
     * 就能证明"换班次必换 handle、同班次必撞车"，
     * 进而要求 render 在 cancel 之前先比较新旧 slot 是否相同。
     */
    @Ignore(
        "cancelAll 需要 Context 构造 PendingIntent/SharedPreferences；" +
            "本项目无 Robolectric 依赖。建议把 requestCode/uri 的构造抽成 handlesFor(slot) 纯函数后补测。"
    )
    @Test
    fun cancelAllAndRenderMustNotCancelTheAlarmsTheyJustRegistered() {
        fail("待抽成 handlesFor(slot) 纯函数后解禁，见上方 KDoc")
    }

    /**
     * `AlarmScheduler.readLastScheduled(context)` 是 private，且要读 SharedPreferences。
     *
     * ## 它为什么重要（P0 相关）
     *
     * `cancelAll(context, null)` 的注释写着"冷进程里 lastScheduled == null 直接 return，
     * 什么也没取消" —— 修法就是把 lastScheduled 持久化到 prefs，冷进程也能读回来。
     * 所以 `readLastScheduled` 一旦不返回、或返回的 triggerAt 算错，
     * "改时间后旧闹钟仍会触发"这个 bug 会重新出现。
     *
     * ## 建议 Lead 这样改
     *
     * 把"毫秒 ↔ (targetAt, kind)"的编解码抽成不依赖 Context 的纯函数：
     *
     * ```kotlin
     * internal fun encodeLastScheduled(slot: NextPunchSlot): Pair<Long, String> =
     *     slot.targetAtMillis to slot.kind.name
     *
     * internal fun decodeLastScheduled(targetAt: Long, kindName: String?): NextPunchSlot?
     * ```
     *
     * 断言点：`decode(encode(slot))` 必须还原出同一个 slot（含 triggerAt 的推导），
     * `targetAt <= 0` 与 `kindName == null` 都必须返回 null 而不是抛异常。
     */
    @Ignore(
        "readLastScheduled 为 private 且读 SharedPreferences。建议抽出纯函数 " +
            "encodeLastScheduled/decodeLastScheduled 后补测 round-trip 与非法输入。"
    )
    @Test
    fun lastScheduledPersistRoundTripSurvivesColdProcess() {
        fail("待抽出 encodeLastScheduled/decodeLastScheduled 后解禁，见上方 KDoc")
    }
}