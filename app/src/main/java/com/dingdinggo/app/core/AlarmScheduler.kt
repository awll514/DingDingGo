package com.dingdinggo.app.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.exec.PunchAlarmReceiver

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
    private const val ACTION_PREWARM = "com.dingdinggo.app.action.PREWARM"

    /** 目标时刻的主闹钟（真正的兜底触发点） */
    private const val ACTION_FIRE = "com.dingdinggo.app.action.FIRE"

    /** 补执行闹钟（目标时间 + 120 秒窗口内的一次机会） */
    private const val ACTION_RETRY = "com.dingdinggo.app.action.RETRY"

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
        // 【审计 §3.1】这里刻意**不**调用 pi.cancel()。
        //
        // AlarmManager.cancel(pi) 已足以撤销注册。而 PendingIntent.cancel() 是
        // 不可逆的：它会让 PI 本体失效，之后再用 FLAG_UPDATE_CURRENT 取同一个
        // (requestCode, data) 得到的是一个"新建的 PI"。
        //
        // 这带来两个问题：
        // ① 任何持有旧 PI 引用的代码会静默失效（当前没有，但重构时极易踩）；
        // ② 将来若想用 FLAG_NO_CREATE 探测"闹钟是否还注册着"（把健康度卡片
        //    从"算出来的 slot 非空"改成"系统里真的存在"），前提就是 PI 还活着。
        //    现在先取消掉，等于自己堵死那条路。
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

        // 【P0 修复】同一班次的重排判定，必须区分"目标时刻是否还在未来"。
        //
        // ## 旧实现的致命缺陷（真机复现，见 audit/device-p0-alarm-loss.md）
        //
        // 旧代码只要 prefs 里记录的本班次与算出来的 slot 相同就直接 return，
        // 从不重新注册。它比较的是**我们上次想要排什么**，而不是**系统里现在
        // 到底还有没有这个闹钟**。两者一旦分叉就永久卡死：
        //
        //   force-stop / 系统重启 / HyperOS 清理闹钟 → 系统侧三个闹钟全没
        //   → prefs 里 last_target_at 还在 → 每次 scheduleNext 都命中早返回
        //   → 永远不会重新注册 → 当天漏卡
        //
        // 更糟的是 KeepAliveService 每 15 分钟的自检走的也是这个函数，
        // 于是**自愈机制本身被自己挡死了**，跑一整天也救不回来。
        //
        // ## 为什么不能简单地"每次都无条件重排"
        //
        // AlarmScheduler 顶部与 render() 里反复记录过自激事故：
        // 目标时刻已经过去、但仍在补执行窗口内时，render() 会把 FIRE
        // 钳到 now+500ms，于是"重排一次就立刻再触发一次"。
        //
        // ## 现在的判定（两条分支覆盖全部情况）
        //
        //   1. 目标时刻**仍在未来** → 无条件重排。
        //      此时 render() 的 PREWARM / FIRE / RETRY 都排在未来，
        //      重复 setExact 只是覆盖同一个 PendingIntent 的触发时刻，
        //      幂等且无副作用；同时天然修复"系统把闹钟清掉了"的情况。
        //
        //   2. 目标时刻**已过**（处于补执行窗口）→ 只有在"本班确实已经
        //      触发过"时才保持既有闹钟，否则必须重排补一次。
        //      "触发过"由接收器写入的持久化标记给出（markSlotFired），
        //      不会因为进程重启而丢失，所以既不会自激、也不会漏补。
        val previous = readLastScheduled(context)
        val firedTargetAt = readFiredTargetAt(context)
        val firedKindName = readFiredKind(context)

        // 判定抽成纯函数（reusePreviousAlarms），这样 P0 的回归可以被单元测试钉住。
        // 修复前这里是内联 if，只能在真机上复现 —— 回归时没有任何测试会报警。
        val keepExisting = reusePreviousAlarms(
            previousTargetAt = previous?.targetAtMillis ?: 0L,
            previousKind = previous?.kind?.name,
            slotTargetAt = slot.targetAtMillis,
            slotKind = slot.kind.name,
            nowMillis = System.currentTimeMillis(),
            firedTargetAt = firedTargetAt,
            firedKind = firedKindName,
        )

        if (keepExisting) {
            Log.i(
                TAG,
                "同一班次已触发过且处于补执行窗口，保持既有闹钟 target=" +
                    PunchSchedule.formatTime(slot.targetAtMillis),
            )
            return slot
        }

        if (previous != null &&
            previous.targetAtMillis == slot.targetAtMillis &&
            previous.kind == slot.kind
        ) {
            Log.i(
                TAG,
                if (slot.targetAtMillis > System.currentTimeMillis()) {
                    "同一班次但目标时刻仍在未来，幂等重排以确保系统侧闹钟存在 target=" +
                        PunchSchedule.formatTime(slot.targetAtMillis)
                } else {
                    "同一班次尚未触发过，重排以补执行 target=" +
                        PunchSchedule.formatTime(slot.targetAtMillis)
                },
            )
        }

        render(context, slot, previous)
        return slot
    }

    /**
     * 【P0-1 的唯一判定，纯函数】
     *
     * 决定"当算出来的 slot 与上次记录的是同一班次时，是否可以沿用既有闹钟、
     * 跳过重新注册"。
     *
     * ## 为什么必须抽出来
     *
     * 这是本项目最严重缺陷（闹钟永久丢失）的全部判定逻辑。修复前它内联在
     * scheduleNext 里，只能靠真机 force-stop 复现 —— **没有任何单元测试
     * 会在它被改坏时报警**。抽成纯函数后
     * [com.dingdinggo.app.SchedulerSelfHealTest] 可以完整覆盖真值表。
     *
     * ## 判定规则（两条分支，覆盖全部情况）
     *
     * | 场景 | 返回 | 理由 |
     * |---|---|---|
     * | 不同班次 | false | 必须重排 |
     * | 同班次 + 目标时刻在**未来** | **false** | 必须重排。系统侧闹钟可能已被清掉，重排是幂等的 setExact，能自愈 |
     * | 同班次 + 目标时刻已过 + **已触发过** | true | 原来的防自激意图：别把已过的时间钳到 now 再触发一次 |
     * | 同班次 + 目标时刻已过 + **没触发过** | false | 必须重排，否则漏掉补执行 |
     *
     * 关键：**"目标时刻在未来"这一支必须返回 false**。旧实现在这里
     * 无条件返回 true（跳过重排），正是闹钟永久丢失的根因。
     *
     * @param previousTargetAt 上次调度记录的 targetAt（0 表示没有记录）
     * @param previousKind 上次调度记录的班次名
     * @param slotTargetAt 本次算出的 targetAt
     * @param slotKind 本次算出的班次名
     * @param nowMillis 当前时刻（注入，便于测试）
     * @param firedTargetAt 已触发标记的 targetAt（0 表示没有）
     * @param firedKind 已触发标记的班次名
     */
    internal fun reusePreviousAlarms(
        previousTargetAt: Long,
        previousKind: String?,
        slotTargetAt: Long,
        slotKind: String,
        nowMillis: Long,
        firedTargetAt: Long,
        firedKind: String?,
    ): Boolean {
        // 不是同一班次 → 无可沿用
        if (previousTargetAt == 0L || previousKind != slotKind) return false
        if (previousTargetAt != slotTargetAt) return false

        // 同一班次，且目标时刻仍在未来 → **必须重排**（这是自愈的关键）
        val targetStillFuture = slotTargetAt > nowMillis
        if (targetStillFuture) return false

        // 目标时刻已过：只有"确实触发过"才保持既有闹钟（防自激）
        val alreadyFired = firedTargetAt == slotTargetAt &&
            firedKind == slotKind &&
            PunchSchedule.dateKey(firedTargetAt) == PunchSchedule.dateKey(slotTargetAt)

        return alreadyFired
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
                val dao = com.dingdinggo.app.data.db.AppDatabase.get(context).punchLogDao()
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
        //
        // 【P1 修复·消掉 cancel→set 空窗】只有**班次真的变了**才 cancel。
        //
        // 起因（来自独立审计 audit/schedule.md §2.1）：修复 P0 后，"目标时刻
        // 仍在未来就无条件重排"会成为常规路径。若这里仍然无条件
        // cancelAll(previous) 再 set，就会在 PREWARM 已经触发、FIRE 尚未触发
        // 的那 75 秒里，把本班三段闹钟先全部 cancel 掉再重新注册。
        // 这个 cancel→set 之间存在一个真实空窗：进程若恰好在此刻被
        // HyperOS 回收，FIRE / RETRY 就真的没了 —— 等于用一个新 bug
        // 换掉一个旧 bug。
        //
        // 同一班次时三段 PendingIntent 的 (requestCode, data) 完全相同，
        // 直接用 setExact 覆盖触发时刻即可，天然幂等，无需 cancel。
        // 只有换了班次（改时间 / 跨天 / 手动覆盖）才需要撤掉旧的，
        // 否则旧 FIRE 会按旧时间再执行一次打卡 —— 那才是 cancel 存在的原意。
        val sameSlotAsBefore = previous != null &&
            previous.targetAtMillis == slot.targetAtMillis &&
            previous.kind == slot.kind

        if (!sameSlotAsBefore) {
            previous?.let { prev ->
                cancelAll(context, prev)
                Log.i(TAG, "已取消上一轮闹钟 target=" + PunchSchedule.formatTime(prev.targetAtMillis))
            }
        } else {
            Log.i(TAG, "同一班次幂等重排，跳过 cancel 以消除空窗 target=" + PunchSchedule.formatTime(slot.targetAtMillis))
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
        // 已经过了但仍在补执行窗口内 → 只有"尚未触发过 FIRE/RETRY"才立刻补一次，
        // 已经触发过 FIRE 但执行失败时不再把 FIRE 钳到立刻，交给既有的 RETRY 闹钟兜底。
        val alreadyFired = hasSlotFired(context, target, slot.kind)
        val fireAt = when {
            slot.targetAtMillis > now -> slot.targetAtMillis
            shouldRegisterImmediateFire(
                slotTargetAt = slot.targetAtMillis,
                nowMillis = now,
                retryWindowSeconds = PunchSchedule.RETRY_WINDOW_SECONDS,
                alreadyFired = alreadyFired,
            ) -> registerNow
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
    /**
     * 补执行窗口内是否需要把 FIRE 钳到"立即补发"。
     *
     * 只有在目标时刻已过、仍在补执行窗口内、且本班尚未触发过 FIRE/RETRY 时才返回 true；
     * 若本班 FIRE 已经触发过（即使预热/执行刚失败），不得再次立即注册 FIRE，交由 RETRY 闹钟兜底。
     */
    internal fun shouldRegisterImmediateFire(
        slotTargetAt: Long,
        nowMillis: Long,
        retryWindowSeconds: Long,
        alreadyFired: Boolean,
    ): Boolean {
        if (alreadyFired) return false
        if (slotTargetAt > nowMillis) return false
        return nowMillis - slotTargetAt <= retryWindowSeconds * 1000L
    }

    fun onPunchFinished(context: Context, slot: NextPunchSlot?, markFired: Boolean) {
        runCatching {
            if (slot != null) {
                if (markFired) {
                    // 【审计 §2.4】持久化已触发标记，并标记"本班已结案"以跳过到下一班
                    markSlotFired(context, slot.targetAtMillis, slot.kind)
                    markJustFired(slot.targetAtMillis, slot.kind)
                    markSlotDoneOnDate(context, slot.targetAtMillis, slot.kind)
                } else if (isJustFired(slot)) {
                    // 失败或过早被拒时绝不能把本班当作已结案，否则 scheduleNext 会提前取消 RETRY
                    clearJustFired()
                }
            }
            scheduleNext(context)
        }.onFailure { Log.w(TAG, "onPunchFinished 重排失败: " + it.javaClass.simpleName) }
    }

    /** 读取"已触发"标记的 targetAt（0 表示没有）。纯只读，供 scheduleNext 判定用 */
    private fun readFiredTargetAt(context: Context): Long = runCatching {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_FIRED_TARGET_AT, 0L)
    }.getOrDefault(0L)

    /** 读取"已触发"标记的班次名（null 表示没有） */
    private fun readFiredKind(context: Context): String? = runCatching {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_FIRED_KIND, null)
    }.getOrNull()

    /**
     * 标记某个槽位刚刚被触发。
     *
     * 只记录"最近一次"，不做长期记账 —— 长期记账由数据库里的
     * SUCCESS 记录负责（见 completedSlotsToday）。
     */
    fun markJustFired(targetAt: Long, kind: com.dingdinggo.app.data.db.PunchKind) {
        justFiredTargetAt = targetAt
        justFiredKind = kind.name
        firedSlotTargetAt = targetAt
        firedSlotKind = kind.name
    }

    /**
     * 供非本类的调用方（接收器）在触发时持久化，避免依赖进程内状态。
     *
     * 【P1 修复】必须用 commit() 同步落盘，不能用 apply()。
     *
     * 审计结论（audit/schedule.md §2.2）：apply() 是异步落盘，
     * 而 FIRE 触发后进程随时可能被 HyperOS 回收（广播接收者进程被杀很常见）。
     * 一旦落盘没完成，服务侧 hasSlotFired 读到 false → 判定"没触发过"
     * → 重排补执行 → 再打一次。
     *
     * 上班卡会被迟到闸门拦下（无损），但**下班卡的闸门只有下界没有上界**，
     * 重复执行会真的打第二张下班卡 —— 也就是重复打卡。
     *
     * 这里只写几十字节，在 BroadcastReceiver 的 10 秒预算内可忽略，
     * 与 AppConfig.saveLockPassword 已有的"必须 commit 不能 apply"判据一致。
     */
    fun markSlotFired(context: Context, targetAt: Long, kind: com.dingdinggo.app.data.db.PunchKind) {
        // 只记录"FIRE/RETRY 已触发"用于 reusePreviousAlarms 保活 RETRY，
        // 不要写入 justFiredTargetAt —— 否则 FIRE 触发后若 PREWARM/本次失败，
        // onPunchFinished(markFired=false) 调 scheduleNext 会误判本班已结案并取消 RETRY。
        firedSlotTargetAt = targetAt
        firedSlotKind = kind.name
        runCatching {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            p.edit()
                .putLong(KEY_FIRED_TARGET_AT, targetAt)
                .putString(KEY_FIRED_KIND, kind.name)
                .commit()
        }
    }

    /** 清掉"已触发"标记（跨天 / 手动改配置时用）。内存与持久化同时清，避免残留值误命中 */
    fun clearSlotFired(context: Context) {
        clearJustFired()
        firedSlotTargetAt = 0L
        firedSlotKind = null
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_FIRED_TARGET_AT)
                .remove(KEY_FIRED_KIND)
                .commit()
        }
    }

    /**
     * 这一班是否真的触发过（内存优先，回落到 prefs）。
     *
     * 【审计 §2.3】加同日校验做纵深防御。
     *
     * 跨天"误伤"实际不会发生：昨天与今天的 targetAt 相差 86400000ms，
     * 数值天然不等，且外层守卫已限定"同一班次"才会调用本函数。
     * 但 fired 标记本来的设计缺陷是**没有生命周期**——只写不删。
     * 一旦用户在同一天内改了上下班时间又改回来，或未来引入某些
     * 时间戳复用逻辑，就会命中残留值。加一道同日校验成本近乎为零，
     * 却让这个持久状态不再依赖"时间戳天然唯一"这个隐含假设。
     */
    private fun hasSlotFired(context: Context, targetAt: Long, kind: com.dingdinggo.app.data.db.PunchKind): Boolean {
        if (firedSlotTargetAt == targetAt && firedSlotKind == kind.name) return true
        return runCatching {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val firedAt = p.getLong(KEY_FIRED_TARGET_AT, 0L)
            firedAt == targetAt &&
                PunchSchedule.dateKey(firedAt) == PunchSchedule.dateKey(targetAt) &&
                p.getString(KEY_FIRED_KIND, null) == kind.name
        }.getOrDefault(false)
    }

    @Volatile
    private var firedSlotTargetAt: Long = 0L

    @Volatile
    private var firedSlotKind: String? = null

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

    /**
     * 已经**真正触发过**的班次（不是"想排"的班次）。
     *
     * 与 [KEY_TARGET_AT] 的区别是本类 bug 的关键：
     * [KEY_TARGET_AT] 记录的是"我上一次把闹钟排到了哪一班"，
     * 而这个 key 记录的是"哪一班真的被闹钟/蓝牙触发过"。
     *
     * 前者会被用来判断"要不要重排"，一旦系统侧闹钟被清掉就会永久卡死；
     * 后者只用来判断"补执行窗口内这一班还需不需要再补一次"，
     * 语义窄且不会阻止重注册。
     *
     * 必须持久化（不能只放内存）：进程随时可能被 HyperOS 杀掉重启，
     * 只放内存的话重启后会误判成"没触发过"，从而在补执行窗口内
     * 重复执行一次 —— 那就是自激。
     */
    private const val KEY_FIRED_TARGET_AT = "fired_target_at"
    private const val KEY_FIRED_KIND = "fired_kind"
    private const val KEY_DONE_CLOCK_IN_DAY = "done_clock_in_day"
    private const val KEY_DONE_CLOCK_OUT_DAY = "done_clock_out_day"

    private fun markSlotDoneOnDate(
        context: Context,
        targetAt: Long,
        kind: com.dingdinggo.app.data.db.PunchKind,
    ) {
        val key = when (kind) {
            com.dingdinggo.app.data.db.PunchKind.CLOCK_IN -> KEY_DONE_CLOCK_IN_DAY
            com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> KEY_DONE_CLOCK_OUT_DAY
            com.dingdinggo.app.data.db.PunchKind.MANUAL -> return
        }
        val dayKey = PunchSchedule.dateKey(targetAt)
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(key, dayKey)
                .apply()
        }
    }

    /** 查询指定时刻当天上班/下班槽位是否已由真实结案（成功或超窗放弃）处理完 */
    fun readDoneToday(context: Context, nowMillis: Long): Pair<Boolean, Boolean> {
        val today = PunchSchedule.dateKey(nowMillis)
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val inDone = p.getInt(KEY_DONE_CLOCK_IN_DAY, 0) == today
        val outDone = p.getInt(KEY_DONE_CLOCK_OUT_DAY, 0) == today
        return inDone to outDone
    }

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
            com.dingdinggo.app.data.db.PunchKind.valueOf(
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

    /**
     * 系统里是否**真的**注册着本班的 FIRE 闹钟。
     *
     * 【审计 F7 修复】这是"静默失败"的典型缺口。
     *
     * 旧实现里健康度卡片的 `alarmScheduled` 取的是"算出来的 slot 非空"
     * （HealthCheck.kt: `val alarmScheduled = nextSlot != null`），
     * 那是**纯计算**，跟 AlarmManager 里实际有没有闹钟毫无关系。
     *
     * 后果正是 P0「闹钟永久丢失」整整一天没被界面发现的原因：
     * 卡片一直显示"闹钟已注册、下次 09:30"，而系统里一个都没有。
     *
     * 这里用 PendingIntent.FLAG_NO_CREATE 做只读探测：
     * 该 flag 表示"存在就返回、不存在返回 null"，**不会创建**新的 PI，
     * 因此零副作用、可安全地在健康度检查里反复调用。
     *
     * 注意：PI 存在 ≈ 曾经注册过。AlarmManager.cancel 不会销毁 PI 本体
     * （我们刚从 cancelPending 里去掉了 pi.cancel()，正是为了保住这个探测能力），
     * 所以它仍然是目前 Android 上**最接近**"闹钟还在不在"的可用信号。
     * 它不如 dumpsys 精确，但能覆盖本 bug 的场景（PI 都没有 = 肯定没注册）。
     */
    fun isSlotAlarmRegistered(context: Context, slot: NextPunchSlot): Boolean = runCatching {
        val kindName = slot.kind.name
        val target = slot.targetAtMillis
        val rc = requestCodeFor(slot.kind)
        val intent = Intent(context, PunchAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kindName)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, target)
            data = android.net.Uri.parse(
                "autoattend://" + ACTION_FIRE + "/" + kindName + "/" + target,
            )
        }
        val pi = PendingIntent.getBroadcast(
            context,
            rc,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi != null
    }.getOrDefault(false)

    /** 当前时刻是否落在某个打卡点的补执行窗口内（供服务判断是否值得启动） */
    fun withinRetryWindow(nowMillis: Long, targetAtMillis: Long): Boolean {
        val delta = nowMillis - targetAtMillis
        return delta >= -PunchSchedule.PRE_WARM_SECONDS * 1000L &&
            delta <= PunchSchedule.RETRY_WINDOW_SECONDS * 1000L
    }
}