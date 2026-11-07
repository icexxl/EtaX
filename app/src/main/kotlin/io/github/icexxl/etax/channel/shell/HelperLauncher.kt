package io.github.icexxl.etax.channel.shell

import android.content.Context
import android.util.Log
import io.github.icexxl.etax.channel.adb.AdbConnectPort
import io.github.icexxl.etax.channel.adb.AdbConnection
import io.github.icexxl.etax.channel.adb.AdbPairing
import io.github.icexxl.etax.channel.adb.AdbServiceTypes
import io.github.icexxl.etax.channel.adb.EtaAdb
import io.github.icexxl.etax.channel.adb.EtaNsdServiceFinder
import io.github.icexxl.etax.channel.adb.LocalPortScan
import io.github.icexxl.etax.channel.adb.NioPortScanner
import io.github.icexxl.etax.channel.adb.PortCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 经本机无线调试拉起 shell 助手（对应 Mika 的 HelperHub 起助手那一步）。
 *
 * 流程：生成一次性口令 → 找到 adbd 的连接端口并连上（TLS，用已配对的身份）→
 * `exec` 一条 `app_process` 命令把助手拉起来 → 把口令写到它的标准输入。
 * 助手起来后经 [HelperHubProvider] 把 Binder 交回，[EtaShellConnection] 收到即可用。
 */
internal object HelperLauncher {

    private const val TAG = "EtaHelperLaunch"

    /** 保存上次连上的端口，下次优先试（无线调试开关后端口会变）。 */
    private class PrefsPortCache(private val context: Context) : PortCache {
        private val prefs = context.applicationContext
            .getSharedPreferences("etax_adb", Context.MODE_PRIVATE)

        override fun load(): Int? = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..65535 }

        override fun save(port: Int) {
            prefs.edit().putInt(KEY_PORT, port).apply()
        }

        private companion object {
            const val KEY_PORT = "connect_port"
        }
    }

    /**
     * 配对：用户填了无线调试的配对码后调用。成功后钥匙被设备接受，之后可直接 [launch]。
     * 返回是否成功，失败原因在 [lastError]。
     */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun pair(context: Context, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        runCatching {
            val tls = EtaAdb.tls(EtaAdb.pairingIdentity(context))
            AdbPairing(tls).pair(AdbConnectPort.LOCALHOST, port, code)
            Log.i(TAG, "paired with adbd on port $port")
            PrefsPortCache(context).save(port)
            true
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "pairing failed", error)
            false
        }
    }

    /**
     * 拉起助手（若已在跑则依赖现有连接）。返回是否已经把命令送出去。
     * 助手真正的「可用」由 [EtaShellConnection.isConnected] 反映。
     */
    suspend fun launch(context: Context, manualPort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (EtaShellConnection.isConnected(context)) return@withContext true
        lastError = null
        val packageName = context.packageName
        val appUid = context.applicationInfo.uid
        val apkPath = context.applicationInfo.sourceDir
        val nonce = EtaShellConnection.hub(context).issueNonce()

        // 证书指纹用于助手自检（当前只做传递，不做校验强度要求）
        val certPin = runCatching { certFingerprint(context) }.getOrDefault("")

        val command = HelperHandoff.command(apkPath, packageName, appUid, certPin)
        runCatching {
            val connection = connect(context, manualPort)
            try {
                val stream = connection.exec(command)
                // AdbStream.write 直接下发 WRTE，没有额外的缓冲层
                stream.write(HelperHandoff.stdinLine(nonce))
                // 助手进程占着这条 exec：读到它退出为止；期间它已把 Binder 交回
                val output = withTimeoutOrNull(HELPER_READ_TIMEOUT_MS) { stream.readText() }
                if (output != null) {
                    // 读到 EOF 说明助手退出了；连接随后可复用
                    Log.i(TAG, "helper exited: ${output.take(400)}")
                }
                true
            } finally {
                runCatching { connection.close() }
            }
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "launch failed", error)
            false
        }
    }

    /** 用上次的端口 / mDNS / 本机扫描依次找端口并连上。 */
    private suspend fun connect(context: Context, manualPort: Int?): AdbConnection {
        val finder = EtaNsdServiceFinder(context)
        val cache = PrefsPortCache(context)
        val port = AdbConnectPort(
            property = { null },
            finder = finder,
            cache = cache,
            scan = LocalPortScan(
                scanner = NioPortScanner(),
                hosts = { listOf(AdbConnectPort.LOCALHOST) },
            ),
            manual = { manualPort },
        )
        return port.connect { host, candidate ->
            AdbConnection.open(host, candidate, EtaAdb.tls(EtaAdb.identity(context)))
        }.also { cache.save(it.bannerPort()) }
    }

    private fun AdbConnection.bannerPort(): Int = runCatching {
        Regex("port=(\\d+)").find(banner)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }.getOrNull() ?: runCatching {
        // 从 banner 拿不到时退回上次的缓存值
        0
    }.getOrDefault(0)

    private fun certFingerprint(context: Context): String {
        val signatures = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo
            ?.apkContentsSigners
            ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return signatures.firstOrNull()?.toByteArray()
            ?.let { digest.digest(it).joinToString("") { b -> "%02x".format(b) } }
            ?: ""
    }

    private const val HELPER_READ_TIMEOUT_MS = 60_000L
}
