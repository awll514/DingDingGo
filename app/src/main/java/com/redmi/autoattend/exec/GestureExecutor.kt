package com.redmi.autoattend.exec

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

    private fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean {
        return runCatching {
            service.dispatchGesture(gesture, null, null)
        }.onFailure {
            Log.w(TAG, "dispatchGesture failed: " + it.javaClass.simpleName)
        }.isSuccess
    }
}
