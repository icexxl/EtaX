package io.github.mangi.eta.channel.openclaw

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
internal data class InboundRequest(
    val conversationId: String,
    val text: String,
    val clientMsgId: String,
)

@Serializable
internal data class InboundResponse(
    val ok: Boolean = false,
    val accepted: Boolean = false,
    val messageId: String? = null,
    val error: String? = null,
)

@Serializable
internal data class OutboundRecord(
    val seq: Long,
    val conversationId: String,
    val role: String,
    val text: String,
    val runId: String? = null,
    val timestamp: Long = 0L,
)

@Serializable
internal data class PullResponse(
    val ok: Boolean = false,
    val records: List<OutboundRecord> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class AckResponse(
    val ok: Boolean = false,
    @SerialName("ackedSeq") val ackedSeq: Long = 0L,
    val error: String? = null,
)

@Serializable
internal data class HealthResponse(
    val ok: Boolean = false,
    val channel: String = "",
    val ackedSeq: Long = 0L,
    val error: String? = null,
)

/** 本地展示用的消息模型，与线协议解耦。 */
internal data class OpenClawMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val text: String,
    val timestamp: Long,
    val pending: Boolean = false,
    val failed: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/**
 * 出站记录 → 本地消息。
 *
 * 只保留 assistant / system；用户消息由本地回显，服务端不会回传。
 */
internal fun OutboundRecord.toMessage(): OpenClawMessage? {
    val mappedRole = when (role) {
        "assistant" -> OpenClawMessage.Role.ASSISTANT
        "system" -> OpenClawMessage.Role.SYSTEM
        else -> return null
    }
    if (text.isBlank()) return null
    return OpenClawMessage(
        id = "srv-$seq",
        conversationId = conversationId,
        role = mappedRole,
        text = text,
        timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis(),
    )
}
