package io.github.icexxl.etax.data.repository

import android.content.Context
import io.github.icexxl.etax.agent.auth.EncryptedProviderCredentialStorage
import io.github.icexxl.etax.agent.auth.LiveProviderOAuthClient
import io.github.icexxl.etax.agent.auth.ProviderAccountSummary
import io.github.icexxl.etax.agent.auth.ProviderAuthException
import io.github.icexxl.etax.agent.auth.ProviderCredentialSessions
import io.github.icexxl.etax.agent.auth.ProviderCredentialBinding
import io.github.icexxl.etax.agent.auth.ProviderLoginPrompt
import io.github.icexxl.etax.agent.runtime.AgentRunController

/** 仅由 Eta 主进程初始化和访问；调用方在 I/O 线程执行，不将返回的令牌写入配置。 */
internal object ProviderCredentialRepository {
    @Volatile private var sessions: ProviderCredentialSessions? = null

    @Synchronized
    fun init(context: Context) {
        if (sessions != null) return
        sessions = ProviderCredentialSessions(EncryptedProviderCredentialStorage(context), LiveProviderOAuthClient())
    }

    fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        sessions().account(providerId, authMode)

    fun binding(providerId: String, authMode: String): ProviderCredentialBinding = sessions().binding(providerId, authMode)

    fun isCurrent(binding: ProviderCredentialBinding): Boolean = sessions().isCurrent(binding)

    fun login(providerId: String, authMode: String, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderAccountSummary =
        sessions().login(providerId, authMode, controller, onPrompt)

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String =
        sessions().accessToken(providerId, authMode, controller, rejectedAccessToken)

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        sessions().disconnect(providerId, authMode, controller)

    fun forget(providerId: String) = sessions().forget(providerId)

    private fun sessions(): ProviderCredentialSessions = sessions
        ?: throw ProviderAuthException("oauth_not_initialized", "登录服务尚未就绪，请重新打开 Eta")
}
