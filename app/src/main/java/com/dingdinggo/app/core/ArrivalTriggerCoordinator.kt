package com.dingdinggo.app.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.dingdinggo.app.data.db.AppDatabase
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.exec.LateConfirmCoordinator
import com.dingdinggo.app.exec.PunchAlarmReceiver
import com.dingdinggo.app.exec.PunchForegroundService
import com.dingdinggo.app.exec.PunchNotifier
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * 统一的「到岗触发」协调器（蓝牙考勤机 + 公司 Wi-Fi 共用）。
 *
 * 职责：
 * 1. 共享 60 秒冷却，避免同时检测到公司 Wi-Fi 与蓝牙考勤机时重复拉起打卡服务；
 * 2. 查询 Room 今日已完成班次并调用 [BtPunchRule.decide] 做红线判定；
 * 3. 若命中早到/正点窗口或下班窗口，立即拉起 [PunchForegroundService]；
 * 4. 若在早上首次撞上上班迟到边界，原子占位今日唯一一次迟到确认机会并触发通知/弹窗。
 */
object ArrivalTriggerCoordinator {

    private const val TAG = "ArrivalCoordinator"

    /** 蓝牙与 Wi-Fi 共享的触发冷却窗口（60 秒） */
    const val MIN_TRIGGER_INTERVAL_MS = 60_000L

    private val triggerLock = Any()

    fun onCompanyArrival(
        context: Context,
        source: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(triggerLock) {
            val app = context.applicationContext
            runCatching {
                AppConfig.init(app)
                AppConfig.reload()
                BtTriggerStore.init(app)
                WifiTriggerStore.init(app)
                LateConfirmStore.init(app)
            }.onFailure {
                Log.e(TAG, "初始化配置失败: " + it.javaClass.simpleName)
                return
            }

            // 共享冷却：避免 Wi-Fi 与蓝牙几乎同时命中导致并发/二次拉起
            val lastAny = maxOf(
                BtTriggerStore.lastTriggerAt.value,
                WifiTriggerStore.lastTriggerAt.value,
            )
            if (lastAny > 0L && nowMillis - lastAny < MIN_TRIGGER_INTERVAL_MS) {
                Log.i(TAG, "距上次到岗触发不足冷却窗口，忽略 source=" + source)
                return
            }

            val settings = AppConfig.settings.value
            val done = runCatching {
                runBlocking {
                    val dao = AppDatabase.get(app).punchLogDao()
                    val startOfToday = PunchSchedule.startOfDay(nowMillis, 0)
                    listOfNotNull(
                        dao.lastSuccessSince(PunchKind.CLOCK_IN, startOfToday),
                        dao.lastSuccessSince(PunchKind.CLOCK_OUT, startOfToday),
                    )
                }
            }.getOrDefault(emptyList())

            val clockInDone = done.any { it.kind == PunchKind.CLOCK_IN }
            val clockOutDone = done.any { it.kind == PunchKind.CLOCK_OUT }

            val decision = BtPunchRule.decide(
                nowMillis = nowMillis,
                settings = settings,
                clockInDoneToday = clockInDone,
                clockOutDoneToday = clockOutDone,
                isActiveDay = PunchSchedule.isActiveDay(nowMillis, settings),
                skippedToday = AppConfig.isSkipped(PunchSchedule.dateKey(nowMillis)),
                enabled = AppConfig.enabled.value,
                earlyWindowMinutes = BtTriggerStore.earlyWindowMinutes.value,
            )

            if (decision.shouldPunch) {
                val kind = decision.kind ?: return
                val summary = if (source == PunchForegroundService.SOURCE_WIFI) {
                    decision.message.replace("考勤机", "公司 Wi-Fi")
                } else {
                    decision.message
                }
                markSourceTriggered(source, nowMillis, summary)
                Log.i(
                    TAG,
                    "到岗触发打卡 source=" + source + " action=" + decision.action +
                        " kind=" + kind + " msg=" + summary,
                )
                launchPunch(app, kind, decision.punchAtMillis, decision.configuredTargetMillis, source)
                return
            }

            if (decision.action == BtPunchRule.Action.SKIP_TOO_LATE) {
                val dayStart = PunchSchedule.startOfDay(nowMillis)
                val clockInTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockInMinute.toLong())
                val clockOutTarget = dayStart + TimeUnit.MINUTES.toMillis(settings.clockOutMinute.toLong())
                val promptedDay = LateConfirmStore.promptedDateKey(app, nowMillis)

                if (LateConfirmRule.shouldPromptLateConfirm(
                        kind = PunchKind.CLOCK_IN,
                        nowMillis = nowMillis,
                        clockInTargetMillis = clockInTarget,
                        clockOutTargetMillis = clockOutTarget,
                        clockInDoneToday = clockInDone,
                        promptedDateKey = promptedDay,
                    )
                ) {
                    val lateSeconds = ((nowMillis - clockInTarget) / 1000L).coerceAtLeast(0L)
                    if (LateConfirmStore.tryClaimPromptToday(
                            context = app,
                            nowMillis = nowMillis,
                            plannedTargetAt = clockInTarget,
                            lateSeconds = lateSeconds,
                            source = source,
                        )
                    ) {
                        markSourceTriggered(source, nowMillis, "已过上班时间，等待确认是否打迟到卡")
                        Log.i(TAG, "首次迟到触发确认弹窗/通知 source=" + source + " lateSec=" + lateSeconds)
                        LateConfirmCoordinator.triggerLateConfirmPrompt(
                            context = app,
                            plannedTargetAt = clockInTarget,
                            lateSeconds = lateSeconds,
                            source = source,
                        )
                        return
                    }
                }
                Log.i(TAG, "已过迟到边界且今日已询问或超出确认窗口，静默忽略 source=" + source)
                return
            }

            Log.i(
                TAG,
                "到岗决策跳过 source=" + source + " action=" + decision.action + " msg=" + decision.message,
            )
        }
    }

    private fun markSourceTriggered(source: String, nowMillis: Long, message: String) {
        when (source) {
            PunchForegroundService.SOURCE_WIFI -> WifiTriggerStore.markTriggered(nowMillis, message)
            else -> BtTriggerStore.markTriggered(nowMillis, message)
        }
    }

    private fun launchPunch(
        context: Context,
        kind: PunchKind,
        punchAtMillis: Long,
        gateTarget: Long,
        source: String,
    ) {
        val intent = Intent(context, PunchForegroundService::class.java).apply {
            action = PunchForegroundService.ACTION_EXECUTE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, punchAtMillis)
            putExtra(PunchForegroundService.EXTRA_PREWARM, false)
            putExtra(PunchForegroundService.EXTRA_SOURCE, source)
            putExtra(PunchForegroundService.EXTRA_BT_GATE_TARGET_AT, gateTarget)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "已拉起打卡服务 source=" + source + " kind=" + kind.name + " target=" + punchAtMillis)
        } catch (e: Throwable) {
            Log.e(TAG, "startForegroundService 被拒绝: " + e.javaClass.simpleName, e)
            runCatching {
                PunchNotifier.notifyStartBlocked(context, kind)
            }
        }
    }
}
