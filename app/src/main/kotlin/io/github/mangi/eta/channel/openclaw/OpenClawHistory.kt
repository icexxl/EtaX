package io.github.mangi.eta.channel.openclaw

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地消息与会话持久化。
 *
 * 用单个 JSON 文件保存（不引入 Room 迁移）：结构简单、失败可整体丢弃，
 * 每个会话最多保留 [MAX_MESSAGES_PER_CONVERSATION] 条。
 */
internal class OpenClawHistory(context: Context) {

    @Serializable
    data class StoredMessage(
        val id: String,
        val role: String,
        val text: String,
        val timestamp: Long,
    )

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        val conversations: Map<String, List<StoredMessage>> = emptyMap(),
        /** 已消费到的最大 seq；重启后据此继续拉取，避免重复补投全部历史。 */
        val lastSeq: Long = 0L,
    )

    data class Loaded(
        val conversations: Map<String, List<OpenClawMessage>>,
        val lastSeq: Long,
    )

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyMap(), 0L)
        return runCatching {
            val snapshot = json.decodeFromString(Snapshot.serializer(), file.readText())
            val conversations = snapshot.conversations.mapValues { (conversationId, messages) ->
                messages.mapNotNull { stored ->
                    val role = when (stored.role) {
                        "assistant" -> OpenClawMessage.Role.ASSISTANT
                        "system" -> OpenClawMessage.Role.SYSTEM
                        "user" -> OpenClawMessage.Role.USER
                        else -> return@mapNotNull null
                    }
                    OpenClawMessage(
                        id = stored.id,
                        conversationId = conversationId,
                        role = role,
                        text = stored.text,
                        timestamp = stored.timestamp,
                    )
                }
            }
            Loaded(conversations = conversations, lastSeq = snapshot.lastSeq.coerceAtLeast(0L))
        }.getOrDefault(Loaded(emptyMap(), 0L))
    }

    fun save(messagesByConversation: Map<String, List<OpenClawMessage>>, lastSeq: Long = 0L) {
        runCatching {
            val snapshot = Snapshot(
                lastSeq = lastSeq.coerceAtLeast(0L),
                conversations = messagesByConversation.mapValues { (_, messages) ->
                    messages.takeLast(MAX_MESSAGES_PER_CONVERSATION).map { message ->
                        StoredMessage(
                            id = message.id,
                            role = when (message.role) {
                                OpenClawMessage.Role.USER -> "user"
                                OpenClawMessage.Role.ASSISTANT -> "assistant"
                                OpenClawMessage.Role.SYSTEM -> "system"
                            },
                            text = message.text,
                            timestamp = message.timestamp,
                        )
                    }
                },
            )
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "openclaw_history.json"
        const val MAX_MESSAGES_PER_CONVERSATION = 200
    }
}
