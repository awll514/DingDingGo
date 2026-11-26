package com.dingdinggo.app.core

import com.dingdinggo.app.data.db.PunchKind

/**
 * 早上首次触发迟到卡时的确认规则（纯函数，可单测）。
 *
 * ## 语义边界
 *
 * 1. **默认不打迟到卡**：只在今天尚未打上班卡、且今天尚未向用户弹过确认时，
 *    允许在首次撞上迟到边界时发起「确认是否打迟到卡」询问；
 * 2. **一天最多询问一次**：无论是定时闹钟、蓝牙考勤机还是公司 Wi-Fi 触发，
 *    只要今天已经询问过，当天后续到达或定时重检都不再重复打扰；
 * 3. **超时 / 默认关闭绝不打卡**：只有用户显式点击「确认打迟到卡」才允许放行迟到红线，
 *    且仅对 [PunchKind.CLOCK_IN] 生效，绝不影响下班早退红线。
 */
object LateConfirmRule {

    /** 上班时间后最多 3 小时内允许首次迟到确认 */
    const val MAX_LATE_CONFIRM_WINDOW_MS = 3L * 60 * 60 * 1000L

    /** 未打上班卡且今日尚未询问过时，早到扫描窗可向迟到确认期延展的分钟数 */
    const val DEFAULT_LATE_SCAN_WINDOW_MINUTES = 120

    /** 屏幕弹窗自动关闭时间（60 秒，超时默认不打） */
    const val OVERLAY_AUTO_DISMISS_MS = 60_000L

    /** 锁屏期间错过弹窗后，解锁自动补弹的有效期（15 分钟） */
    const val UNLOCK_POPUP_TTL_MS = 15L * 60 * 1000L

    /**
     * 判断当前首次迟到触发是否应当发起一次确认询问。
     */
    fun shouldPromptLateConfirm(
        kind: PunchKind,
        nowMillis: Long,
        clockInTargetMillis: Long,
        clockOutTargetMillis: Long,
        clockInDoneToday: Boolean,
        promptedDateKey: Int,
    ): Boolean {
        if (kind != PunchKind.CLOCK_IN) return false
        if (clockInDoneToday) return false
        if (clockInTargetMillis <= 0L) return false

        // 尚未超过上班迟到停手点：属于正常可打区间，不需要迟到确认
        if (nowMillis <= BtPunchRule.clockInMustStopAt(clockInTargetMillis)) return false

        // 超过最大迟到确认窗口（3 小时）不再询问
        if (nowMillis > clockInTargetMillis + MAX_LATE_CONFIRM_WINDOW_MS) return false

        // 常规白班已进入下班可打时间后，优先交由下班流程处理
        if (clockOutTargetMillis > clockInTargetMillis &&
            nowMillis >= BtPunchRule.clockOutEarliestAt(clockOutTargetMillis)
        ) {
            return false
        }

        // 每天最多主动询问一次
        val todayKey = PunchSchedule.dateKey(nowMillis)
        if (promptedDateKey == todayKey) return false

        return true
    }

    /**
     * 只有用户显式确认且班次为上班卡时，才允许放行迟到红线。
     * 下班卡早退红线绝不允许任何绕过。
     */
    fun isLateAllowedForKind(kind: PunchKind, allowLate: Boolean): Boolean =
        allowLate && kind == PunchKind.CLOCK_IN
}
