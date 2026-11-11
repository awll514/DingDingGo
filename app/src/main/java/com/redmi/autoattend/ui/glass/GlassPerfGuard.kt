package com.redmi.autoattend.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/**
 * 玻璃性能守门员。
 *
 * ## 为什么需要它
 *
 * AGSL 全管线的成本是「每次采样 = 一次图层录制 + 一次 RenderEffect 链 +
 * 一次 AGSL 片元着色」。在中高端 GPU 上完全没问题，
 * 但在三种情况下会翻车：
 *
 * 1. **软件渲染器**（模拟器 SwiftShader）——AGSL 在软件光栅化下慢到不可用；
 * 2. **低端 GPU**——片元着色吞吐不够；
 * 3. **温控降频**——手机长时间高负载后 GPU 锁频，原本流畅的界面开始掉帧。
 *
 * 与其让用户面对卡顿，不如主动降级到轻量磨砂（视觉上仍有玻璃质感，
 * 只是没有模糊和折射）。这个降级是**静默且可恢复的**。
 *
 * ## 怎么判定
 *
 * 用帧间隔的滑动窗口估计：连续 N 帧超过阈值就降级，
 * 然后再定期重测，如果恢复流畅就升回来。
 * 不做一次性判定，因为温控降频是动态的。
 */
object GlassPerfGuard {

    /** 单帧超过这个时长就算"卡"（约 20fps 以下） */
    private const val SLOW_FRAME_MS = 48L

    /** 连续多少帧卡就降级 */
    private const val SLOW_FRAMES_TO_DEGRADE = 20

    /** 降级后，连续多少帧流畅才恢复 */
    private const val FAST_FRAMES_TO_RESTORE = 45

    /** 降级后至少保持这么久再考虑恢复，避免反复横跳 */
    private const val RESTORE_COOLDOWN_MS = 8_000L

    /**
     * 切回前台后的预热帧数。
     *
     * ## 为什么必须有这个
     *
     * 进程从后台回到前台的第一帧必然很慢：Compose 要重建组合、
     * AGSL 管线要重新初始化、图层要重新录制。实测这一段单帧轻松超过 200ms，
     * 远超 SLOW_FRAME_MS 的 48ms 阈值。
     *
     * 如果这段被算进"慢帧"统计，只要用户切几次前后台，
     * 就会在**设备完全不卡**的情况下被判成"需要降级"，
     * 然后玻璃永久退化成没有模糊和折射的 FrostedPanel ——
     * 看起来就像"玻璃突然变成一块塑料板"，而且用户不知道为什么。
     *
     * 所以切回前台后先丢弃若干帧不参与统计。
     */
    private const val WARMUP_FRAMES_AFTER_RESUME = 8

    /**
     * 两帧间隔超过这个值，就认为发生了"suspend / 屏幕熄灭 / 切前后台"，
     * 而不是普通掉帧。1 秒足够区分：正常掉帧不会到这个量级。
     */
    private const val RESUME_GAP_MS = 1_000L

    /** 是否应该降级（false = 用全 AGSL 管线） */
    var shouldDegrade by mutableStateOf(initialDegrade())
        private set

    /**
     * 静态判定：明显不可能跑得动 AGSL 的环境直接降级。
     *
     * 主要拦软件渲染器（模拟器）。真机的 Adreno/Mali 都能跑。
     */
    private fun initialDegrade(): Boolean {
        // 模拟器/软件渲染：直接降级，否则整个界面卡到没法用
        val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

        val softwareGl = Build.HARDWARE.contains("swiftshader")

        return isEmulator || softwareGl
    }

    /**
     * 启动动态监测。
     *
     * 在 App 根组件里调用一次即可（内部用 LaunchedEffect 保证只跑一份）。
     */
    @Composable
    fun StartMonitoring() {
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            var slowFrames = 0
            var fastFrames = 0
            var lastFrameNanos = 0L
            var degradedAt = 0L

            // 前后台切换时重置统计窗口，避免"切回来第一帧慢"被误判为掉帧。
            // 前后台切换会触发一次重新组合，但 LaunchedEffect(Unit) 不会重启，
            // 所以这里用一个可变的预热计数来吸收那几帧。
            var warmupLeft = 0

            while (true) {
                withFrameNanos { now ->
                    if (lastFrameNanos != 0L) {
                        val deltaMs = (now - lastFrameNanos) / 1_000_000L

                        // 间隔异常大 = 刚从后台回来（或屏幕刚点亮），不计入判定
                        if (deltaMs > RESUME_GAP_MS) {
                            warmupLeft = WARMUP_FRAMES_AFTER_RESUME
                        }
                        if (warmupLeft > 0) {
                            warmupLeft--
                            slowFrames = 0
                            lastFrameNanos = now
                            return@withFrameNanos
                        }
                        val elapsedSinceDegrade = (now - degradedAt) / 1_000_000L

                        if (shouldDegrade) {
                            // 已经在降级状态：找机会恢复
                            if (deltaMs < SLOW_FRAME_MS) {
                                fastFrames++
                            } else {
                                fastFrames = 0
                            }
                            if (fastFrames >= FAST_FRAMES_TO_RESTORE &&
                                elapsedSinceDegrade > RESTORE_COOLDOWN_MS
                            ) {
                                // 注意：静态降级（模拟器）不恢复，否则又会卡回去
                                if (!isStaticallyDegraded(context)) {
                                    shouldDegrade = false
                                    fastFrames = 0
                                }
                            }
                        } else {
                            // 正常状态：找该降级的信号
                            if (deltaMs > SLOW_FRAME_MS) {
                                slowFrames++
                            } else {
                                slowFrames = (slowFrames - 1).coerceAtLeast(0)
                            }
                            if (slowFrames >= SLOW_FRAMES_TO_DEGRADE) {
                                shouldDegrade = true
                                degradedAt = now
                                slowFrames = 0
                            }
                        }
                    }
                    lastFrameNanos = now
                }
            }
        }
    }

    /** 静态降级（模拟器）的真值，用于判断是否允许恢复 */
    private fun isStaticallyDegraded(context: android.content.Context): Boolean =
        initialDegrade()
}
