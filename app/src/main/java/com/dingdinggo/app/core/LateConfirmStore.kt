package com.dingdinggo.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 早上首次触发迟到确认的日级状态仓库。
 *
 * 线程安全且同步落盘（commit），防止 Wi-Fi、BLE 与定时闹钟并发触发时弹出多次确认。
 */
object LateConfirmStore {

    private const val PREFS_NAME = "autoattend_prefs"

    private const val KEY_DAY = "late_confirm_day"
    private const val KEY_STATE = "late_confirm_state"
    private const val KEY_TARGET_AT = "late_confirm_target_at"
    private const val KEY_PROMPTED_AT = "late_confirm_prompted_at"
    private const val KEY_LATE_SEC = "late_confirm_late_sec"
    private const val KEY_SOURCE = "late_confirm_source"
    private const val KEY_OVERLAY_SHOWN = "late_confirm_overlay_shown"

    private const val STATE_NONE = "NONE"
    private const val STATE_PENDING = "PENDING"
    private const val STATE_CONFIRMED = "CONFIRMED"
    private const val STATE_DECLINED = "DECLINED"

    data class PendingLateConfirm(
        val dateKey: Int,
        val plannedTargetAt: Long,
        val promptedAt: Long,
        val lateSeconds: Long,
        val source: String,
    )

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    private val _pendingPrompt = MutableStateFlow<PendingLateConfirm?>(null)
    val pendingPrompt: StateFlow<PendingLateConfirm?> = _pendingPrompt.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
        reload(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        (appContext ?: context.applicationContext)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun reload(context: Context) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _pendingPrompt.value = readPendingLocked(prefs(context), now)
        }
    }

    /** 今天是否已经触发过一次迟到确认询问（无论当前处于等待、已确认还是已拒绝） */
    fun hasPromptedToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val p = prefs(context)
            p.getInt(KEY_DAY, 0) == PunchSchedule.dateKey(nowMillis) &&
                p.getString(KEY_STATE, STATE_NONE) != STATE_NONE
        }

    /** 今天已被询问的日期键（若尚未询问返回 0） */
    fun promptedDateKey(context: Context, nowMillis: Long = System.currentTimeMillis()): Int =
        synchronized(lock) {
            val p = prefs(context)
            val day = p.getInt(KEY_DAY, 0)
            val state = p.getString(KEY_STATE, STATE_NONE)
            if (day == PunchSchedule.dateKey(nowMillis) && state != STATE_NONE) day else 0
        }

    /**
     * 原子占位今天的唯一一次迟到确认机会。
     *
     * @return true 表示本调用成功夺得今天唯一一次弹窗/通知权利；false 表示今天已问过。
     */
    fun tryClaimPromptToday(
        context: Context,
        nowMillis: Long,
        plannedTargetAt: Long,
        lateSeconds: Long,
        source: String,
    ): Boolean = synchronized(lock) {
        val p = prefs(context)
        val today = PunchSchedule.dateKey(nowMillis)
        val existingDay = p.getInt(KEY_DAY, 0)
        val existingState = p.getString(KEY_STATE, STATE_NONE)
        if (existingDay == today && existingState != STATE_NONE) {
            return@synchronized false
        }

        val ok = p.edit()
            .putInt(KEY_DAY, today)
            .putString(KEY_STATE, STATE_PENDING)
            .putLong(KEY_TARGET_AT, plannedTargetAt)
            .putLong(KEY_PROMPTED_AT, nowMillis)
            .putLong(KEY_LATE_SEC, lateSeconds.coerceAtLeast(0L))
            .putString(KEY_SOURCE, source)
            .putBoolean(KEY_OVERLAY_SHOWN, false)
            .commit()

        if (ok) {
            _pendingPrompt.value = PendingLateConfirm(
                dateKey = today,
                plannedTargetAt = plannedTargetAt,
                promptedAt = nowMillis,
                lateSeconds = lateSeconds.coerceAtLeast(0L),
                source = source,
            )
        }
        ok
    }

    fun markOverlayShown(context: Context) {
        synchronized(lock) {
            prefs(context).edit().putBoolean(KEY_OVERLAY_SHOWN, true).commit()
        }
    }

    /**
     * 若锁屏期间触发了迟到确认且尚未弹出过屏幕弹窗，在 15 分钟内首次解锁时返回待确认对象。
     */
    fun shouldAutoShowOverlayOnUnlock(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): PendingLateConfirm? = synchronized(lock) {
        val p = prefs(context)
        val pending = readPendingLocked(p, nowMillis) ?: return@synchronized null
        if (p.getBoolean(KEY_OVERLAY_SHOWN, false)) return@synchronized null
        val elapsed = nowMillis - pending.promptedAt
        if (elapsed !in 0L..LateConfirmRule.UNLOCK_POPUP_TTL_MS) return@synchronized null
        pending
    }

    fun markConfirmed(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val today = PunchSchedule.dateKey(nowMillis)
            val p = prefs(context)
            p.edit()
                .putInt(KEY_DAY, today)
                .putString(KEY_STATE, STATE_CONFIRMED)
                .putBoolean(KEY_OVERLAY_SHOWN, true)
                .commit()
            _pendingPrompt.value = null
        }
    }

    fun markDeclined(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val today = PunchSchedule.dateKey(nowMillis)
            val p = prefs(context)
            p.edit()
                .putInt(KEY_DAY, today)
                .putString(KEY_STATE, STATE_DECLINED)
                .putBoolean(KEY_OVERLAY_SHOWN, true)
                .commit()
            _pendingPrompt.value = null
        }
    }

    /** 上班打卡已成功完成时清掉悬而未决的确认态（保留当天已处理记录，防止再弹） */
    fun clearPendingIfDone(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val p = prefs(context)
            val today = PunchSchedule.dateKey(nowMillis)
            if (p.getInt(KEY_DAY, 0) == today && p.getString(KEY_STATE, STATE_NONE) == STATE_PENDING) {
                p.edit()
                    .putString(KEY_STATE, STATE_CONFIRMED)
                    .putBoolean(KEY_OVERLAY_SHOWN, true)
                    .commit()
            }
            _pendingPrompt.value = null
        }
    }

    fun clearToday(context: Context) {
        synchronized(lock) {
            prefs(context).edit()
                .remove(KEY_DAY)
                .remove(KEY_STATE)
                .remove(KEY_TARGET_AT)
                .remove(KEY_PROMPTED_AT)
                .remove(KEY_LATE_SEC)
                .remove(KEY_SOURCE)
                .remove(KEY_OVERLAY_SHOWN)
                .commit()
            _pendingPrompt.value = null
        }
    }

    private fun readPendingLocked(p: SharedPreferences, nowMillis: Long): PendingLateConfirm? {
        val today = PunchSchedule.dateKey(nowMillis)
        val day = p.getInt(KEY_DAY, 0)
        val state = p.getString(KEY_STATE, STATE_NONE)
        if (day != today || state != STATE_PENDING) return null
        val targetAt = p.getLong(KEY_TARGET_AT, 0L)
        if (targetAt <= 0L) return null
        return PendingLateConfirm(
            dateKey = day,
            plannedTargetAt = targetAt,
            promptedAt = p.getLong(KEY_PROMPTED_AT, 0L),
            lateSeconds = p.getLong(KEY_LATE_SEC, 0L),
            source = p.getString(KEY_SOURCE, "") ?: "",
        )
    }
}
