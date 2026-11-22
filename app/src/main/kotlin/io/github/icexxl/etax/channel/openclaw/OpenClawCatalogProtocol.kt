package io.github.icexxl.etax.channel.openclaw

import kotlinx.serialization.Serializable

/**
 * M1 插件新增端点的线协议。
 *
 * GET /plugins/eta/agents                     → AgentsResponse
 * GET /plugins/eta/sessions?agentId=&includeArchived= → SessionsResponse
 * GET /plugins/eta/history?sessionKey=&limit=&before=  → HistoryResponse
 */

@Serializable
internal data class AgentDto(
    val id: String,
    val name: String = "",
    val channels: List<String> = emptyList(),
    val isDefault: Boolean = false,
)

@Serializable
internal data class AgentsResponse(
    val ok: Boolean = false,
    val agents: List<AgentDto> = emptyList(),
    val defaultAgentId: String = "",
    val error: String? = null,
)

@Serializable
internal data class SessionDto(
    val sessionKey: String,
    val label: String = "",
    val agentId: String = "",
    val channel: String? = null,
    val updatedAt: Long = 0L,
    val hasActiveRun: Boolean = false,
    /** OpenClaw 侧置顶；置顶会话排在前面。 */
    val pinned: Boolean = false,
    /** 已归档；侧栏默认不请求，打开「显示归档」后才会出现。 */
    val archived: Boolean = false,
)

@Serializable
internal data class SessionsResponse(
    val ok: Boolean = false,
    val agentId: String = "",
    val sessions: List<SessionDto> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class HistoryDto(
    val role: String,
    val text: String,
    val at: Long = 0L,
    val mediaUrl: String? = null,
    val mediaName: String? = null,
)

@Serializable
internal data class HistoryResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val messages: List<HistoryDto> = emptyList(),
    /** 是否还有更早的消息（顶部「加载更早」用）。 */
    val hasMore: Boolean = false,
    /** 下一页游标；hasMore=false 时为 null。 */
    val nextBefore: Long? = null,
    val error: String? = null,
)

/** 历史消息 → 本地消息模型。 */
internal fun HistoryDto.toMessage(): OpenClawMessage? {
    val mapped = when (role) {
        "user" -> OpenClawMessage.Role.USER
        "assistant" -> OpenClawMessage.Role.ASSISTANT
        "system" -> OpenClawMessage.Role.SYSTEM
        else -> return null
    }
    if (text.isBlank() && mediaUrl.isNullOrBlank()) return null
    return OpenClawMessage(
        id = "hist-$at-${text.hashCode()}",
        conversationId = "",
        role = mapped,
        text = text,
        timestamp = if (at > 0) at else System.currentTimeMillis(),
        mediaUrl = mediaUrl,
        mediaName = mediaName,
    )
}
