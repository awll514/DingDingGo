package com.dingdinggo.app.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dingdinggo.app.core.AppConfig
import com.dingdinggo.app.core.ArrivalTriggerCoordinator
import com.dingdinggo.app.core.BtTriggerStore
import com.dingdinggo.app.core.WifiTriggerStore
import com.dingdinggo.app.exec.PunchForegroundService
import com.dingdinggo.app.wifi.WifiTriggerMonitor

/**
 * BLE 扫描结果的系统投递接收器与窗口内主动短扫续期入口。
 */
class BleScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BleScanReceiver"

        /** 广播级去重窗口（10 秒，挡掉同批多次投递与站立不动时的高频唤醒） */
        private const val MIN_SCAN_DEDUPE_MS = 10_000L

        @Volatile
        private var lastReceiveAtMs = 0L

        private val seenLock = Any()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()

        // ---------- -1. 扫描续期（控制路径，不受扫描结果去重影响）----------
        if (intent.action == BtScanner.ACTION_SCAN_RENEW) {
            val pending = goAsync()
            Thread({
                try {
                    runCatching {
                        AppConfig.init(app)
                        BtTriggerStore.init(app)
                        WifiTriggerStore.init(app)
                    }
                    BtScanner.renewAndReschedule(app)
                    if (BtScanner.shouldKeepScanAliveNow(app)) {
                        // 若在有效打卡窗口内，节流补一次 4 秒主动短扫，唤醒深度 Doze 下被挂起的蓝牙芯片
                        BtScanner.activeBurstScanAndHandle(app, durationMs = 4_000L, force = false)
                        // 若已提前连上公司 Wi-Fi 才进入窗口，顺带触发一次 Wi-Fi 在场复核
                        if (WifiTriggerStore.enabled.value) {
                            WifiTriggerMonitor.checkNow(app)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "续期后台任务异常: " + t.javaClass.simpleName)
                } finally {
                    runCatching { pending.finish() }
                }
            }, "bt-renew-worker").start()
            return
        }

        // ---------- 0. 最外层内存节流（必须在任何 I/O 之前）----------
        @Suppress("UNUSED_EXPRESSION")
        synchronized(seenLock) {
            if (lastReceiveAtMs > 0L && now - lastReceiveAtMs < MIN_SCAN_DEDUPE_MS) {
                return
            }
            lastReceiveAtMs = now
        }

        // ---------- 1. 解析扫描结果 ----------
        val results = extractScanResults(intent)
        if (results.isEmpty()) {
            Log.w(TAG, "扫描结果为空（可能被 ROM 过滤），忽略")
            return
        }

        val pending = goAsync()
        Thread({
            try {
                runCatching {
                    AppConfig.init(app)
                    AppConfig.reload()
                    BtTriggerStore.init(app)
                }.onFailure {
                    Log.e(TAG, "初始化配置失败: " + it.javaClass.simpleName)
                    return@Thread
                }

                if (!BtTriggerStore.enabled.value) {
                    Log.i(TAG, "蓝牙触发已关闭，忽略扫描结果")
                    return@Thread
                }

                val matched = results.firstOrNull { BtScanner.matchesTargetDevice(it) }
                if (matched == null) {
                    Log.i(TAG, "扫描结果不匹配已学到的考勤机，忽略（共 " + results.size + " 条）")
                    return@Thread
                }

                BtTriggerStore.markSeen(now)
                Log.i(
                    TAG,
                    "检测到考勤机 mac=" + runCatching { matched.device?.address }.getOrNull() +
                        " rssi=" + matched.rssi +
                        " name=" + runCatching { matched.scanRecord?.deviceName }.getOrNull(),
                )

                ArrivalTriggerCoordinator.onCompanyArrival(
                    context = app,
                    source = PunchForegroundService.SOURCE_BLUETOOTH,
                    nowMillis = now,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "处理扫描广播失败: " + t.javaClass.simpleName, t)
            } finally {
                runCatching { pending.finish() }
            }
        }, "bt-scan-worker").start()
    }

    /**
     * 解析系统塞进 Intent 的扫描结果。
     */
    private fun extractScanResults(intent: Intent): List<ScanResult> {
        val key = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT

        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(key)
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<ScanResult>(key)
        }.getOrNull()?.let { return listOf(it) }

        runCatching {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(key) as? ArrayList<*>
        }.getOrNull()?.let { raw ->
            val typed = raw.filterIsInstance<ScanResult>()
            if (typed.isNotEmpty()) return typed
        }

        return emptyList()
    }
}
