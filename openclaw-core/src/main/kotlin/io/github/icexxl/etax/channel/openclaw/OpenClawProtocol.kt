package io.github.icexxl.etax.channel.openclaw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与 openclaw-channel-eta 插件约定的线协议。
 *
 * 入站：POST /plugins/eta/inbound  { conversationId, text, clientMsgId }
 * 出站：GET  /plugins/eta/pull?since=<seq>&wait_ms=<ms>  → { ok, records[] }
 * 确认：GET  /plugins/eta/ack?seq=<seq>                  → { ok, ackedSeq }
 */
@Serializable
data class InboundRequest(
    val conversationId: String,
    val text: String,
    val clientMsgId: String,
)

@Serializable
data class InboundResponse(
    val ok: Boolean = false,
    val accepted: Boolean = false,
    val messageId: String? = null,
    val error: String? = null,
)

@Serializable
data class OutboundRecord(
    val seq: Long,
    val conversationId: String,
    val role: String,
    val text: String,
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    val reasoning: String? = null,
    val tools: List<ToolStepDto> = emptyList(),
    val runId: String? = null,
    val timestamp: Long = 0L,
)

@Serializable
data class PullResponse(
    val ok: Boolean = false,
    val records: List<OutboundRecord> = emptyList(),
    val error: String? = null,
)

@Serializable
data class AckResponse(
    val ok: Boolean = false,
    @SerialName("ackedSeq") val ackedSeq: Long = 0L,
    val error: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val channel: String = "",
    val ackedSeq: Long = 0L,
    val error: String? = null,
)

/** 助手执行过程中的单个工具步骤摘要。 */
data class OpenClawToolStep(
    val name: String,
    val summary: String = "",
    val at: Long = 0L,
)

/** 本地展示用的消息模型，与线协议解耦。 */
data class OpenClawMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val text: String,
    val timestamp: Long,
    val pending: Boolean = false,
    val failed: Boolean = false,
    /** 附件来源（交给 /plugins/eta/file 代理下载）。 */
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    /** 可选：思考过程文本（折叠展示）。 */
    val reasoning: String? = null,
    /** 可选：工具调用步骤列表。 */
    val tools: List<OpenClawToolStep> = emptyList(),
    /** 是否为正在生成的实时流式气泡。 */
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/**
 * 出站记录 → 本地消息。
 *
 * 只保留 assistant / system；用户消息由本地回显，服务端不会回传。
 */
fun OutboundRecord.toMessage(): OpenClawMessage? {
    val mappedRole = when (role) {
        "assistant" -> OpenClawMessage.Role.ASSISTANT
        "system" -> OpenClawMessage.Role.SYSTEM
        else -> return null
    }
    if (text.isBlank() && mediaUrl.isNullOrBlank()) return null
    return OpenClawMessage(
        id = "srv-$seq",
        conversationId = conversationId,
        role = mappedRole,
        text = text,
        timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis(),
        mediaUrl = mediaUrl,
        mediaName = mediaName,
        reasoning = reasoning?.takeIf { it.isNotBlank() },
        tools = tools.map { OpenClawToolStep(name = it.name, summary = it.summary, at = it.at) },
    )
}
