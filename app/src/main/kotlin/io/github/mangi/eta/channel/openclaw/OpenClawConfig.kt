package io.github.mangi.eta.channel.openclaw

import android.content.Context
import androidx.core.content.edit

/**
 * OpenClaw 渠道连接配置。
 *
 * token 目前以明文保存在独立 SharedPreferences 中；与 MCP/语音的 Keystore 方案不一致，
 * 属于 M4 待改进项（见 openclaw-channel-eta 设计文档）。
 */
internal data class OpenClawConfig(
    val baseUrl: String = "",
    val token: String = "",
    val agentId: String = "main",
    val conversationId: String = "c_default",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** 去掉尾部斜杠并校验协议，非法返回 null。 */
    fun normalizedBaseUrl(): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
        return trimmed
    }
}

internal class OpenClawSettings(
    context: Context,
    private val tokenStore: OpenClawTokenStore = OpenClawSecretStore(context),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): OpenClawConfig = OpenClawConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        token = readToken(),
        agentId = prefs.getString(KEY_AGENT_ID, "main").orEmpty().ifBlank { "main" },
        conversationId = prefs.getString(KEY_CONVERSATION_ID, "c_default").orEmpty()
            .ifBlank { "c_default" },
    )

    fun save(config: OpenClawConfig) {
        tokenStore.setToken(config.token)
        prefs.edit {
            putString(KEY_BASE_URL, config.baseUrl.trim())
            putString(KEY_AGENT_ID, config.agentId.trim().ifBlank { "main" })
            putString(KEY_CONVERSATION_ID, config.conversationId.trim().ifBlank { "c_default" })
        }
    }

    /**
     * Token 走 Keystore；若检测到旧版本的明文条目，就地迁移并删除。
     */
    private fun readToken(): String {
        tokenStore.token()?.let { return it }
        val legacy = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        if (legacy.isNotEmpty()) {
            runCatching {
                tokenStore.setToken(legacy)
                prefs.edit { remove(KEY_TOKEN) }
            }
            return legacy
        }
        return ""
    }

    private companion object {
        const val PREFS_NAME = "openclaw_channel"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_AGENT_ID = "agent_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
    }
}
