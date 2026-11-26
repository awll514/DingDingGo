package com.dingdinggo.app.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.LateConfirmStore
import com.dingdinggo.app.core.PunchSchedule

/**
 * 接收高优先级迟到确认通知上的「确认打迟到卡」与「今天不打」按钮动作。
 */
class LateConfirmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LateConfirmReceiver"

        const val ACTION_CONFIRM_LATE = "com.dingdinggo.app.action.CONFIRM_LATE_PUNCH"
        const val ACTION_DECLINE_LATE = "com.dingdinggo.app.action.DECLINE_LATE_PUNCH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_CONFIRM_LATE && action != ACTION_DECLINE_LATE) return

        val app = context.applicationContext
        val rawTargetAt = intent.getLongExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, 0L)
        val pending = goAsync()
        Thread({
            try {
                AppConfig.init(app)
                AppConfig.reload()
                LateConfirmStore.init(app)

                val now = System.currentTimeMillis()
                if (rawTargetAt > 0L &&
                    PunchSchedule.dateKey(rawTargetAt) != PunchSchedule.dateKey(now)
                ) {
                    Log.w(TAG, "收到跨天过期的迟到确认操作，忽略并清除通知")
                    PunchNotifier.cancelLateConfirm(app)
                    return@Thread
                }

                when (action) {
                    ACTION_CONFIRM_LATE -> {
                        Log.i(TAG, "收到通知动作：确认打迟到卡")
                        LateConfirmCoordinator.confirmLatePunch(app, rawTargetAt)
                    }

                    ACTION_DECLINE_LATE -> {
                        Log.i(TAG, "收到通知动作：今天不打")
                        LateConfirmCoordinator.declineLatePunch(app, rawTargetAt)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "处理迟到确认动作失败: " + t.javaClass.simpleName, t)
            } finally {
                runCatching { pending.finish() }
            }
        }, "late-confirm-rx").start()
    }
}
