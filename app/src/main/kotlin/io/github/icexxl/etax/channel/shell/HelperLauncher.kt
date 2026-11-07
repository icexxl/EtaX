package io.github.icexxl.etax.channel.shell

import android.content.Context
import android.util.Log
import io.github.icexxl.etax.channel.adb.AdbConnectPort
import io.github.icexxl.etax.channel.adb.AdbConnection
import io.github.icexxl.etax.channel.adb.AdbConnectionException
import io.github.icexxl.etax.channel.adb.AdbPairing
import io.github.icexxl.etax.channel.adb.AdbServiceTypes
import io.github.icexxl.etax.channel.adb.EtaAdb
import io.github.icexxl.etax.channel.adb.EtaNsdServiceFinder
import io.github.icexxl.etax.channel.adb.LocalPortScan
import io.github.icexxl.etax.channel.adb.NioPortScanner
import io.github.icexxl.etax.channel.adb.PortCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

        fun clear() {
            prefs.edit().remove(KEY_PORT).apply()
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

    /** 正在保持的 adb 连接：助手进程靠它活着，不能提前关。 */
    @Volatile
    private var liveConnection: AdbConnection? = null

    private val connectionMutex = Mutex()

    /** 助手进程的原始输出（含崩溃堆栈），连接失败时给用户看或写日志。 */
    @Volatile
    var helperOutput: String? = null
        private set

    suspend fun pair(context: Context, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        runCatching {
            val tls = EtaAdb.tls(EtaAdb.pairingIdentity(context))
            AdbPairing(tls).pair(AdbConnectPort.LOCALHOST, port, code)
            Log.i(TAG, "paired with adbd on port $port")
            // Pairing and connection listen on different ports. The connect path caches its own port.
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
    suspend fun launch(
        context: Context,
        connectPort: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.lock()
        try {
        if (EtaShellConnection.isConnected(context)) return@withContext true
        lastError = null
        helperOutput = null
        val packageName = context.packageName
        val appUid = context.applicationInfo.uid
        val apkPath = context.applicationInfo.sourceDir
        val nonce = EtaShellConnection.hub(context).issueNonce()
        val certPin = runCatching { certFingerprint(context) }.getOrDefault("")
        val command = HelperHandoff.command(apkPath, packageName, appUid, certPin)
        var pendingConnection: AdbConnection? = null
        try {
        val connection = try {
            connect(context, connectPort).also { pendingConnection = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = when (error) {
                is AdbConnectionException -> when (error.reason) {
                    AdbConnectionException.Reason.CONNECT_FAILED ->
                        "找不到无线调试端口（确认无线调试开着、手机连着 Wi-Fi；也可在「连接端口」里直接填无线调试主页上的端口）"
                    AdbConnectionException.Reason.TLS_FAILED,
                    AdbConnectionException.Reason.UNAUTHORIZED ->
                        "adbd 拒绝了这把钥匙，需要重新配对"
                    AdbConnectionException.Reason.NOT_LOCAL -> "只允许连接本机 adbd"
                    else -> error.message ?: "连接 adbd 失败"
                }
                else -> error.message ?: error.javaClass.simpleName
            }
            Log.w(TAG, "connect adbd failed", error)
            return@withContext false
        }

        val stream = try {
            connection.exec(command)
        } catch (error: Throwable) {
            runCatching { connection.close() }
            lastError = "adbd 拒绝执行助手命令：${error.message ?: error.javaClass.simpleName}"
            Log.w(TAG, "exec helper failed", error)
            return@withContext false
        }

        // 先把口令写进去，助手起来后会经 Provider 把 Binder 交回
        runCatching { stream.write(HelperHandoff.stdinLine(nonce)) }
            .onFailure {
                runCatching { connection.close() }
                lastError = "写入口令失败：${it.message}"
                return@withContext false
            }

        // 助手占着这条 exec，不能关连接；后台读它的输出，进程退出时清理
        liveConnection?.takeIf { it !== connection }?.let { runCatching { it.close() } }
        liveConnection = connection
        pendingConnection = null
        startOutputDrain(stream, connection)

        // 轮询是否已交接（助手启动需要一点时间）
        val deadline = System.currentTimeMillis() + HANDOFF_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (EtaShellConnection.isConnected(context)) return@withContext true
            delay(150)
        }

        runCatching { connection.close() }
        if (liveConnection === connection) liveConnection = null
        EtaShellConnection.hub(context).forget()
        lastError = buildString {
            append("助手没有回报连接（已连上 adbd 并启动助手，但没收到交接）")
            helperOutput?.takeIf { it.isNotBlank() }?.let { append("；助手输出：").append(it.take(400)) }
        }
        false
        } catch (error: CancellationException) {
            // Leaving the screen during handoff must not kill an already attached helper.
            if (!EtaShellConnection.isConnected(context)) {
                if (liveConnection === pendingConnection) liveConnection = null
                liveConnection?.let { connection ->
                    liveConnection = null
                    runCatching { connection.close() }
                }
                EtaShellConnection.hub(context).forget()
            }
            throw error
        } finally {
            pendingConnection?.let { runCatching { it.close() } }
        }
        } finally {
            connectionMutex.unlock()
        }
    }

    /** 显式断开：先撤销 shell 资源，再终止助手并删除本地配对身份。 */
    suspend fun disconnect(context: Context): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        connectionMutex.withLock {
            val hub = EtaShellConnection.hub(context)
            val shell = hub.shell.value
            lastError = null
            var released = shell == null
            var stopped = shell == null
            try {
                if (shell != null) {
                    try {
                        shell.releaseAll()
                        released = true
                    } catch (error: Exception) {
                        Log.w(TAG, "release shell resources failed", error)
                    }
                    val pid = HelperHandoff.shellPid(runCatching { shell.identity() }.getOrNull())
                    val connection = liveConnection?.takeUnless { it.isClosed }
                    if (pid != null && connection != null) {
                        // Explicit PID avoids killing an unrelated shell process or adbd wrapper.
                        stopped = runCatching {
                            connection.exec("kill $pid").use { it.readAll() }
                            true
                        }.onFailure { Log.w(TAG, "stop helper failed", it) }.getOrDefault(false)
                    }
                    if (!released || !stopped) lastError = "助手资源释放或进程终止未确认"
                }
            } finally {
                hub.forget()
                liveConnection?.let { runCatching { it.close() } }
                liveConnection = null
                PrefsPortCache(context).clear()
                try {
                    EtaAdb.forget(context)
                } catch (error: Exception) {
                    Log.w(TAG, "clear adb pairing failed", error)
                    lastError = error.message ?: error.javaClass.simpleName
                    return@withLock false
                }
            }
            released && stopped
        }
    }

    /** 持续读助手输出用于诊断；连接结束时清掉句柄，供下次重连。 */
    private fun startOutputDrain(stream: AdbConnection.AdbStream, connection: AdbConnection) {
        Thread({
            val text = runCatching { stream.readText() }.getOrNull().orEmpty()
            if (text.isNotBlank()) {
                helperOutput = text.trim().takeLast(1200)
                Log.i(TAG, "helper exited: ${text.take(600)}")
            }
            if (liveConnection === connection) liveConnection = null
            runCatching { connection.close() }
        }, "etax-helper-drain").apply { isDaemon = true }.start()
    }

    /** 用上次的端口 / mDNS / 本机扫描依次找端口并连上。 */
    private suspend fun connect(context: Context, connectPort: Int?): AdbConnection {
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
            manual = { connectPort },
            report = { candidates ->
                Log.i(
                    TAG,
                    "port candidates: cache=${candidates.cache} mdns=${candidates.mdns} " +
                        "scanned=${candidates.scanned}(open=${candidates.scanOpen},adb=${candidates.scanAdb}) " +
                        "manual=${candidates.manual} connected=${candidates.connected}",
                )
            },
        )
        // AdbConnectPort 在连上时会自己把端口写进缓存，这里不要再覆盖
        return port.connect { host, candidate ->
            AdbConnection.open(host, candidate, EtaAdb.tls(EtaAdb.identity(context)))
        }
    }

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

    /** 等助手完成交接的最长时间。 */
    private const val HANDOFF_WAIT_MS = 15_000L
}
