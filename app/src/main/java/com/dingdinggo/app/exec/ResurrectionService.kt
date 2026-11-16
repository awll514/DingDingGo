package com.dingdinggo.app.exec

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 后台自拉起（滑掉进程后自动复活）。
 *
 * ## 问题
 *
 * 你在多任务里把 App 滑掉之后，进程就没了；HyperOS 也不会主动重启它。
 * 结果：闹钟虽然还在系统里，但触发时没有进程承接 → 打卡静默失败。
 *
 * ## 三层复活机制（按可靠性排序）
 *
 * ### 第 1 层：AlarmManager 心跳（最可靠）
 * 注册一个**精确闹钟**，每 10 分钟唤醒一次并拉起保活服务。
 * - 精确闹钟的 PendingIntent 是**进程无关**的：进程死了系统照样发；
 * - 这是本方案的核心，不依赖 JobScheduler 的配额。
 *
 * ### 第 2 层：JobScheduler 周期任务
 * 每 15 分钟一次，用 `setPersisted(true)` 在重启后也保留。
 * 作为精确闹钟被系统限流时的补充。
 *
 * ### 第 3 层：前台服务 START_STICKY
 * KeepAliveService 已经是 START_STICKY，被 LMK 杀掉后系统会尝试重启。
 *
 * ## root 增强
 *
 * 有 root 时可以把 App 加进系统的**待机白名单**（deviceidle whitelist），
 * 大幅降低被冻结的概率。这个已在 RootShell 里做过。
 *
 * ## 诚实边界
 *
 * 用户在系统里主动「强行停止」App 时，**所有机制都会失效** ——
 * 这是 Android 的安全设计（force-stop 会清掉所有闹钟与 Job）。
 * 那种情况只能手动再打开一次 App。
 */
class ResurrectionService : JobService() {

    companion object {
        private const val TAG = "Resurrect"

        const val JOB_ID = 7101
        const val ALARM_REQUEST = 7201
        const val ACTION_HEARTBEAT = "com.dingdinggo.app.action.HEARTBEAT"

        /** 心跳间隔：10 分钟 */
        private const val HEARTBEAT_INTERVAL_MS = 10 * 60 * 1000L

        /** JobScheduler 周期：15 分钟 */
        private const val JOB_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * 安装所有复活机制。
         *
         * ## 【实测修订】原来这里注释写着"幂等，不会堆积"，但真机数据推翻了它
         *
         * `dumpsys jobscheduler` 显示 `schedulePersisted()` 被调用**上百次**，
         * `ResurrectionService` 的 `launches: 50 / Starts: 0`。
         *
         * 原因：本函数在两条高频路径上被调用 ——
         *   · `HeartbeatReceiver`（每 10 分钟）→ `install()`；
         *   · `onStartJob` → `installHeartbeat()`。
         *
         * 而 `install()` 每次都无条件重排心跳**和** Job。
         * `JobScheduler.schedule()` 对同一个 JobId 重复调用确实不会堆积
         * （这点原注释没错），但它会**重置该 Job 的执行时间表** ——
         * 于是 Job 永远被推到"15 分钟后"，可能长期得不到执行，
         * 同时留下大量调度记录。
         *
         * 现在拆开语义：
         *   · 心跳每次都要重排（自循环必需）；
         *   · Job 只在"尚未调度"时排一次，已在运行时不动它。
         */
        fun install(context: Context) {
            installHeartbeat(context)
            ensureJobScheduled(context)
        }

        /** 第 1 层：AlarmManager 心跳 */
        private fun installHeartbeat(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = heartbeatIntent(context)

            val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 用 setExactAndAllowWhileIdle：即使在 Doze 里也会被唤醒。
                    // 这是整个复活机制最关键的一行。
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi,
                    )
                } else {
                    am.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi,
                    )
                }
                Log.i(TAG, "心跳闹钟已注册，间隔 " + (HEARTBEAT_INTERVAL_MS / 60_000) + " 分钟")
            }.onFailure {
                Log.w(TAG, "心跳闹钟注册失败: " + it.javaClass.simpleName)
            }
        }

        private fun heartbeatIntent(context: Context): PendingIntent {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * 确保 Job 已调度（幂等，不重置已有任务）。
         *
         * 与原来"每次 install 都 schedule"的区别：
         * 用 `getPendingJob(JOB_ID)` 先查是否已在调度中，在就不再动它。
         * 这样 Job 的周期计时不会被每次心跳重置。
         */
        private fun ensureJobScheduled(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val existing = runCatching { js.getPendingJob(JOB_ID) }.getOrNull()
            if (existing != null) {
                Log.i(TAG, "保活 Job 已在调度中，跳过重复注册")
                return
            }
            installJob(context)
        }

        /** 第 2 层：JobScheduler 周期任务 */
        private fun installJob(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            val component = ComponentName(context, ResurrectionService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(JOB_INTERVAL_MS)
                // 不需要网络，纯本地保活
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                // 允许在 Doze 里执行
                .setRequiresDeviceIdle(false)
                .setPersisted(true)
                .build()

            runCatching {
                val result = js.schedule(info)
                Log.i(TAG, "保活 Job 已注册，result=" + result)
            }.onFailure { Log.w(TAG, "Job 注册失败: " + it.javaClass.simpleName) }
        }

        /** 取消所有复活机制（用户关掉总开关时调用） */
        fun uninstall(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(heartbeatIntent(context))
            }
            runCatching {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                js.cancel(JOB_ID)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "JobScheduler 触发保活")
        resurrect()
        // 重排下一次心跳（Job 触发时把心率也补上）
        installHeartbeat(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    /** 把保活服务重新拉起来 */
    private fun resurrect() {
        runCatching {
            KeepAliveService.start(this)
            Log.i(TAG, "已尝试拉起保活服务")
        }.onFailure { Log.w(TAG, "拉起失败: " + it.javaClass.simpleName) }
    }
}

/**
 * 心跳广播接收器。
 *
 * 每次心跳做三件事：
 * 1. 复活保活服务；
 * 2. 重排下一次心跳（形成自循环）；
 * 3. 检查闹钟是否还在（防止系统清理后静默失效）。
 */
class HeartbeatReceiver : android.content.BroadcastReceiver() {

    companion object {
        /** 最后一次心跳的时刻（持久化，跨进程存活） */
        const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"

        /** 心跳共享 prefs 名 */
        const val PREFS = "resurrection"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (action != ResurrectionService.ACTION_HEARTBEAT) return

        Log.i("Resurrect", "收到心跳，开始复活")

        // 0. 记录心跳时刻，用于检测"守护中断"（见下方说明）
        //
        // ## 为什么需要这个
        //
        // 真机验证确认了一个此前完全没有覆盖的失效场景：
        // 用户「强行停止」应用后，包进入 FLAG_STOPPED 状态
        // （dumpsys package 显示 stopped=true）。
        //
        // 在该状态下 **Android 不会向本应用投递任何广播**，
        // 即使闹钟完好地留在系统里。实测证据：
        //     am broadcast -a ...HEARTBEAT -n .../HeartbeatReceiver
        //     → "Broadcast completed: result=0"（0 = 无接收者）
        //     → 进程完全没有被拉起
        // 同时 JobScheduler 的 job 也已被系统删除
        //     → "Could not find job 7101 in package ..."
        //
        // 后果：**所有保活机制永久失效，而应用无从自知**
        // （能运行时 stopped 标志已被系统清除，自己读不到：
        //   dumpsys package 显示 flags=0x0，不含 FLAG_STOPPED）。
        //
        // 唯一可靠的间接信号是"心跳间隔异常"：正常每 10 分钟一次，
        // 若下次醒来发现距上次已过很久，说明中间断过。
        // 持久化记录，供 HealthCheck 展示给用户。
        runCatching {
            context.getSharedPreferences("resurrection", Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
                .commit()
        }

        // 1. 拉起保活服务
        runCatching { KeepAliveService.start(context) }

        // 2. 重排下一次心跳（自循环，不依赖服务存活）
        runCatching { ResurrectionService.install(context) }

        // 3. 顺便确认无障碍还在
        runCatching { AccessibilityKeeper.ensureEnabled(context) }

    }
}