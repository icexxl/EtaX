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
data class AgentDto(
    val id: String,
    val name: String = "",
    val channels: List<String> = emptyList(),
    val isDefault: Boolean = false,
    /** 可选：插件透传的默认模型与上下文窗口上限。 */
    val model: String? = null,
    val modelProvider: String? = null,
    val contextTokens: Int? = null,
)

@Serializable
data class AgentsResponse(
    val ok: Boolean = false,
    val agents: List<AgentDto> = emptyList(),
    val defaultAgentId: String = "",
    val error: String? = null,
)

@Serializable
data class SessionDto(
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
    /** 可选：插件从 OpenClaw session store 透传的当前模型与上下文用量。 */
    val model: String? = null,
    val modelProvider: String? = null,
    val totalTokens: Int? = null,
    val contextTokens: Int? = null,
    val thinkingLevel: String? = null,
    val compactionCount: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheRead: Int? = null,
    val cacheWrite: Int? = null,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class SessionsResponse(
    val ok: Boolean = false,
    val agentId: String = "",
    val sessions: List<SessionDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ToolStepDto(
    val name: String,
    val summary: String = "",
    val at: Long = 0L,
)

@Serializable
data class HistoryDto(
    val role: String,
    val text: String,
    val at: Long = 0L,
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    val reasoning: String? = null,
    val tools: List<ToolStepDto> = emptyList(),
)

@Serializable
data class HistoryResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val messages: List<HistoryDto> = emptyList(),
    /** 是否还有更早的消息（顶部「加载更早」用）。 */
    val hasMore: Boolean = false,
    /** 下一页游标；hasMore=false 时为 null。 */
    val nextBefore: Long? = null,
    /** 可选：历史接口附带的当前模型与上下文用量。 */
    val model: String? = null,
    val modelProvider: String? = null,
    val totalTokens: Int? = null,
    val contextTokens: Int? = null,
    val thinkingLevel: String? = null,
    val compactionCount: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheRead: Int? = null,
    val cacheWrite: Int? = null,
    val estimatedCostUsd: Double? = null,
    val error: String? = null,
)

@Serializable
data class ModelOptionDto(
    val model: String,
    val modelProvider: String? = null,
    val name: String? = null,
    val alias: String? = null,
    val contextTokens: Int? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class ModelsResponse(
    val ok: Boolean = false,
    val agentId: String = "",
    val defaultModel: String? = null,
    val defaultModelProvider: String? = null,
    val models: List<ModelOptionDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SetSessionModelRequest(
    val sessionKey: String,
    val model: String = "",
    val modelProvider: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class SetSessionModelResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val model: String? = null,
    val modelProvider: String? = null,
    val contextTokens: Int? = null,
    val isDefault: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class LiveStreamResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val rev: Long = 0L,
    val active: Boolean = false,
    val phase: String = "idle",
    val compacting: Boolean = false,
    val runId: String? = null,
    val text: String = "",
    val reasoning: String = "",
    val tools: List<ToolStepDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class CommandOptionDto(
    val command: String,
    val name: String = "",
    val description: String = "",
    val acceptsArgs: Boolean = false,
    val category: String = "command",
)

@Serializable
data class CommandsResponse(
    val ok: Boolean = false,
    val agentId: String = "",
    val commands: List<CommandOptionDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SessionKeyRequest(
    val sessionKey: String,
)

@Serializable
data class CompactSessionRequest(
    val sessionKey: String,
    val instructions: String? = null,
)

@Serializable
data class AbortSessionResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val aborted: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SessionCommandResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val summary: String = "",
    val totalTokens: Int? = null,
    val contextTokens: Int? = null,
    val compactionCount: Int? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class SetSessionThinkingRequest(
    val sessionKey: String,
    val thinkingLevel: String = "",
)

@Serializable
data class SetSessionThinkingResponse(
    val ok: Boolean = false,
    val sessionKey: String = "",
    val thinkingLevel: String? = null,
    val error: String? = null,
)

/** 历史消息 → 本地消息模型。 */
fun HistoryDto.toMessage(): OpenClawMessage? {
    val mapped = when (role) {
        "user" -> OpenClawMessage.Role.USER
        "assistant" -> OpenClawMessage.Role.ASSISTANT
        "system" -> OpenClawMessage.Role.SYSTEM
        else -> return null
    }
    if (text.isBlank() && mediaUrl.isNullOrBlank() && reasoning.isNullOrBlank() && tools.isEmpty()) return null
    return OpenClawMessage(
        id = "hist-$at-${text.hashCode()}-${tools.size}",
        conversationId = "",
        role = mapped,
        text = text,
        timestamp = if (at > 0) at else System.currentTimeMillis(),
        mediaUrl = mediaUrl,
        mediaName = mediaName,
        reasoning = reasoning?.takeIf { it.isNotBlank() },
        tools = tools.map { OpenClawToolStep(name = it.name, summary = it.summary, at = it.at) },
    )
}
