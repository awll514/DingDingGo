package com.redmi.autoattend.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.redmi.autoattend.core.AppConfig
import com.redmi.autoattend.core.PunchSchedule
import com.redmi.autoattend.data.db.PunchKind

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
        const val ACTION_SEED = "com.redmi.autoattend.debug.SEED_PASSWORD"
        const val ACTION_PUNCH = "com.redmi.autoattend.debug.TRIGGER_PUNCH"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_KIND = "kind"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEED -> seedPassword(context, intent)
            ACTION_PUNCH -> triggerPunch(context, intent)
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
    private fun triggerPunch(context: Context, intent: Intent) {
        AppConfig.init(context)
        val kindName = intent.getStringExtra(EXTRA_KIND)
        val kind = runCatching { PunchKind.valueOf(kindName.orEmpty()) }
            .getOrDefault(PunchKind.CLOCK_OUT)

        // 目标时刻必须用「该班次的计划时刻」，不能用 now —— 否则上班卡闸门永远放行。
        val settings = AppConfig.settings.value
        val startOfDay = PunchSchedule.startOfDay(System.currentTimeMillis())
        val minute = when (kind) {
            com.redmi.autoattend.data.db.PunchKind.CLOCK_OUT -> settings.clockOutMinute
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