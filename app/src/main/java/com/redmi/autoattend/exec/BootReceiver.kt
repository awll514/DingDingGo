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

        AppConfig.init(context)
        AppConfig.reload()

        // 重排所有闹钟
        runCatching {
            AlarmScheduler.scheduleNext(context)
            Log.i(TAG, "闹钟已重排")
        }.onFailure {
            Log.e(TAG, "重排失败: " + it.javaClass.simpleName)
        }

        // 重启后也要恢复保活链路：
        // - 前台服务（Android 15+ 对 BOOT 起的 FGS 有类型限制，所以只在后台起）
        // - 心跳闹钟 + JobScheduler
        // - 无障碍自愈
        runCatching { KeepAliveService.start(context) }
        runCatching { ResurrectionService.install(context) }
        runCatching { AccessibilityKeeper.ensureEnabled(context) }
    }
}