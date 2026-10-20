package io.github.mangi.eta.channel.openclaw

import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Eta 渠道的 WebSocket 客户端。
 *
 * 与服务端约定（见 openclaw-channel-eta/src/ws.ts）：
 *   ← { type: "snapshot", records: [...] }  建连补投
 *   ← { type: "message", record: {...} }    实时推送
 *   ← { type: "pong" }
 *   → { type: "ack", seq: n } / { type: "ping" }
 *
 * 连接失败时由调用方决定是否退回 HTTP 长轮询。
 */
internal class OpenClawWebSocket(
    private val client: OkHttpClient = defaultClient(),
) {
    sealed interface Event {
        data object Opened : Event
        data class Records(val records: List<OutboundRecord>, val snapshot: Boolean) : Event
        /** agent 处理中/结束的瞬时状态（不持久化）。 */
        data class Status(val conversationId: String, val state: String, val detail: String?) : Event
        data object Closed : Event
        data class Failed(val error: String) : Event
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    private var closedByUs = false

    /** 建立连接；返回 false 表示 URL/配置不合法，调用方应走轮询。 */
    fun connect(
        config: OpenClawConfig,
        since: Long,
        onEvent: (Event) -> Unit,
    ): Boolean {
        val base = config.normalizedBaseUrl() ?: return false
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> return false
        }
        val url = buildString {
            append(wsBase)
            append("/plugins/eta/ws?since=").append(since)
            append("&token=").append(java.net.URLEncoder.encode(config.token, "UTF-8"))
        }
        val request = Request.Builder().url(url).build()
        closedByUs = false
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(Event.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseWebSocketFrame(json, text)?.let(onEvent)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) onEvent(Event.Failed(t.message ?: "WebSocket 连接失败"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) onEvent(Event.Closed)
            }
        })
        return true
    }

    fun ack(seq: Long) {
        socket?.send("""{"type":"ack","seq":$seq}""")
    }

    fun ping() {
        socket?.send("""{"type":"ping"}""")
    }

    fun close() {
        closedByUs = true
        runCatching { socket?.close(1000, "bye") }
        socket = null
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连不设读超时
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 解析一帧服务端消息；非数据帧（pong 等）返回 null。
 *
 * 抽成顶层函数以便单测直接喂真实帧（格式取自插件 ws.ts 的实际输出）。
 */
internal fun parseWebSocketFrame(json: Json, text: String): OpenClawWebSocket.Event? = runCatching {
    val root = json.parseToJsonElement(text).jsonObject
    when (root["type"]?.jsonPrimitive?.content) {
        "snapshot" -> {
            val records = root["records"]?.let { element ->
                json.decodeFromJsonElement(ListSerializer(OutboundRecord.serializer()), element)
            }.orEmpty()
            OpenClawWebSocket.Event.Records(records, snapshot = true)
        }
        "status" -> {
            val status = root["status"]?.jsonObject
            val conversationId = status?.get("conversationId")?.jsonPrimitive?.content
            val state = status?.get("state")?.jsonPrimitive?.content
            if (conversationId != null && state != null) {
                OpenClawWebSocket.Event.Status(
                    conversationId = conversationId,
                    state = state,
                    detail = status["detail"]?.jsonPrimitive?.content,
                )
            } else {
                null
            }
        }
        "message" -> root["record"]?.let { element ->
            OpenClawWebSocket.Event.Records(
                records = listOf(json.decodeFromJsonElement(OutboundRecord.serializer(), element)),
                snapshot = false,
            )
        }
        else -> null
    }
}.getOrNull()
