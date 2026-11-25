package io.github.icexxl.etax.channel.openclaw

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * OpenClaw 渠道连接配置。
 *
 * Token 通过 [OpenClawSecretStore]（Android Keystore AES/GCM）加密存储，
 * 读取时自动兼容迁移旧版本 SharedPreferences 中的明文条目。
 */
data class OpenClawConfig(
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

class OpenClawSettings(
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

    /** 稳定的本机设备 ID，用于向 OpenClaw 插件注册/更新 FCM 推送 Token。 */
    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty().trim()
        if (existing.isNotEmpty()) return existing
        val created = "etax-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        prefs.edit { putString(KEY_DEVICE_ID, created) }
        return created
    }

    fun fcmToken(): String = prefs.getString(KEY_FCM_TOKEN, "").orEmpty()

    fun saveFcmToken(token: String) {
        prefs.edit { putString(KEY_FCM_TOKEN, token.trim()) }
    }

    /** 最近一次成功向服务端注册 FCM 时的签名（baseUrl|tokenHash），避免重复上报。 */
    fun fcmRegisteredSignature(): String = prefs.getString(KEY_FCM_REGISTERED_SIG, "").orEmpty()

    fun saveFcmRegisteredSignature(signature: String) {
        prefs.edit { putString(KEY_FCM_REGISTERED_SIG, signature) }
    }

    /** 后台休眠省电模式：切后台停止所有轮询，仅依赖 FCM 高优先级推送唤醒通知。 */
    fun backgroundSleepEnabled(): Boolean = prefs.getBoolean(KEY_BG_SLEEP, true)

    fun saveBackgroundSleepEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_BG_SLEEP, enabled) }
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
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_FCM_REGISTERED_SIG = "fcm_registered_sig"
        const val KEY_BG_SLEEP = "bg_sleep_enabled"
    }
}
