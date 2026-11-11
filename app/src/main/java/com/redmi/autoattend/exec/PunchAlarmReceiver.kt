package com.redmi.autoattend.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.data.db.PunchKind

/**
 * 闹钟触发的静态广播接收器。
 *
 * 关键点（对应验收标准 1）：
 * - 这是**静态注册**在 Manifest 里的 Receiver，App 进程被杀后闹钟仍能唤起它；
 * - onReceive 里只做最轻的事：拿唤醒锁 + 启动前台服务，然后立刻返回；
 * - 不做任何耗时的 UI 自动化动作（BroadcastReceiver 有 10 秒限制）。
 */
class PunchAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PunchAlarmReceiver"

        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_TARGET_AT = "extra_target_at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: PunchKind.CLOCK_IN.name
        val targetAt = intent.getLongExtra(EXTRA_TARGET_AT, 0L)
        val kind = runCatching { PunchKind.valueOf(kindName) }.getOrDefault(PunchKind.CLOCK_IN)

        // 【必须】冷启动时初始化配置。
        //
        // 进程被闹钟冷唤起时 AppConfig.appContext == null，
        // _enabled / _settings 都还是构造默认值（enabled=true, 9:00/18:00, 周一至周五）。
        // scheduleNext 只读 StateFlow 不读 prefs，所以不会崩 ——
        // 但用户设置的时间和生效日会被**默认值覆盖**，闹钟排到错误的时间。
        AppConfig.init(context)
        AppConfig.reload()

        // 【必须】用户关掉开关后不应再执行本次打卡。
        // scheduleNext 内部对"下一次调度"有 enabled 判断，
        // 但对"本次是否执行"没有 —— 少了这一行，关开关当天仍会被打一次卡。
        if (!AppConfig.enabled.value) {
            Log.i(TAG, "自动打卡已关闭，忽略本次闹钟 action=" + action)
            return
        }

        Log.i(TAG, "alarm fired action=" + action + " kind=" + kindName)

        // ==================== 这里**不再重排闹钟**（P0 修复）====================
        //
        // ## 旧实现的致命问题
        //
        // 旧代码在 onReceive 里"先 startService 再 scheduleNext"，后来又改成
        // "先 markJustFired + scheduleNext 再 startService"，并且整段 when 被
        // 复制了两遍（每次闹钟 startService 两次）。但真正的致命点不是顺序：
        //
        //   PREWARM 在 target-75s 触发 → markJustFired(target) →
        //   scheduleNext 认为"这一班已经处理过" → 算出下一班（下班卡）→
        //   render() 先 cancelAll(readLastScheduled()) →
        //   **把同一 targetAt 的 FIRE 和 RETRY 一起取消了**。
        //
        // 结果：三段闹钟实际退化成"一个凭空等待 75 秒的前台服务"。这 75 秒里
        // 进程一旦被 HyperOS 回收，打卡静默失败、连兜底都没有。因为服务自己
        // 会执行打卡，表面功能正常，所以真机上一路都没暴露。
        //
        // ## 现在的分工
        //
        //   · 接收器：**只**负责把服务拉起来，不碰闹钟；
        //   · 服务：在执行结束（成功/失败/取消）后统一调用
        //     AlarmScheduler.onPunchFinished() 重排下一班。
        //
        // 好处：
        //   1. PREWARM 之后 FIRE / RETRY 依然在系统里，兜底链路完整；
        //   2. 重排发生在本班真正结束之后，天然不会自激；
        //   3. 只有一个重排入口，不会出现"谁先谁后"的竞争。
        when {
            action.endsWith("PREWARM") -> {
                // 预热闹钟：提前 75 秒。服务会在这段时间里把"点亮屏幕 → 解锁 →
                // 拉起钉钉 → 进入考勤页"全部做完，到点只点一下按钮。
                startService(context, kind, targetAt, prewarm = true)
            }

            action.endsWith("FIRE") -> {
                // 主闹钟：真正的打卡执行点
                startService(context, kind, targetAt, prewarm = false)
            }

            action.endsWith("RETRY") -> {
                // 补执行：目标时间后 120 秒内的最后机会
                startService(context, kind, targetAt, prewarm = false)
            }
        }
    }

    /**
     * 启动前台服务。
     *
     * Android 12+ 对后台启动 FGS 有限制，但"处理精确闹钟"是官方豁免项之一
     * （targetSdk 36 下从 BroadcastReceiver 启动 FGS 合法）。
     *
     * **关键**：如果仍被拒绝（ForegroundServiceStartNotAllowedException），
     * 那是"静默保活失效"的唯一可观测信号——必须落库 + 发通知，
     * 绝不能只打一行 log 就算了，否则用户永远不知道为什么没打卡。
     */
    private fun startService(
        context: Context,
        kind: PunchKind,
        targetAt: Long,
        prewarm: Boolean,
    ) {
        val intent = Intent(context, PunchForegroundService::class.java).apply {
            this.action = PunchForegroundService.ACTION_EXECUTE
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_TARGET_AT, targetAt)
            putExtra(PunchForegroundService.EXTRA_PREWARM, prewarm)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Throwable) {
            // 只有后台启动被系统拒绝时才走到这里；这是保活链路断掉的确证。
            Log.e(TAG, "startForegroundService 被拒绝: " + e.javaClass.simpleName, e)
            reportStartFailure(context, kind, targetAt, e)
        }
    }

    /**
     * 把"服务起不来"这件事记录下来并通知用户。
     *
     * 写库用 runBlocking：BroadcastReceiver 的 onReceive 有 10 秒预算，
     * 而这里只写一条几十字节的记录，远低于限制。
     */
    private fun reportStartFailure(
        context: Context,
        kind: PunchKind,
        targetAt: Long,
        error: Throwable,
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.redmi.autoattend.data.db.AppDatabase.get(context)
                    .punchLogDao()
                    .insert(
                        com.redmi.autoattend.data.db.PunchLogEntity(
                            scheduledAt = if (targetAt > 0) targetAt else now,
                            startedAt = now,
                            finishedAt = now,
                            kind = kind,
                            outcome = com.redmi.autoattend.data.db.PunchOutcome.FAILURE,
                            channel = "none",
                            failedStage = "启动执行服务",
                            message = "系统拒绝启动前台服务：" + error.javaClass.simpleName +
                                "。通常是 HyperOS 后台管控过严，请检查自启动与省电设置。",
                        )
                    )
            }
        }.onFailure { Log.e(TAG, "写失败记录也失败了: " + it.javaClass.simpleName) }

        // 用高优先级通知告知用户，避免静默失败
        runCatching {
            com.redmi.autoattend.exec.PunchNotifier.notifyStartBlocked(context, kind)
        }
    }
}