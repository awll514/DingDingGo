package com.dingdinggo.app.exec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * 手势执行器：通过无障碍服务派发点击/滑动。
 *
 * 这是"不依赖坐标写死"的关键——手势走系统级注入，而不是 input tap 的 shell 往返，
 * 延迟更低，也不会因为 su 授权弹窗被打断。
 */
object GestureExecutor {

    private const val TAG = "GestureExecutor"

    /** 在指定屏幕坐标点击 */
    fun tap(x: Int, y: Int): Boolean {
        val service = PunchAccessibilityService.get() ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(service, gesture)
    }

    /** 在指定坐标区间滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val service = PunchAccessibilityService.get() ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(30))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(service, gesture)
    }

    /**
     * 派发手势并返回**真实**结果。
     *
     * 【审计 C-07 修复】旧实现用 `.isSuccess` 判断 `runCatching { ... }` 的结果，
     * 而 `runCatching` 只反映"有没有抛异常"—— `dispatchGesture()` 本身返回的
     * Boolean（false = 系统拒绝了这次手势）被整个丢弃了。
     *
     * 后果是**静默失败**：
     *   · 手势被系统拒绝时仍返回 true；
     *   · 调用方（PunchEngine 解锁上滑）以为滑成功了，不再降级到 root 通道；
     *   · 日志还写着"已点击/已滑动"，排查时完全看不出是手势没派发出去。
     *
     * 现在把 dispatchGesture 的 Boolean 与异常状态**都**纳入判定：
     * 只有既没抛异常、且系统接受了这次手势，才算成功。
     */
    private fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean {
        return runCatching {
            service.dispatchGesture(gesture, null, null)
        }.fold(
            onSuccess = { accepted ->
                if (!accepted) {
                    Log.w(TAG, "dispatchGesture 被系统拒绝（返回 false），该手势未派发")
                }
                accepted
            },
            onFailure = { t ->
                Log.w(TAG, "dispatchGesture 抛出异常: " + t.javaClass.simpleName)
                false
            },
        )
    }
}
