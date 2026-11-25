package io.github.icexxl.etax.host

import android.app.Application
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import io.github.icexxl.etax.channel.openclaw.OpenClawClient
import io.github.icexxl.etax.channel.openclaw.OpenClawContextUsage
import io.github.icexxl.etax.channel.openclaw.OpenClawHomeStore
import io.github.icexxl.etax.channel.openclaw.OpenClawNetworkMonitor
import io.github.icexxl.etax.channel.openclaw.OpenClawNotifier
import io.github.icexxl.etax.channel.openclaw.OpenClawSettings
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import kotlinx.coroutines.CoroutineScope

/**
 * EtaX 宿主接线统一入口：集中管理 :openclaw-core / :vscreen-core 与上游 :app 的桥接，
 * 将上游宿主文件中的 EtaX 改动收敛到最小单行调用。
 */
internal object EtaXHost {
    fun createOpenClawClient(): OpenClawClient = OpenClawClient(AgentHttpClient.client)

    fun createOpenClawNotifier(context: Context): OpenClawNotifier = OpenClawNotifier(
        context = context.applicationContext,
        smallIconRes = R.drawable.ic_stat_openclaw,
        launchIntentFactory = { ctx, sessionKey, agentId ->
            Intent(ctx, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_OPENCLAW_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPENCLAW_SESSION, sessionKey)
                putExtra(MainActivity.EXTRA_OPENCLAW_AGENT, agentId)
            }
        },
        debugLog = { AndroidAgentLogger.debug(it) },
    )

    fun createOpenClawStore(
        application: Application,
        scope: CoroutineScope,
    ): OpenClawHomeStore = OpenClawHomeStore(
        client = createOpenClawClient(),
        settings = OpenClawSettings(application),
        notifier = createOpenClawNotifier(application),
        onEvictConnections = { AgentHttpClient.client.connectionPool.evictAll() },
    ).apply {
        attach(scope)
        syncFcmToken(this)
    }

    fun syncFcmToken(store: OpenClawHomeStore, force: Boolean = false) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    store.syncPushToken(token, force = force)
                }
            }
        }
    }

    fun createNetworkMonitor(
        application: Application,
        store: OpenClawHomeStore,
    ): OpenClawNetworkMonitor = OpenClawNetworkMonitor(application) {
        store.onNetworkChanged()
    }.apply { start() }

    fun OpenClawContextUsage.toUi(): AgentContextUsageUi = AgentContextUsageUi(
        contextTokens = contextTokens,
        contextWindow = contextWindow,
    )
}
