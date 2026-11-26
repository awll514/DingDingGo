package com.dingdinggo.app.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.dingdinggo.app.ble.BtScanner
import com.dingdinggo.app.core.ArrivalTriggerCoordinator
import com.dingdinggo.app.core.BtTriggerStore
import com.dingdinggo.app.core.WifiTriggerStore
import com.dingdinggo.app.exec.PunchForegroundService
import java.util.concurrent.Executors

/**
 * 事件驱动的公司 Wi-Fi 监听器。
 *
 * 依赖系统 [ConnectivityManager.NetworkCallback] 监听 Wi-Fi 连接变化（不做高频轮询），
 * 当连上已学习的公司 Wi-Fi 时：
 * 1. 通过 [ArrivalTriggerCoordinator] 触发早到/正点/下班打卡（或早上首次迟到确认）；
 * 2. 若同时开启了蓝牙自动打卡但蓝牙尚未在场，顺带唤醒一次 4 秒高灵敏 BLE 短扫辅助刷新考勤机在场态。
 */
object WifiTriggerMonitor {

    private const val TAG = "WifiMonitor"
    private const val CAPABILITY_THROTTLE_MS = 30_000L

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wifi-trigger-worker").apply { isDaemon = true }
    }

    @Volatile
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastCheckAtMs: Long = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        WifiTriggerStore.init(app)
        if (!WifiTriggerStore.enabled.value) {
            stop(app)
            return
        }

        synchronized(lock) {
            appContext = app
            if (registeredCallback != null) return

            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    scheduleEval(app, capabilities = null, isCapabilitiesUpdate = false)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    scheduleEval(app, capabilities = networkCapabilities, isCapabilitiesUpdate = true)
                }

                override fun onLost(network: Network) {
                    executor.execute {
                        runCatching {
                            WifiTriggerStore.init(app)
                            if (!WifiPresenceReader.isWifiConnected(app)) {
                                WifiTriggerStore.markConnected(false, System.currentTimeMillis())
                                Log.i(TAG, "Wi-Fi 已断开，清除在场状态")
                            }
                        }
                    }
                }
            }

            runCatching {
                cm.registerNetworkCallback(request, cb)
                registeredCallback = cb
                Log.i(TAG, "已注册公司 Wi-Fi 事件监听")
            }.onFailure {
                Log.w(TAG, "注册 Wi-Fi 监听失败: " + it.javaClass.simpleName)
            }
        }

        // 注册后立即检查一次当前是否已连着公司 Wi-Fi
        checkNow(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val cb = registeredCallback ?: return
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
            registeredCallback = null
            Log.i(TAG, "已停止公司 Wi-Fi 监听")
        }
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        WifiTriggerStore.init(app)
        if (WifiTriggerStore.enabled.value) start(app) else stop(app)
    }

    fun checkNow(context: Context) {
        val app = context.applicationContext
        scheduleEval(app, capabilities = null, isCapabilitiesUpdate = false)
    }

    private fun scheduleEval(
        app: Context,
        capabilities: NetworkCapabilities?,
        isCapabilitiesUpdate: Boolean,
    ) {
        executor.execute {
            runCatching {
                evaluate(app, capabilities, isCapabilitiesUpdate)
            }.onFailure {
                Log.w(TAG, "Wi-Fi 状态评估异常: " + it.javaClass.simpleName)
            }
        }
    }

    private fun evaluate(
        app: Context,
        capabilities: NetworkCapabilities?,
        isCapabilitiesUpdate: Boolean,
    ) {
        WifiTriggerStore.init(app)
        BtTriggerStore.init(app)

        if (!WifiTriggerStore.enabled.value) {
            WifiTriggerStore.markConnected(false, System.currentTimeMillis())
            return
        }

        val now = System.currentTimeMillis()
        if (isCapabilitiesUpdate &&
            WifiTriggerStore.connected.value &&
            now - lastCheckAtMs < CAPABILITY_THROTTLE_MS
        ) {
            // 已处于公司 Wi-Fi 在场态时，屏蔽 RSSI/LinkSpeed 变化带来的高频回调
            return
        }

        val targetSsid = WifiTriggerStore.targetSsid.value
        val targetBssid = WifiTriggerStore.targetBssid.value
        if (targetSsid.isBlank() && targetBssid.isBlank()) {
            WifiTriggerStore.markConnected(false, now)
            return
        }

        lastCheckAtMs = now
        val wasConnected = WifiTriggerStore.connected.value
        val snap = WifiPresenceReader.readCurrentWifi(
            context = app,
            allowRootFallback = true,
            knownCapabilities = capabilities,
        )
        val matched = WifiStatusParser.matchesTarget(snap, targetSsid, targetBssid)
        WifiTriggerStore.markConnected(matched, now)

        if (!matched) {
            return
        }

        Log.i(
            TAG,
            "命中公司 Wi-Fi ssid=" + snap?.ssid + " bssid=" + snap?.bssid +
                " fresh=" + !wasConnected,
        )

        ArrivalTriggerCoordinator.onCompanyArrival(
            context = app,
            source = PunchForegroundService.SOURCE_WIFI,
            nowMillis = now,
        )

        // 若同时开启了蓝牙自动打卡且当前不在场，借 Wi-Fi 到达时机主动短扫一次唤醒蓝牙控制器
        if (BtTriggerStore.enabled.value && !BtTriggerStore.present.value) {
            BtScanner.activeBurstScanAndHandle(
                context = app,
                durationMs = 4_000L,
                force = !wasConnected,
            )
        }
    }
}
