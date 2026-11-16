package com.dingdinggo.app.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.dingdinggo.app.core.BtTriggerStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 一次性"学习考勤机"。
 *
 * ## 为什么需要它
 *
 * 同一栋楼里可能有几台 0xfe3c 设备（其他楼层的考勤机、门店设备）。
 * 只按 UUID 匹配的话，人在 A 楼层就可能被 B 楼层的机器触发。
 *
 * 学习动作把"我这台"的 MAC/名称记下来，之后按 MAC 精确匹配。
 * 不学习也能用（退化成按 UUID 匹配），但精确匹配更稳。
 *
 * ## 为什么用回调式扫描而不是 PendingIntent 式
 *
 * PendingIntent 式适合"进程不在也要收"，但**拿不到返回值** ——
 * 它只投递广播。学习需要当场拿到设备列表并选一台，
 * 所以这里用回调式扫描，短时运行（默认 12 秒）后**保证停止**。
 *
 * 两者可以共存：常驻注册与本次一次性扫描是两个独立扫描会话。
 *
 * ## 生命周期铁律
 *
 * 这个函数无论从哪条路径返回（成功 / 超时 / 取消 / 启动失败），
 * 都必须确保 [BluetoothLeScanner.stopScan] 被调用一次，且用的
 * 是**同一个 callback 实例**。否则扫描会泄漏（系统侧配额被吃满，
 * 最后导致全设备 BLE 扫描失效）。
 * 所以停止动作统一放在 finally 里。
 */
object BtDeviceLearner {

    private const val TAG = "BtDeviceLearner"

    data class Result(val ok: Boolean, val message: String)

    /**
     * 扫描 [timeoutMs] 毫秒，把扫到的第一台 fe3c 设备记为考勤机。
     *
     * @return 成功时 message 形如「已学习「DingTalk-M1」」
     */
    suspend fun learn(context: Context, timeoutMs: Long = 12_000L): Result {
        val app = context.applicationContext

        BtScanner.blocker(app)?.let { reason ->
            return Result(false, "无法扫描：" + reason)
        }

        val scanner = runCatching {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner
        }.getOrNull() ?: return Result(false, "BLE 扫描不可用")

        // 只放行一次：拿到第一个结果就 resolve，后续广播全部忽略
        val done = AtomicBoolean(false)

        // 这个 callback 必须在 finally 里被同一个实例 stopScan 掉
        var activeCallback: ScanCallback? = null

        try {
            val found = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<ScanResult?> { cont ->
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            if (!done.compareAndSet(false, true)) return
                            if (cont.isActive) cont.resume(result)
                        }

                        override fun onBatchScanResults(results: MutableList<ScanResult>) {
                            val first = results.firstOrNull() ?: return
                            if (!done.compareAndSet(false, true)) return
                            if (cont.isActive) cont.resume(first)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            Log.e(TAG, "学习扫描失败 errorCode=" + errorCode)
                            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                        }
                    }
                    activeCallback = callback

                    val filters = listOf(
                        ScanFilter.Builder()
                            .setServiceUuid(BtScanner.ATTENDANCE_SERVICE_UUID)
                            .build(),
                    )
                    val settings = ScanSettings.Builder()
                        // 用户主动发起的短时动作，用低延迟尽快拿到结果
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()

                    val started = runCatching {
                        scanner.startScan(filters, settings, callback)
                    }

                    if (started.isFailure) {
                        Log.e(TAG, "startScan 失败: " + started.exceptionOrNull()?.javaClass?.simpleName)
                        done.set(true)
                        if (cont.isActive) cont.resume(null)
                    }

                    // 协程被取消（例如页面销毁）时，finally 仍会负责 stopScan
                }
            }

            val result = found
                ?: return if (done.get()) {
                    Result(false, "扫描被系统拒绝（请检查蓝牙是否开启）")
                } else {
                    Result(false, "附近没有扫到钉钉考勤机，请走到考勤机旁重试")
                }

            val mac = runCatching { result.device?.address }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return Result(false, "扫到设备但拿不到地址")

            val name = runCatching { result.scanRecord?.deviceName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "钉钉考勤机"

            BtTriggerStore.saveDevice(mac, name)
            Log.i(TAG, "学习成功 mac=" + mac + " name=" + name)
            return Result(true, "已学习「" + name + "」")
        } finally {
            // 【铁律】无论走哪条路径，都必须用同一个 callback 停扫
            activeCallback?.let { cb -> runCatching { scanner.stopScan(cb) } }
        }
    }
}
