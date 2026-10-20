package io.github.mangi.eta.channel.openclaw

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * OpenClaw 渠道的会话状态机（多会话版）。
 *
 * - 每个会话 id 对应 Gateway 侧一个独立 session（`agent:<agentId>:eta:<id>`）；
 * - 出站记录按 conversationId 归入各自会话，当前会话即时可见；
 * - 消息与会话列表持久化到 [OpenClawHistory]，杀进程后仍在。
 */
internal class OpenClawChatStore(
    private val client: OpenClawApi,
    private val settings: OpenClawSettings,
    private val history: OpenClawHistory,
    private val webSocket: OpenClawWebSocket = OpenClawWebSocket(),
) {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class Conversation(
        val id: String,
        val lastMessageAt: Long,
        val preview: String,
        val unread: Int = 0,
    )

    data class UiState(
        val config: OpenClawConfig = OpenClawConfig(),
        val connection: Connection = Connection.DISCONNECTED,
        val conversations: List<Conversation> = emptyList(),
        val activeConversationId: String = "c_default",
        val messages: List<OpenClawMessage> = emptyList(),
        val lastSeq: Long = 0L,
        val sending: Boolean = false,
        /** 当前会话 agent 是否仍在处理（来自 WS status 事件）。 */
        val running: Boolean = false,
        val error: String? = null,
    )

    private val lock = Any()
    private var messagesByConversation: Map<String, List<OpenClawMessage>> = emptyMap()
    /** 非当前会话的未读计数，仅内存保存。 */
    private val unreadByConversation = mutableMapOf<String, Int>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var scope: CoroutineScope? = null
    /** 用户是否希望保持连接；断开后不再自动重连。 */
    private var wantsConnection = false

    init {
        val config = settings.load()
        val loaded = history.load()
        messagesByConversation = loaded.conversations
        _state.value = buildState(
            config = config,
            connection = Connection.DISCONNECTED,
            activeId = config.conversationId,
            sending = false,
            error = null,
            lastSeq = loaded.lastSeq,
        )
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    fun saveConfig(config: OpenClawConfig) {
        settings.save(config)
        _state.value = buildState(
            config = config,
            connection = _state.value.connection,
            activeId = config.conversationId,
            sending = _state.value.sending,
            error = null,
            lastSeq = _state.value.lastSeq,
        )
    }

    fun connect() {
        val config = _state.value.config
        if (!config.isConfigured) {
            _state.value = _state.value.copy(
                connection = Connection.ERROR,
                error = "请先填写 Gateway 地址与 Token",
            )
            return
        }
        val activeScope = scope ?: return
        if (wantsConnection) return
        wantsConnection = true
        _state.value = _state.value.copy(connection = Connection.CONNECTING, error = null)
        val started = webSocket.connect(config, _state.value.lastSeq) { event ->
            handleWebSocketEvent(event, config)
        }
        if (!started) startPolling(activeScope, config)
    }

    private fun startPolling(activeScope: CoroutineScope, config: OpenClawConfig) {
        if (pollJob?.isActive == true) return
        pollJob = activeScope.launch { pollLoop(config) }
    }

    /** WebSocket 事件：建连/补投/推送/断开。断开后回落到长轮询。 */
    internal fun handleWebSocketEvent(event: OpenClawWebSocket.Event, config: OpenClawConfig) {
        when (event) {
            OpenClawWebSocket.Event.Opened ->
                _state.value = _state.value.copy(connection = Connection.CONNECTED, error = null)

            is OpenClawWebSocket.Event.Records -> {
                _state.value = _state.value.copy(connection = Connection.CONNECTED, error = null)
                if (event.records.isNotEmpty()) {
                    appendMessages(event.records.mapNotNull { it.toMessage() })
                    if (event.records.any { it.role == "assistant" || it.role == "system" }) {
                        _state.value = _state.value.copy(running = false)
                    }
                    val maxSeq = event.records.maxOf { it.seq }
                    if (maxSeq > _state.value.lastSeq) {
                        _state.value = _state.value.copy(lastSeq = maxSeq)
                        webSocket.ack(maxSeq)
                    }
                }
            }

            is OpenClawWebSocket.Event.Status -> {
                if (event.conversationId == _state.value.activeConversationId) {
                    _state.value = _state.value.copy(
                        running = event.state == "running",
                    )
                }
            }

            OpenClawWebSocket.Event.Closed, is OpenClawWebSocket.Event.Failed -> {
                if (!wantsConnection) return
                webSocket.close()
                _state.value = _state.value.copy(connection = Connection.CONNECTING)
                scope?.let { startPolling(it, config) }
            }
        }
    }

    fun disconnect() {
        wantsConnection = false
        webSocket.close()
        pollJob?.cancel()
        pollJob = null
        _state.value = _state.value.copy(connection = Connection.DISCONNECTED)
    }

    /** 切换会话：更新配置并刷新当前消息。 */
    fun switchConversation(conversationId: String) {
        val id = conversationId.trim().ifEmpty { return }
        if (id == _state.value.activeConversationId) return
        unreadByConversation.remove(id)
        val config = _state.value.config.copy(conversationId = id)
        settings.save(config)
        _state.value = buildState(
            config = config,
            connection = _state.value.connection,
            activeId = id,
            sending = false,
            error = null,
            lastSeq = _state.value.lastSeq,
        )
    }

    /** 新建会话并切换过去，返回新会话 id。 */
    fun createConversation(): String {
        val id = "c_" + UUID.randomUUID().toString().replace("-", "").take(12)
        switchConversation(id)
        return id
    }

    fun deleteConversation(conversationId: String) {
        synchronized(lock) {
            messagesByConversation = messagesByConversation - conversationId
            history.save(messagesByConversation)
        }
        if (_state.value.activeConversationId == conversationId) {
            val next = _state.value.conversations.firstOrNull { it.id != conversationId }?.id
                ?: "c_default"
            switchConversation(next)
        } else {
            refreshConversations()
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val config = _state.value.config
        if (!config.isConfigured) {
            _state.value = _state.value.copy(error = "请先填写 Gateway 地址与 Token")
            return
        }
        val activeScope = scope ?: return
        val conversationId = _state.value.activeConversationId
        val localId = "local-" + UUID.randomUUID()
        appendMessages(
            listOf(
                OpenClawMessage(
                    id = localId,
                    conversationId = conversationId,
                    role = OpenClawMessage.Role.USER,
                    text = body,
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        )
        _state.value = _state.value.copy(sending = true, error = null)
        activeScope.launch {
            client.postInbound(config.copy(conversationId = conversationId), body, localId).fold(
                onSuccess = { _state.value = _state.value.copy(sending = false) },
                onFailure = { failure ->
                    updateMessage(localId) { it.copy(pending = false, failed = true) }
                    _state.value = _state.value.copy(
                        sending = false,
                        error = failure.message ?: "发送失败",
                    )
                },
            )
        }
    }

    fun retry(messageId: String) {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (!message.failed) return
        updateMessage(messageId) { it.copy(failed = false, pending = true) }
        val activeScope = scope ?: return
        activeScope.launch {
            client.postInbound(_state.value.config, message.text, messageId).fold(
                onSuccess = {
                    updateMessage(messageId) { it.copy(failed = false, pending = false) }
                },
                onFailure = {
                    updateMessage(messageId) { it.copy(failed = true, pending = false) }
                },
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun pollLoop(config: OpenClawConfig) {
        var backoffMs = 1_000L
        var connectedOnce = false
        while (scope?.isActive == true) {
            val since = _state.value.lastSeq
            client.pull(config, since, POLL_WAIT_MS).fold(
                onSuccess = { response ->
                    backoffMs = 1_000L
                    if (!connectedOnce) {
                        connectedOnce = true
                        _state.value = _state.value.copy(
                            connection = Connection.CONNECTED,
                            error = null,
                        )
                    }
                    val records = response.records
                    if (records.isNotEmpty()) {
                        appendMessages(records.mapNotNull { it.toMessage() })
                        val maxSeq = records.maxOf { it.seq }
                        _state.value = _state.value.copy(lastSeq = maxSeq)
                        synchronized(lock) {
                            history.save(messagesByConversation, maxSeq)
                        }
                        client.ack(config, maxSeq)
                    }
                },
                onFailure = { failure ->
                    connectedOnce = false
                    _state.value = _state.value.copy(
                        connection = Connection.ERROR,
                        error = failure.message ?: "连接失败",
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                },
            )
        }
        _state.value = _state.value.copy(connection = Connection.DISCONNECTED)
    }

    internal fun appendMessages(incoming: List<OpenClawMessage>) {
        if (incoming.isEmpty()) return
        val changed = synchronized(lock) {
            var changed = false
            val updated = messagesByConversation.toMutableMap()
            incoming.groupBy { it.conversationId }.forEach { (conversationId, batch) ->
                val existing = updated[conversationId].orEmpty()
                val known = existing.asSequence().map { it.id }.toHashSet()
                val fresh = batch.filter { known.add(it.id) }
                if (fresh.isNotEmpty()) {
                    updated[conversationId] = (existing + fresh).sortedBy { it.timestamp }
                    changed = true
                }
            }
            if (changed) {
                messagesByConversation = updated
                history.save(updated, _state.value.lastSeq)
                val active = _state.value.activeConversationId
                incoming.groupBy { it.conversationId }.forEach { (conversationId, batch) ->
                    if (conversationId != active) {
                        unreadByConversation[conversationId] =
                            (unreadByConversation[conversationId] ?: 0) + batch.count { it.role != OpenClawMessage.Role.USER }
                    }
                }
            }
            changed
        }
        if (changed) refreshConversations()
    }

    private fun updateMessage(id: String, transform: (OpenClawMessage) -> OpenClawMessage) {
        synchronized(lock) {
            val updated = messagesByConversation.mapValues { (_, messages) ->
                messages.map { if (it.id == id) transform(it) else it }
            }
            messagesByConversation = updated
            history.save(updated)
        }
        refreshConversations()
    }

    private fun refreshConversations() {
        val current = _state.value
        _state.value = current.copy(
            messages = messagesByConversation[current.activeConversationId].orEmpty(),
            conversations = conversationSummaries(current.activeConversationId),
        )
    }

    private fun buildState(
        config: OpenClawConfig,
        connection: Connection,
        activeId: String,
        sending: Boolean,
        error: String?,
        lastSeq: Long,
    ): UiState = UiState(
        config = config,
        connection = connection,
        conversations = conversationSummaries(activeId),
        activeConversationId = activeId,
        messages = messagesByConversation[activeId].orEmpty(),
        lastSeq = lastSeq,
        sending = sending,
        error = error,
    )

    /** 会话摘要：最近消息时间倒序；没有消息的会话排在最后。 */
    private fun conversationSummaries(activeId: String): List<Conversation> {
        val ids = (messagesByConversation.keys + activeId).toMutableSet()
        return ids.map { id ->
            val last = messagesByConversation[id].orEmpty().lastOrNull()
            Conversation(
                id = id,
                lastMessageAt = last?.timestamp ?: 0L,
                preview = last?.text?.replace('\n', ' ')?.take(40).orEmpty(),
                unread = unreadByConversation[id] ?: 0,
            )
        }.sortedWith(
            compareByDescending<Conversation> { it.lastMessageAt }.thenBy { it.id },
        )
    }

    private companion object {
        const val POLL_WAIT_MS = 25_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
