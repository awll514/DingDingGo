package com.dingdinggo.app.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.dingdinggo.app.BuildConfig
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.data.db.PunchKind

/**
 * 【仅 debug 构建】真机联调用的受控入口。
 *
 * 支持两个动作：
 *
 * 1. [ACTION_SEED] 种入锁屏密码。
 *    锁屏密码是 EncryptedSharedPreferences 里的密文，密钥由 AndroidKeyStore 持有，
 *    adb 无法直接写 prefs（密文搬不过去，密钥不会跟着走）。
 *    唯一正确的写入路径是 [AppConfig.saveLockPassword]，它做 commit + 回读校验。
 *
 * 2. [ACTION_PUNCH] 触发一次真实打卡执行（等价于点「立即手动打卡」）。
 *    真机上 App 的玻璃控件会吞掉 adb 的合成点击，无法点按验证主链路，
 *    因此提供这个等价入口。它调用的是**同一条** startForegroundService 路径，
 *    不绕过任何安全闸门。
 *
 * ## 安全约束
 *
 * · 只存在于 debug 构建（见 app/src/debug/AndroidManifest.xml）；
 * · 密码只从 extra 读，绝不写日志；
 * · 只回传成功/失败，不回显密码；
 * · 打卡动作必须显式传 kind 与 targetAt，不会自己猜。
 */
class DebugPasswordSeedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugPwdSeed"
        const val ACTION_SEED = "com.dingdinggo.app.debug.SEED_PASSWORD"
        const val ACTION_PUNCH = "com.dingdinggo.app.debug.TRIGGER_PUNCH"

        /**
         * 修改排班时间的调试入口（仅供真机验证）。
         *
         * ## 为什么必须走这个入口（一次真实事故）
         *
         * 为验证"打卡时间窗内扫描是否活跃"，需要临时改下班时间。
         * 第一次的做法是直接覆写 `shared_prefs/autoattend_prefs.xml` ——
         * **那把用户的全部配置清空了**（文件变 0 字节）。
         *
         * 原因：SharedPreferences 在进程内有缓存，直接改磁盘文件与内存
         * 不一致，App 下一次写入就把文件覆盖回缓存内容。
         *
         * 所以必须走 [AppConfig.updateSchedule]：它同时更新内存 StateFlow
         * 与磁盘，是唯一安全的写入路径。
         */
        const val ACTION_SET_SCHEDULE = "com.dingdinggo.app.debug.SET_SCHEDULE"

        const val EXTRA_PASSWORD = "password"
        const val EXTRA_KIND = "kind"
        const val EXTRA_CLOCK_IN = "clock_in_minute"
        const val EXTRA_CLOCK_OUT = "clock_out_minute"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 纵深防御：即使 debug manifest 的 exported 声明
        // 被误合并进 release 构建，这个入口也不会真的改密码 / 打卡。
        //
        // 该 receiver 在 debug manifest 里是 android:exported="true" 且
        // **没有任何权限校验**，任意本机 App 只要发一条广播就能：
        //   · SEED_PASSWORD → 改写锁屏密码 → 此后永久漏打卡；
        //   · TRIGGER_PUNCH → 直接拉起真实打卡 → 重复打卡。
        //
        // release manifest 目前确实不含该组件（source set 隔离生效），
        // 但那是一条"依赖构建配置正确"的单点假设。加这一道运行时断言，
        // 让它在 release 包里也失效，是被动防御而不是重复保护。
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "非 debug 构建，拒绝执行调试入口 action=" + intent.action)
            return
        }

        when (intent.action) {
            ACTION_SEED -> seedPassword(context, intent)
            ACTION_PUNCH -> triggerPunch(context, intent)
            ACTION_SET_SCHEDULE -> setSchedule(context, intent)
            else -> Log.w(TAG, "未知 action: " + intent.action)
        }
    }

    private fun seedPassword(context: Context, intent: Intent) {
        val pwd = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (pwd.isEmpty()) {
            Log.w(TAG, "seed: 缺少 password extra")
            return
        }
        AppConfig.init(context)
        val result: Result<Unit> = AppConfig.saveLockPassword(pwd)
        // 只记录成功/失败，绝不记录密码本身
        Log.i(TAG, "seed password: " + if (result.isSuccess) "OK" else "FAILED")
    }

    /**
     * 触发一次打卡，走与「立即手动打卡」完全相同的服务入口。
     *
     * 时间闸门、外勤闸门、结果判定全部照常执行 —— 这里只是省掉一次触摸。
     */
    /**
     * 修改上下班时间（真机验证专用）。
     *
     * 走 [AppConfig.updateSchedule] 正规入口，同时更新内存与磁盘 ——
     * 直接改 prefs 文件会被 SharedPreferences 的进程内缓存覆盖，
     * 这也正是本次事故的根因（见 ACTION_SET_SCHEDULE 注释）。
     */
    private fun setSchedule(context: Context, intent: Intent) {
        AppConfig.init(context)
        val inMin = intent.getIntExtra(EXTRA_CLOCK_IN, -1)
        val outMin = intent.getIntExtra(EXTRA_CLOCK_OUT, -1)
        if (inMin !in 0..1439 || outMin !in 0..1439) {
            Log.w(TAG, "setSchedule: 分钟数非法 in=" + inMin + " out=" + outMin)
            return
        }
        val current = AppConfig.settings.value
        AppConfig.updateSchedule(
            current.copy(clockInMinute = inMin, clockOutMinute = outMin),
        )
        Log.i(
            TAG,
            "schedule updated: in=" + PunchSchedule.formatTime(
                PunchSchedule.startOfDay(System.currentTimeMillis()) + inMin * 60_000L,
            ) + " out=" + PunchSchedule.formatTime(
                PunchSchedule.startOfDay(System.currentTimeMillis()) + outMin * 60_000L,
            ),
        )
    }

    private fun triggerPunch(context: Context, intent: Intent) {
        AppConfig.init(context)
        val kindName = intent.getStringExtra(EXTRA_KIND)
        val kind = runCatching { PunchKind.valueOf(kindName.orEmpty()) }
            .getOrDefault(PunchKind.CLOCK_OUT)

        // 目标时刻必须用「该班次的计划时刻」，不能用 now —— 否则上班卡闸门永远放行。
        val settings = AppConfig.settings.value
        val startOfDay = PunchSchedule.startOfDay(System.currentTimeMillis())
        val minute = when (kind) {
            com.dingdinggo.app.data.db.PunchKind.CLOCK_OUT -> settings.clockOutMinute
            else -> settings.clockInMinute
        }
        val targetAt = startOfDay + minute * 60_000L

        val svc = Intent(context, PunchForegroundService::class.java).apply {
            action = PunchForegroundService.ACTION_EXECUTE
            putExtra(PunchAlarmReceiver.EXTRA_KIND, kind.name)
            putExtra(PunchAlarmReceiver.EXTRA_TARGET_AT, targetAt)
        }
        Log.i(
            TAG,
            "trigger punch: kind=" + kind.name +
                " target=" + PunchSchedule.formatTime(targetAt),
        )
        runCatching {
            ContextCompat.startForegroundService(context, svc)
        }.onFailure { Log.w(TAG, "启动打卡服务失败: " + it.javaClass.simpleName) }
    }
}