package com.redmi.autoattend.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.redmi.autoattend.data.db.PunchKind
import com.redmi.autoattend.exec.PunchAlarmReceiver

/**
 * 硬调度主干。
 *
 * 设计原则（对应 AGENTS.md 验收标准 1）：
 * - 只用 AlarmManager.setExactAndAllowWhileIdle，不用 WorkManager 做准点触发；
 * - 闹钟的 PendingIntent 指向静态注册的 BroadcastReceiver，杀掉进程后仍能触发；
 * - 每次重新调度前先取消旧的，避免重复触发；
 * - 开机、改时间、改配置后都要重排。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** 上班闹钟的 requestCode */
    private const val RC_CLOCK_IN = 1001

    /** 下班闹钟的 requestCode */
    private const val RC_CLOCK_OUT = 1002

    /** 预热闹钟（提前 75 秒，用来点亮屏幕并开始准备） */
    private const val ACTION_PREWARM = "com.redmi.autoattend.action.PREWARM"

    /** 目标时刻的主闹钟（真正的兜底触发点） */
    private const val ACTION_FIRE = "com.redmi.autoattend.action.FIRE"

    /** 补执行闹钟（目标时间 + 120 秒窗口内的一次机会） */
    private const val ACTION_RETRY = "com.redmi.autoattend.action.RETRY"

    private fun alarmManager(ctx: Context): AlarmManager =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 能否使用精确闹钟。
     *
     * 判断顺序（重要，来自 Android 14 官方行为）：
     *
     * 1. **电池优化白名单是精确闹钟的官方豁免**。
     *    Android 14 的"闹钟和提醒"设置页原文：power allowlist 上的应用
     *    "always allowed to call setExact() / setExactAndAllowWhileIdle()"。
     *    也就是说，即使用户拒绝了 SCHEDULE_EXACT_ALARM，
     *    只要应用在电池优化白名单里，精确闹钟依然合法。
     *
     *    因此这里**先查白名单**——这是比"精确闹钟权限"更容易拿到、
     *    也更值得优先引导用户开启的开关。
     *
     * 2. 白名单不可用（未开启）时，才回落到 canScheduleExactAlarms()。
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        // 优先判定：电池优化白名单 = 官方豁免
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
                .getOrDefault(false)
        ) {
            Log.i(TAG, "精确闹钟：电池优化白名单已豁免")
            return true
        }

        return runCatching { alarmManager(context).canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * 当前设备精确能力的详细状态，用于健康度卡片如实展示。
     *
     * @return Pair(是否可用, 原因说明)
     */
    fun exactStatus(context: Context): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true to "系统版本无需授权"
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val whitelisted = runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        if (whitelisted) return true to "电池无限制已豁免"

        val granted = runCatching { alarmManager(context).canScheduleExactAlarms() }
            .getOrDefault(false)
        return if (granted) {
            true to "已授权精确闹钟"
        } else {
            false to "未授权，且未加入电池无限制"
        }
    }

    private fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        kindName: String,
        targetAt: Long,
    ): PendingIntent {
        val intent = Intent(context, PunchAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kindName)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, targetAt)
            // 保证每个待触发点都是独立 PendingIntent
            data = android.net.Uri.parse("autoattend://" + action + "/" + kindName + "/" + targetAt)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPending(
        context: Context,
        action: String,
        requestCode: Int,
        kindName: String,
        targetAt: Long,
    ) {
        val pi = pendingIntent(context, action, requestCode, kindName, targetAt)
        alarmManager(context).cancel(pi)
        pi.cancel()
    }

    /**
     * 取消全部已注册的闹钟。
     *
     * 由于 PendingIntent 的 data 里带了 targetAt，取消时必须用完全相同的 data，
     * 所以这里改用"已知 slot"来取消。调用方应传入上一次调度用的 slot。
     */
    fun cancelAll(context: Context, lastSlot: NextPunchSlot?) {
        val slot = lastSlot ?: readLastScheduled(context)
        if (slot != null) {
            val kindName = slot.kind.name
            val target = slot.targetAtMillis
            val rc = requestCodeFor(slot.kind)
            cancelPending(context, ACTION_PREWARM, rc + 10, kindName, target)
            cancelPending(context, ACTION_FIRE, rc, kindName, target)
            cancelPending(context, ACTION_RETRY, rc + 20, kindName, target)
        }

        // 持久化记录也要清掉，否则下次 render 会拿它去取消一个已不存在的闹钟
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TARGET_AT)
                .remove(KEY_KIND)
                .apply()
        }
        lastScheduled = null
    }

    private fun requestCodeFor(kind: PunchKind): Int = when (kind) {
        PunchKind.CLOCK_IN -> RC_CLOCK_IN
        PunchKind.CLOCK_OUT -> RC_CLOCK_OUT
        PunchKind.MANUAL -> RC_CLOCK_IN
    }

    /**
     * 调度下一个打卡点。
     *
     * @return 实际注册的 slot；若无可调度项返回 null
     */
    fun scheduleNext(context: Context): NextPunchSlot? {
        val now = System.currentTimeMillis()
        if (!AppConfig.enabled.value) {
            Log.i(TAG, "punch disabled, skip scheduling")
            // 关闭时也要取消已注册的闹钟，否则旧闹钟到点仍会触发
            cancelAll(context, null)
            return null
        }

        // 【关键】把"今天已经打完的班"带上，否则手动打卡后
        // 到点闹钟仍会再打一次（用户明确反馈过的问题）。
        val completed = completedSlotsToday(context, now)

        var slot = PunchSchedule.nextSlot(
            nowMillis = now,
            settings = AppConfig.settings.value,
            skippedDateKeys = AppConfig.skippedDateKey
                .takeIf { it != 0 }
                ?.let { setOf(it) }
                ?: emptySet(),
            completedSlots = completed,
        )

        // 【防自激】如果算出来的正是刚刚触发过的那一班，说明它还在
        // 补执行窗口里。把它当作已完成跳过，让 nextSlot 给出**下一班**。
        if (slot != null && isJustFired(slot)) {
            Log.i(TAG, "跳过刚触发的槽位 target=" + PunchSchedule.formatTime(slot.targetAtMillis))
            val expanded = completed + PunchSchedule.CompletedSlot(
                PunchSchedule.dateKey(slot.targetAtMillis),
                slot.kind,
            )
            slot = PunchSchedule.nextSlot(
                nowMillis = now,
                settings = AppConfig.settings.value,
                skippedDateKeys = AppConfig.skippedDateKey
                    .takeIf { it != 0 }
                    ?.let { setOf(it) }
                    ?: emptySet(),
                completedSlots = expanded,
            )
        }

        slot ?: run {
            Log.i(TAG, "no upcoming slot")
            // 没有可调度项时也要把旧闹钟撤掉，
            // 否则残留的旧闹钟会按旧时间再触发一次。
            readLastScheduled(context)?.let { cancelAll(context, it) }
            return null
        }

        // 【关键】若上一轮闹钟正是同一个班次，说明本班还没真正结束
        // （例如 RETRY 触发后又失败）。这时候直接复用，不要 cancel 再重排：
        // cancel + 重新注册同一组 PendingIntent 会把已经过去的 PREWARM/FIRE
        // 的时间钳到 now，等于立刻再触发一次 —— 又是一条自激路径。
        val previous = readLastScheduled(context)
        if (previous != null &&
            previous.targetAtMillis == slot.targetAtMillis &&
            previous.kind == slot.kind
        ) {
            Log.i(
                TAG,
                "同一班次仍在处理中，保持既有闹钟 target=" +
                    PunchSchedule.formatTime(slot.targetAtMillis),
            )
            return slot
        }

        render(context, slot, previous)
        return slot
    }

    /**
     * 查出"今天已经成功打完的班次"。
     *
     * 只认 SUCCESS 记录：失败的那次不能吃掉当天的闹钟，
     * 否则一次失败就再也不会自动重试了。
     */
    private fun completedSlotsToday(context: Context, now: Long): Set<PunchSchedule.CompletedSlot> {
        // runBlocking 在这里是安全的：
        // 1. scheduleNext 的所有调用点都在后台线程（协程的 IO 派发器、
        //    BroadcastReceiver.goAsync 的协程、Service 的协程）；
        // 2. 查询是本地 SQLite 的两次索引查找，耗时亚毫秒级；
        // 3. 如果这里不用同步方式，就必须把整个 scheduleNext 改成 suspend，
        //    会波及 BootReceiver / PunchAlarmReceiver / KeepAliveService
        //    等一串调用方，改动面远大于收益。
        val rows = runCatching {
            kotlinx.coroutines.runBlocking {
                val dao = com.redmi.autoattend.data.db.AppDatabase.get(context).punchLogDao()
                val startOfToday = PunchSchedule.startOfDay(now, 0)
                listOfNotNull(
                    dao.lastSuccessSince(PunchKind.CLOCK_IN, startOfToday),
                    dao.lastSuccessSince(PunchKind.CLOCK_OUT, startOfToday),
                )
            }
        }.getOrDefault(emptyList())

        val done = rows.map { it.kind to it.startedAt }
        return PunchSchedule.completedSlotsForToday(now, done)
    }

    /**
     * 按 slot 注册三段闹钟：预热闹钟、主闹钟、补执行闹钟。
     *
     * 三段都注册是有意为之：
     * - PREWARM 提前 75 秒，用于点亮屏幕 + 解锁 + 拉钉钉，把准备工作做在前面；
     * - FIRE 在目标时刻，是真正的打卡执行点（若 PREWARM 已成功预热，这里只做点击）；
     * - RETRY 在目标时间 + RETRY_WINDOW_SECONDS，作为最后的兜底。
     */
    private fun render(context: Context, slot: NextPunchSlot, previous: NextPunchSlot?) {
        // 【关键】先取消上一轮的三段闹钟，再注册新的。
        //
        // 早先的实现每次都只注册不取消，于是：
        // 1. 用户改时间（updateSchedule）后，旧的 PREWARM/FIRE/RETRY 仍在系统里，
        //    旧 FIRE 会按旧时间**再执行一次**打卡；
        // 2. 关开关时 cancelAll(context, lastScheduled) 在冷进程里
        //    lastScheduled == null，直接 return，什么也没取消。
        //
        // 现在 lastScheduled 持久化到 prefs，冷进程也能读回来取消。
        previous?.let { prev ->
            cancelAll(context, prev)
            Log.i(TAG, "已取消上一轮闹钟 target=" + PunchSchedule.formatTime(prev.targetAtMillis))
        }

        val am = alarmManager(context)
        val exact = canScheduleExact(context)
        val kindName = slot.kind.name
        val target = slot.targetAtMillis
        val rc = requestCodeFor(slot.kind)

        val prewarmPi = pendingIntent(context, ACTION_PREWARM, rc + 10, kindName, target)
        val firePi = pendingIntent(context, ACTION_FIRE, rc, kindName, target)
        val retryPi = pendingIntent(context, ACTION_RETRY, rc + 20, kindName, target)

        val prewarmAt = slot.triggerAtMillis
        val retryAt = target + PunchSchedule.RETRY_WINDOW_SECONDS * 1000L

        // 【关键】已经过去的时间点**不能再注册**。
        //
        // 旧代码用 maxOf(at, now + 500/1000) 把过去的时间钳到"立刻"，
        // 于是每重排一次就立刻再触发一次 —— 这是另一条自激路径，也是
        // 为什么之前 dumpsys alarm 里会看到 PREWARM/FIRE/RETRY 各有两份。
        //
        // 语义上"已经过去的那一段"本来就不该再触发：
        //   · PREWARM 已过 → 预热窗口没了，服务由 FIRE 拉起即可；
        //   · FIRE 已过但还在补执行窗口 → 用"立即"触发是合理的（补执行）；
        //   · RETRY 已过 → 这一班彻底结束，不注册。
        val now = System.currentTimeMillis()
        val registerNow = now + 500L

        if (prewarmAt > now) {
            setExact(am, exact, prewarmAt, prewarmPi, context)
        } else {
            // 预热闹钟时间已过：不必再注册，避免"钳到立刻"造成的重复执行
            runCatching { am.cancel(prewarmPi) }
        }

        // FIRE：本班目标时刻还没到 → 按目标时刻排；
        // 已经过了但仍在补执行窗口内 → 允许立刻补一次（这是补执行的语义）。
        val fireAt = when {
            slot.targetAtMillis > now -> slot.targetAtMillis
            now - slot.targetAtMillis <= PunchSchedule.RETRY_WINDOW_SECONDS * 1000L -> registerNow
            else -> 0L
        }
        if (fireAt > 0L) {
            setExact(am, exact, fireAt, firePi, context)
        } else {
            runCatching { am.cancel(firePi) }
        }

        if (retryAt > now) {
            setExact(am, exact, retryAt, retryPi, context)
        } else {
            runCatching { am.cancel(retryPi) }
        }

        Log.i(
            TAG,
            "scheduled kind=" + kindName +
                " target=" + PunchSchedule.formatTime(target) +
                " prewarmAt=" + PunchSchedule.formatTime(prewarmAt) +
                " fire=" + (if (fireAt > 0) PunchSchedule.formatTime(fireAt) else "skip") +
                " retry=" + (if (retryAt > now) PunchSchedule.formatTime(retryAt) else "skip") +
                " exact=" + exact,
        )

        // 记录到内存 + prefs，供后续取消（prefs 让冷进程也能取消）
        lastScheduled = slot
        persistLastScheduled(context, slot)
    }

    @Volatile
    var lastScheduled: NextPunchSlot? = null
        private set

    /**
     * 刚刚触发过的槽位（防止同一班次被反复重排）。
     *
     * ## 为什么需要
     *
     * [PunchSchedule.nextSlot] 的语义是"当前时刻之后的下一个打卡点"，
     * 并且为了支持补执行，它会把**仍在补执行窗口内**的已过时间点也算进来
     * （effectiveTrigger = now）。
     *
     * 于是出现这个循环：FIRE 触发 → onReceive 里重排 →
     * nextSlot 又返回**同一个班次**（因为还在窗口内）→
     * 立刻又排一个 FIRE → FIRE 触发 …
     *
     * 正常情况下这个循环会被"成功后跳过已完成班次"打断，
     * 但如果本次执行失败、或者服务因 "已有执行在进行" 被忽略，
     * estimated 就会一直空转到窗口结束。
     *
     * 这里显式记住"这一班已经触发过"，重排时跳过它。
     */
    @Volatile
    private var justFiredTargetAt: Long = 0L

    @Volatile
    private var justFiredKind: String? = null

    /**
     * 打卡流程结束后统一重排下一班（P0-1 修复的配套）。
     *
     * ## 为什么重排从接收器搬到这里
     *
     * 旧实现在 [PunchAlarmReceiver.onReceive] 里重排，PREWARM 一触发就把
     * 本班的 FIRE / RETRY 取消了（详见 AlarmScheduler 顶部注释与
     * PunchAlarmReceiver 的说明），兜底链路直接断掉。
     *
     * 现在改成：接收器只拉服务，**服务执行完毕**（成功/失败/取消）后调这里。
     * 语义上"本班已经结束"才是重排下一班的正确时机。
     *
     * @param markFired 是否把"刚跑完的这一班"标记成已处理。
     *        成功/主动放弃传 true（下次要跳到下一班）；
     *        执行失败传 false，让 nextSlot 在补执行窗口内仍能排回本班，
     *        这样 RETRY 还有机会救一次。
     */
    fun onPunchFinished(context: Context, slot: NextPunchSlot?, markFired: Boolean) {
        runCatching {
            if (markFired && slot != null) {
                markJustFired(slot.targetAtMillis, slot.kind)
            }
            scheduleNext(context)
        }.onFailure { Log.w(TAG, "onPunchFinished 重排失败: " + it.javaClass.simpleName) }
    }

    /**
     * 标记某个槽位刚刚被触发。
     *
     * 只记录"最近一次"，不做长期记账 —— 长期记账由数据库里的
     * SUCCESS 记录负责（见 completedSlotsToday）。
     */
    fun markJustFired(targetAt: Long, kind: com.redmi.autoattend.data.db.PunchKind) {
        justFiredTargetAt = targetAt
        justFiredKind = kind.name
    }

    /** 清掉"刚刚触发"标记（跨天或手动改配置时调用） */
    fun clearJustFired() {
        justFiredTargetAt = 0L
        justFiredKind = null
    }

    private fun isJustFired(slot: NextPunchSlot): Boolean =
        slot.targetAtMillis == justFiredTargetAt && slot.kind.name == justFiredKind

    // ---------- lastScheduled 持久化 ----------

    private const val PREF = "alarm_scheduler"
    private const val KEY_TARGET_AT = "last_target_at"
    private const val KEY_KIND = "last_kind"

    private fun persistLastScheduled(context: Context, slot: NextPunchSlot) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_TARGET_AT, slot.targetAtMillis)
                .putString(KEY_KIND, slot.kind.name)
                .apply()
        }
    }

    private fun readLastScheduled(context: Context): NextPunchSlot? = runCatching {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val target = p.getLong(KEY_TARGET_AT, 0L)
        if (target <= 0L) return@runCatching null
        val kind = runCatching {
            com.redmi.autoattend.data.db.PunchKind.valueOf(
                p.getString(KEY_KIND, null) ?: return@runCatching null
            )
        }.getOrNull() ?: return@runCatching null
        NextPunchSlot(
            triggerAtMillis = target - PunchSchedule.PRE_WARM_SECONDS * 1000L,
            targetAtMillis = target,
            kind = kind,
            isOverride = false,
        )
    }.getOrNull()

    private fun setExact(
        am: AlarmManager,
        canExact: Boolean,
        atMillis: Long,
        pi: PendingIntent,
        context: Context,
    ) {
        val safeAt = maxOf(atMillis, System.currentTimeMillis() + 500)
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeAt, pi)
        } else {
            // 没有精确闹钟权限时降级：setAndAllowWhileIdle 仍能在 Doze 下唤醒，但精度较差。
            // UI 会明确提示用户去开启"闹钟和提醒"权限。
            Log.w(TAG, "no exact alarm permission, falling back to inexact")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeAt, pi)
        }
    }

    /** 当前时刻是否落在某个打卡点的补执行窗口内（供服务判断是否值得启动） */
    fun withinRetryWindow(nowMillis: Long, targetAtMillis: Long): Boolean {
        val delta = nowMillis - targetAtMillis
        return delta >= -PunchSchedule.PRE_WARM_SECONDS * 1000L &&
            delta <= PunchSchedule.RETRY_WINDOW_SECONDS * 1000L
    }
}