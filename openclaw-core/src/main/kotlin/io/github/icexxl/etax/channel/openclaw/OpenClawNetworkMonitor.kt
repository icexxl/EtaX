package io.github.icexxl.etax.channel.openclaw

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * 监听 Android 默认网络切换（如家里 Wi-Fi ↔ 5G 切换）。
 *
 * 切网时旧的长连接/SSE 往往处于半开假死状态，需要等待十几秒超时；
 * 通过此监听器去抖 300ms 后立即触发重连与连接池清理，将恢复时间压缩到 < 0.5 秒。
 */
class OpenClawNetworkMonitor(
    context: Context,
    private val onNetworkChanged: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var activeNetwork: Network? = null
    private var initialized = false
    private val notifyTask = Runnable { onNetworkChanged() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prev = activeNetwork
            activeNetwork = network
            if (!initialized) {
                initialized = true
                return
            }
            if (prev != network) {
                scheduleNotify()
            }
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                activeNetwork = null
                scheduleNotify()
            }
        }
    }

    fun start() {
        if (registered || cm == null) return
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun stop() {
        handler.removeCallbacks(notifyTask)
        if (!registered || cm == null) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun scheduleNotify() {
        handler.removeCallbacks(notifyTask)
        handler.postDelayed(notifyTask, DEBOUNCE_MS)
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
