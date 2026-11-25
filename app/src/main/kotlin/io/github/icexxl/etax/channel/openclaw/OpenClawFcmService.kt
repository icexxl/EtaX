package io.github.icexxl.etax.channel.openclaw

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.icexxl.etax.host.EtaXHost
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 原生 FCM 推送接收服务（高优先级 Data Message）。
 *
 * - App 切后台休眠、锁屏甚至被系统回收后，由 Google Play Services 直接唤醒；
 * - 复用 [OpenClawNotifier] 弹出 EtaX 原生通知，点击直达对应会话；
 * - 若用户当前正停留在前台该会话内，自动静默跳过，避免重复打扰。
 */
class OpenClawFcmService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val clean = token.trim()
        if (clean.isEmpty()) return
        val settings = OpenClawSettings(applicationContext)
        settings.saveFcmToken(clean)
        val config = settings.load()
        if (!config.isConfigured) return
        val base = config.normalizedBaseUrl() ?: return
        val signature = "$base|${clean.hashCode()}|${config.token.hashCode()}"
        val deviceId = settings.deviceId()
        serviceScope.launch {
            EtaXHost.createOpenClawClient()
                .registerPushToken(config, deviceId, clean)
                .onSuccess {
                    settings.saveFcmRegisteredSignature(signature)
                    AndroidAgentLogger.debug { "openclaw.fcm: token registered" }
                }
                .onFailure { error ->
                    AndroidAgentLogger.debug { "openclaw.fcm: register failed: ${error.message}" }
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) return
        val sessionKey = data["sessionKey"].orEmpty().trim()
        val body = data["body"].orEmpty().trim()
        if (sessionKey.isEmpty() || body.isEmpty()) return
        if (OpenClawHomeStore.activeForegroundSessionKey == sessionKey) {
            AndroidAgentLogger.debug { "openclaw.fcm: skip foreground session=$sessionKey" }
            return
        }
        val agentName = data["agentName"].orEmpty().ifBlank {
            data["agentId"].orEmpty().ifBlank { "OpenClaw" }
        }
        runCatching {
            EtaXHost.createOpenClawNotifier(applicationContext).notify(sessionKey, agentName, body)
        }.onFailure { error ->
            AndroidAgentLogger.debug { "openclaw.fcm: notify failed: ${error.message}" }
        }
    }
}
