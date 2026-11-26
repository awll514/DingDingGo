package com.dingdinggo.app.exec

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dingdinggo.app.MainActivity
import com.dingdinggo.app.core.AlarmScheduler
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.LateConfirmStore
import com.dingdinggo.app.core.NextPunchSlot
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.AppDatabase
import com.dingdinggo.app.data.db.PunchKind
import com.dingdinggo.app.data.db.PunchLogEntity
import com.dingdinggo.app.data.db.PunchOutcome
import java.util.concurrent.Executors

/**
 * 早上首次触发迟到确认的统一编排器。
 *
 * 协调：高优先级通知 + 亮屏已解锁即弹系统悬浮窗（锁屏期则在下次解锁自动补弹）+
 * 「确认打迟到卡」显式放行与「今天不打」落库收尾。
 */
object LateConfirmCoordinator {

    private const val TAG = "LateConfirm"
    const val EXTRA_OPEN_LATE_CONFIRM = "extra_open_late_confirm"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "late-confirm-worker").apply { isDaemon = true }
    }

    /**
     * 发出迟到确认通知；若当前屏幕亮着且未锁屏则同步弹出确认弹窗。
     *
     * @return true 表示已经在当前屏幕上展示或拉起了可见交互弹窗（服务收尾应避免再灭屏）
     */
    fun triggerLateConfirmPrompt(
        context: Context,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
    ): Boolean {
        val app = context.applicationContext
        PunchNotifier.notifyLateConfirm(app, plannedTargetAt, lateSeconds, source)

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val awakeAndUnlocked = runCatching { pm.isInteractive && !km.isKeyguardLocked }
            .getOrDefault(false)

        if (!awakeAndUnlocked) {
            Log.i(TAG, "当前处于锁屏或息屏状态，已发高优先级通知，等待下次解锁再自动弹出确认窗")
            return false
        }

        LateConfirmStore.markOverlayShown(app)
        return showPromptOnUnlockedScreen(app, plannedTargetAt, lateSeconds, source)
    }

    /** 若锁屏期间错过弹窗且仍在 15 分钟有效期内，解锁时立即补弹一次确认弹窗 */
    fun showOverlayOnUnlockIfPending(context: Context): Boolean {
        val app = context.applicationContext
        LateConfirmStore.init(app)
        val pending = LateConfirmStore.shouldAutoShowOverlayOnUnlock(app, System.currentTimeMillis())
            ?: return false
        LateConfirmStore.markOverlayShown(app)
        Log.i(TAG, "解锁后自动补弹迟到确认弹窗 target=" + pending.plannedTargetAt)
        return showPromptOnUnlockedScreen(
            app = app,
            plannedTargetAt = pending.plannedTargetAt,
            lateSeconds = pending.lateSeconds,
            source = pending.source,
        )
    }

    private fun showPromptOnUnlockedScreen(
        app: Context,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
    ): Boolean {
        val overlayShown = LateConfirmOverlay.show(
            context = app,
            plannedTargetAt = plannedTargetAt,
            lateSeconds = lateSeconds,
            source = source,
            onConfirm = { confirmLatePunch(app, plannedTargetAt) },
            onDecline = { declineLatePunch(app, plannedTargetAt) },
        )
        if (overlayShown) return true

        // 无悬浮窗权限时尝试直接打开应用内液态玻璃确认弹窗
        return openMainActivityForConfirm(app)
    }

    private fun openMainActivityForConfirm(app: Context): Boolean {
        val pkg = app.packageName
        if (RootShell.isAvailable()) {
            val out = RootShell.exec(
                "am start -n " + pkg + "/com.dingdinggo.app.MainActivity" +
                    " --ez " + EXTRA_OPEN_LATE_CONFIRM + " true",
                timeoutMs = 8_000L,
            )
            if (!out.contains("Error") && !out.contains("Exception")) {
                return true
            }
        }
        return runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_LATE_CONFIRM, true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                },
            )
            true
        }.getOrDefault(false)
    }

    /** 用户明确确认「确认打迟到卡」：清掉提示并显式带 EXTRA_ALLOW_LATE=true 启动打卡服务 */
    fun confirmLatePunch(context: Context, plannedTargetAt: Long) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        runCatching {
            AppConfig.init(app)
            AppConfig.reload()
            LateConfirmStore.init(app)
        }
        PunchNotifier.cancelLateConfirm(app)
        LateConfirmOverlay.hide()
        LateConfirmStore.markConfirmed(app, now)

        val targetAt = if (plannedTargetAt > 0L) {
            plannedTargetAt
        } else {
            PunchNotifier.plannedTargetAt(now, PunchKind.CLOCK_IN, AppConfig.settings.value)
        }

        val intent = Intent(app, PunchForegroundService::class.java).apply {
            action = PunchForegroundService.ACTION_EXECUTE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, PunchKind.CLOCK_IN.name)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, targetAt)
            putExtra(PunchForegroundService.EXTRA_PREWARM, false)
            putExtra(PunchForegroundService.EXTRA_SOURCE, PunchForegroundService.SOURCE_LATE_CONFIRMED)
            putExtra(PunchForegroundService.EXTRA_ALLOW_LATE, true)
        }

        try {
            ContextCompat.startForegroundService(app, intent)
            Log.i(TAG, "用户确认打迟到卡，已拉起执行服务 target=" + PunchSchedule.formatTime(targetAt))
        } catch (e: Throwable) {
            Log.e(TAG, "确认打迟到卡后启动服务失败: " + e.javaClass.simpleName, e)
            runCatching { PunchNotifier.notifyStartBlocked(app, PunchKind.CLOCK_IN) }
        }
    }

    /** 用户明确选择「今天不打」：记录已跳过并把排班顺延至晚上下班打卡 */
    fun declineLatePunch(context: Context, plannedTargetAt: Long) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        runCatching {
            AppConfig.init(app)
            AppConfig.reload()
            LateConfirmStore.init(app)
        }
        PunchNotifier.cancelLateConfirm(app)
        LateConfirmOverlay.hide()
        LateConfirmStore.markDeclined(app, now)

        val targetAt = if (plannedTargetAt > 0L) {
            plannedTargetAt
        } else {
            PunchNotifier.plannedTargetAt(now, PunchKind.CLOCK_IN, AppConfig.settings.value)
        }

        ioExecutor.execute {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    AppDatabase.get(app).punchLogDao().insert(
                        PunchLogEntity(
                            scheduledAt = targetAt,
                            startedAt = now,
                            finishedAt = now,
                            kind = PunchKind.CLOCK_IN,
                            outcome = PunchOutcome.CANCELLED,
                            channel = "none",
                            failedStage = null,
                            message = "用户确认今天不打迟到卡，已跳过今日上班打卡",
                        ),
                    )
                }
            }.onFailure {
                Log.w(TAG, "写入拒绝迟到卡日志失败: " + it.javaClass.simpleName)
            }

            runCatching {
                val slot = NextPunchSlot(
                    triggerAtMillis = targetAt - PunchSchedule.PRE_WARM_SECONDS * 1000L,
                    targetAtMillis = targetAt,
                    kind = PunchKind.CLOCK_IN,
                )
                AlarmScheduler.onPunchFinished(app, slot, markFired = true)
            }.onFailure {
                Log.w(TAG, "顺延下一次闹钟失败: " + it.javaClass.simpleName)
            }
            Log.i(TAG, "用户已选择今天不打迟到卡，上班时段已结案并顺延至下一班")
        }
    }
}
