package io.github.icexxl.etax.channel.openclaw

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
    /** 避免日志/异常里打印出 token 明文。 */
    override fun toString(): String =
        "OpenClawConfig(baseUrl=$baseUrl, token=***, agentId=$agentId, conversationId=$conversationId)"

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

    /** 主界面要显示的 agent；空表示不限制（显示服务端返回的全部）。 */
    fun visibleAgentIds(): List<String> =
        prefs.getString(KEY_VISIBLE_AGENTS, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 已消费到的队列游标（进程重启后继续增量拉取，避免重复 ack 或丢消息）。 */
    fun lastSeq(): Long = prefs.getLong(KEY_LAST_SEQ, 0L)

    fun saveLastSeq(seq: Long) {
        prefs.edit { putLong(KEY_LAST_SEQ, seq.coerceAtLeast(0L)) }
    }

    fun lastAgentId(): String = prefs.getString(KEY_LAST_AGENT, "").orEmpty()

    fun saveLastAgentId(agentId: String) {
        prefs.edit { putString(KEY_LAST_AGENT, agentId) }
    }

    fun lastSessionKey(): String = prefs.getString(KEY_LAST_SESSION, "").orEmpty()

    fun saveLastSessionKey(sessionKey: String) {
        prefs.edit { putString(KEY_LAST_SESSION, sessionKey) }
    }

    fun saveVisibleAgentIds(ids: List<String>) {
        prefs.edit { putString(KEY_VISIBLE_AGENTS, ids.distinct().joinToString(",")) }
    }

    /**
     * 各会话最后一次已读时间（毫秒），用于重启后恢复未读标记。
     * 存储为 `sessionKey<TAB>timestamp` 每行一条；session key 不含制表符/换行。
     */
    fun loadLastSeenAt(): Map<String, Long> {
        val raw = prefs.getString(KEY_LAST_SEEN_AT, "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        raw.lineSequence().forEach { line ->
            val separator = line.indexOf('\t')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).toLongOrNull() ?: return@forEach
            if (key.isNotBlank() && value > 0) result[key] = value
        }
        return result
    }

    fun saveLastSeenAt(values: Map<String, Long>) {
        val encoded = values.entries
            .asSequence()
            .filter { (key, value) -> key.isNotBlank() && value > 0 }
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key\t$value" }
        prefs.edit { putString(KEY_LAST_SEEN_AT, encoded) }
    }

    /** 侧栏是否显示归档会话。 */
    fun showArchivedSessions(): Boolean = prefs.getBoolean(KEY_SHOW_ARCHIVED, false)

    fun saveShowArchivedSessions(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_ARCHIVED, show) }
    }

    fun save(config: OpenClawConfig) {
        val previous = load()
        tokenStore.setToken(config.token)
        prefs.edit {
            putString(KEY_BASE_URL, config.baseUrl.trim())
            putString(KEY_AGENT_ID, config.agentId.trim().ifBlank { "main" })
            putString(KEY_CONVERSATION_ID, config.conversationId.trim().ifBlank { "c_default" })
            if (previous.normalizedBaseUrl() != config.normalizedBaseUrl() || previous.token != config.token) {
                remove(KEY_LAST_SEQ)
                remove(KEY_LAST_AGENT)
                remove(KEY_LAST_SESSION)
                remove(KEY_LAST_SEEN_AT)
            }
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
        const val KEY_VISIBLE_AGENTS = "visible_agents"
        const val KEY_LAST_SEQ = "last_seq"
        const val KEY_LAST_AGENT = "last_agent"
        const val KEY_LAST_SESSION = "last_session"
        const val KEY_LAST_SEEN_AT = "last_seen_at"
        const val KEY_SHOW_ARCHIVED = "show_archived"
    }
}
