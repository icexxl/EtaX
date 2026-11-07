package io.github.icexxl.etax.channel.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用系统 mDNS 找本机 adbd 公布的端口（派生自 Mika 的 NsdAdbServiceFinder，Apache-2.0，已裁剪）。
 *
 * 只认地址属于本机网卡的结果：同一局域网里别的手机也会公布同名服务。
 * 拿到端口后由调用方连 127.0.0.1。查到第一条后再多等一会儿收齐同时公布的几条。
 */
internal class EtaNsdServiceFinder(context: Context) : AdbServiceFinder {

    private val nsd: NsdManager? =
        context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val manager = nsd ?: return emptyList()
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                services.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                services.close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { return emptyList() }

        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) {
                var settleUntil = Long.MAX_VALUE
                while (System.currentTimeMillis() < settleUntil) {
                    val remaining = (settleUntil - System.currentTimeMillis()).coerceAtMost(timeoutMs)
                    val info = withTimeoutOrNull(remaining) {
                        services.receiveCatching().getOrNull()
                    } ?: break
                    resolve(manager, info)?.let { port ->
                        ports += port
                        if (settleUntil == Long.MAX_VALUE) {
                            settleUntil = System.currentTimeMillis() + SETTLE_MS
                        }
                    }
                }
            }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    /** 解析一条服务，是本机的就给出端口；解析失败或不是本机返回 null。 */
    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): Int? {
        val done = Channel<Int?>(1)
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        done.trySend(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        done.trySend(info.port.takeIf { isLocal(info.host) })
                    }
                },
            )
        }.onFailure { return null }
        return withTimeoutOrNull(RESOLVE_MS) { done.receive() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
                nic.inetAddresses.asSequence().any { it == host }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
    }
}
