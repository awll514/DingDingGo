package com.redmi.autoattend.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.redmi.autoattend.core.AlarmScheduler
import com.redmi.autoattend.core.AppConfig

/**
 * 开机 / 时间变更 / 应用更新后重排闹钟。
 *
 * 这是"闹钟不丢"的关键：Android 在重启后会清空所有 AlarmManager 闹钟，
 * 必须在 BOOT_COMPLETED 里重新注册（对应验收标准 1 与需求 F5）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "received " + action)

        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!relevant) return

        // 【启动性能关键修正】onReceive 跑在**主线程**，而下面这几件事全是重活：
        //
        //   · AlarmScheduler.scheduleNext()  → runBlocking 查 Room + 注册 3 个闹钟
        //   · RootShell.isAvailable()        → fork su 进程（实测 300~400ms）
        //   · AccessibilityKeeper.ensureEnabled() → 两次 su 调用 + Thread.sleep(1200)
        //
        // 真机日志证据（冷启动 182 帧被跳过，主线程被占 1.5 秒）：
        //   36.362 RootShell: root available = true
        //   37.718 A11yKeeper: 无障碍已通过 root 重新启用
        //   37.722 Looper: PerfMonitor longMsg ... wall=1526ms
        //   37.726 ActivityThread: HardwareRenderer preload done   ← 到这才开始画界面
        //
        // 也就是说：**用户点图标之后，进程先花 1.5 秒做开机广播的事，
        // 才开始画第一帧界面**。这就是"启动卡"的真正原因。
        //
        // 注意这里不只是冷启动的问题：本进程在启动时收到了 LOCKED_BOOT_COMPLETED，
        // 说明系统把这次冷启动和开机广播合并投递了。
        //
        // 修法：用 goAsync() 把工作移到后台线程。BroadcastReceiver 的
        // goAsync() 允许在 onReceive 返回后继续干活（最多约 10 秒），
        // 这是处理"必须在广播里做但耗时"的标准做法。
        val pending = goAsync()
        Thread {
            try {
                AppConfig.init(context)
                AppConfig.reload()

                runCatching {
                    AlarmScheduler.scheduleNext(context)
                    Log.i(TAG, "闹钟已重排")
                }.onFailure {
                    Log.e(TAG, "重排失败: " + it.javaClass.simpleName)
                }

                // 重启后也要恢复保活链路：
                // - 前台服务（Android 15+ 对 BOOT 起的 FGS 有类型限制，所以只在后台起）
                // - 心跳闹钟 + JobScheduler
                // - 无障碍自愈（最慢，放最后，不挡住前面几项）
                runCatching { KeepAliveService.start(context) }
                runCatching { ResurrectionService.install(context) }
                runCatching { AccessibilityKeeper.ensureEnabled(context) }
            } catch (t: Throwable) {
                Log.e(TAG, "后台处理失败: " + t.javaClass.simpleName)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}