package io.github.icexxl.etax.channel.openclaw

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** 单连接 SSE 推送事件（插件 0.8.1+ `/plugins/eta/events`）。 */
sealed interface OpenClawSseEvent {
    data object Connected : OpenClawSseEvent
    data class Record(val record: OutboundRecord) : OpenClawSseEvent
    data class Stream(val snapshot: LiveStreamResponse) : OpenClawSseEvent
    data class SessionsChanged(val reason: String = "", val sessionKey: String? = null) : OpenClawSseEvent
}

/** 可取消的 SSE 订阅句柄。 */
interface OpenClawEventSubscription {
    fun cancel()
}

/**
 * openclaw-channel-eta 的 HTTP 客户端。
 *
 * 复用模块的 OkHttp 连接池；所有方法都在 IO 线程执行并把异常收敛成 Result，
 * 避免把网络细节泄漏到 UI 层。
 */
/** 渠道 HTTP 能力的抽象，便于在测试中注入假实现。 */
interface OpenClawApi {
    suspend fun health(config: OpenClawConfig): Result<HealthResponse>
    suspend fun agents(config: OpenClawConfig): Result<AgentsResponse>
    suspend fun sessions(
        config: OpenClawConfig,
        agentId: String,
        includeArchived: Boolean = false,
    ): Result<SessionsResponse>
    suspend fun history(
        config: OpenClawConfig,
        sessionKey: String,
        limit: Int,
        before: Long? = null,
    ): Result<HistoryResponse>
    suspend fun models(
        config: OpenClawConfig,
        agentId: String,
    ): Result<ModelsResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun setSessionModel(
        config: OpenClawConfig,
        sessionKey: String,
        model: String,
        modelProvider: String? = null,
        isDefault: Boolean = false,
    ): Result<SetSessionModelResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun stream(
        config: OpenClawConfig,
        sessionKey: String,
        sinceRev: Long = 0L,
        waitMs: Long = 0L,
    ): Result<LiveStreamResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun commands(
        config: OpenClawConfig,
        agentId: String,
    ): Result<CommandsResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun setSessionThinking(
        config: OpenClawConfig,
        sessionKey: String,
        thinkingLevel: String,
    ): Result<SetSessionThinkingResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun abortSession(
        config: OpenClawConfig,
        sessionKey: String,
    ): Result<AbortSessionResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun compactSession(
        config: OpenClawConfig,
        sessionKey: String,
        instructions: String? = null,
    ): Result<SessionCommandResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))
    suspend fun resetSession(
        config: OpenClawConfig,
        sessionKey: String,
    ): Result<SessionCommandResponse> = Result.failure(OpenClawClient.Failure.Http(404, "NOT_SUPPORTED"))

    /** 打开单连接 SSE 事件流；返回 null 表示该实现不支持 SSE（自动退回长轮询）。 */
    fun openEvents(
        config: OpenClawConfig,
        since: Long,
        sessionKey: String?,
        onEvent: (OpenClawSseEvent) -> Unit,
        onClosed: (httpCode: Int?, failure: Throwable?) -> Unit,
    ): OpenClawEventSubscription? = null
    suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String): Result<InboundResponse>
    suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long = 0): Result<PullResponse>
    suspend fun ack(config: OpenClawConfig, seq: Long): Result<AckResponse>

    /** 向插件注册本机 FCM 推送 Token，供离线/后台休眠时唤醒通知。 */
    suspend fun registerPushToken(
        config: OpenClawConfig,
        deviceId: String,
        token: String,
        platform: String = "android",
    ): Result<Unit> = Result.success(Unit)

    /** 注销本机 FCM 推送 Token。 */
    suspend fun unregisterPushToken(
        config: OpenClawConfig,
        deviceId: String,
        token: String = "",
    ): Result<Unit> = Result.success(Unit)

    /** 通过插件代理下载附件字节。 */
    suspend fun fetchMedia(config: OpenClawConfig, src: String): Result<ByteArray>

    /** 把本地文件上传给 OpenClaw（作为用户消息的附件）。 */
    suspend fun uploadMedia(
        config: OpenClawConfig,
        name: String,
        mime: String,
        text: String,
        bytes: ByteArray,
    ): Result<Unit>
}

class OpenClawClient(
    private val client: OkHttpClient = OkHttpClient(),
) : OpenClawApi {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sseClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val sessionEtags = ConcurrentHashMap<String, Pair<String, SessionsResponse>>()

    sealed class Failure(message: String) : Exception(message) {
        class NotConfigured : Failure("未配置 Gateway 地址或 Token")
        class InvalidUrl(val url: String) : Failure("地址无效：$url")
        class Http(val code: Int, val body: String) : Failure("HTTP $code")
        class Network(val io: IOException) : Failure(io.message ?: "网络错误")
        class Decode(cause: Throwable) : Failure("响应解析失败：${cause.message}")
    }

    override suspend fun health(config: OpenClawConfig): Result<HealthResponse> =
        request(config, "plugins/eta/health") { json.decodeFromString(it) }

    override suspend fun agents(config: OpenClawConfig): Result<AgentsResponse> =
        request(config, "plugins/eta/agents") { json.decodeFromString(it) }

    override suspend fun sessions(
        config: OpenClawConfig,
        agentId: String,
        includeArchived: Boolean,
    ): Result<SessionsResponse> = withContext(Dispatchers.IO) {
        val base = config.normalizedBaseUrl()
            ?: return@withContext Result.failure(
                if (config.baseUrl.isBlank()) Failure.NotConfigured() else Failure.InvalidUrl(config.baseUrl),
            )
        val builder = (base + "/plugins/eta/sessions").toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext Result.failure(Failure.InvalidUrl(base))
        builder.addQueryParameter("agentId", agentId)
        if (includeArchived) builder.addQueryParameter("includeArchived", "1")
        val cacheKey = "$base|$agentId|$includeArchived"
        val cached = sessionEtags[cacheKey]
        val reqBuilder = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer ${config.token}")
        if (cached != null) {
            reqBuilder.header("If-None-Match", cached.first)
        }
        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 304 && cached != null) {
                    return@withContext Result.success(cached.second)
                }
                val text = response.body.string()
                if (!response.isSuccessful) {
                    Result.failure(Failure.Http(response.code, text.take(512)))
                } else {
                    val businessError = businessErrorOrNull(text)
                    if (businessError != null) {
                        return@use Result.failure(Failure.Http(response.code, businessError))
                    }
                    runCatching { json.decodeFromString<SessionsResponse>(text) }
                        .fold(
                            onSuccess = { parsed ->
                                val etag = response.header("ETag")?.trim()
                                if (!etag.isNullOrEmpty()) {
                                    sessionEtags[cacheKey] = etag to parsed
                                }
                                Result.success(parsed)
                            },
                            onFailure = { Result.failure(Failure.Decode(it)) },
                        )
                }
            }
        } catch (io: IOException) {
            Result.failure(Failure.Network(io))
        }
    }

    override suspend fun history(
        config: OpenClawConfig,
        sessionKey: String,
        limit: Int,
        before: Long?,
    ): Result<HistoryResponse> = request(
        config = config,
        path = "plugins/eta/history",
        query = buildMap {
            put("sessionKey", sessionKey)
            put("limit", limit.toString())
            if (before != null) put("before", before.toString())
        },
    ) { json.decodeFromString(it) }

    override suspend fun models(
        config: OpenClawConfig,
        agentId: String,
    ): Result<ModelsResponse> = request(
        config = config,
        path = "plugins/eta/models",
        query = mapOf("agentId" to agentId),
    ) { json.decodeFromString(it) }

    override suspend fun setSessionModel(
        config: OpenClawConfig,
        sessionKey: String,
        model: String,
        modelProvider: String?,
        isDefault: Boolean,
    ): Result<SetSessionModelResponse> {
        val payload = json.encodeToString(
            SetSessionModelRequest.serializer(),
            SetSessionModelRequest(
                sessionKey = sessionKey,
                model = model,
                modelProvider = modelProvider,
                isDefault = isDefault,
            ),
        )
        return request(
            config = config,
            path = "plugins/eta/session/model",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString(it) }
    }

    override suspend fun stream(
        config: OpenClawConfig,
        sessionKey: String,
        sinceRev: Long,
        waitMs: Long,
    ): Result<LiveStreamResponse> = request(
        config = config,
        path = "plugins/eta/stream",
        query = mapOf(
            "sessionKey" to sessionKey,
            "sinceRev" to sinceRev.toString(),
            "wait_ms" to waitMs.toString(),
        ),
    ) { json.decodeFromString(it) }

    override suspend fun commands(
        config: OpenClawConfig,
        agentId: String,
    ): Result<CommandsResponse> = request(
        config = config,
        path = "plugins/eta/commands",
        query = mapOf("agentId" to agentId),
    ) { json.decodeFromString(it) }

    override suspend fun setSessionThinking(
        config: OpenClawConfig,
        sessionKey: String,
        thinkingLevel: String,
    ): Result<SetSessionThinkingResponse> {
        val payload = json.encodeToString(
            SetSessionThinkingRequest.serializer(),
            SetSessionThinkingRequest(sessionKey = sessionKey, thinkingLevel = thinkingLevel),
        )
        return request(
            config = config,
            path = "plugins/eta/session/thinking",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString(it) }
    }

    override suspend fun abortSession(
        config: OpenClawConfig,
        sessionKey: String,
    ): Result<AbortSessionResponse> {
        val payload = json.encodeToString(
            SessionKeyRequest.serializer(),
            SessionKeyRequest(sessionKey = sessionKey),
        )
        return request(
            config = config,
            path = "plugins/eta/session/abort",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString(it) }
    }

    override suspend fun compactSession(
        config: OpenClawConfig,
        sessionKey: String,
        instructions: String?,
    ): Result<SessionCommandResponse> {
        val payload = json.encodeToString(
            CompactSessionRequest.serializer(),
            CompactSessionRequest(sessionKey = sessionKey, instructions = instructions),
        )
        return request(
            config = config,
            path = "plugins/eta/session/compact",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString(it) }
    }

    override suspend fun resetSession(
        config: OpenClawConfig,
        sessionKey: String,
    ): Result<SessionCommandResponse> {
        val payload = json.encodeToString(
            SessionKeyRequest.serializer(),
            SessionKeyRequest(sessionKey = sessionKey),
        )
        return request(
            config = config,
            path = "plugins/eta/session/reset",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString(it) }
    }

    override fun openEvents(
        config: OpenClawConfig,
        since: Long,
        sessionKey: String?,
        onEvent: (OpenClawSseEvent) -> Unit,
        onClosed: (httpCode: Int?, failure: Throwable?) -> Unit,
    ): OpenClawEventSubscription? {
        val base = config.normalizedBaseUrl() ?: return null
        val builder = (base + "/plugins/eta/events").toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.addQueryParameter("since", since.toString())
        if (!sessionKey.isNullOrBlank()) {
            builder.addQueryParameter("sessionKey", sessionKey)
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val factory = EventSources.createFactory(sseClient)
        var cancelled = false
        val source = factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (!cancelled) onEvent(OpenClawSseEvent.Connected)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (cancelled) return
                    when (type) {
                        "record" -> runCatching { json.decodeFromString<OutboundRecord>(data) }
                            .getOrNull()?.let { onEvent(OpenClawSseEvent.Record(it)) }
                        "stream" -> runCatching { json.decodeFromString<LiveStreamResponse>(data) }
                            .getOrNull()?.let { onEvent(OpenClawSseEvent.Stream(it)) }
                        "sessions" -> {
                            val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                            val reason = obj?.get("reason")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val key = obj?.get("sessionKey")?.jsonPrimitive?.contentOrNull
                            onEvent(OpenClawSseEvent.SessionsChanged(reason, key))
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!cancelled) onClosed(null, null)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (!cancelled) onClosed(response?.code, t)
                }
            },
        )
        return object : OpenClawEventSubscription {
            override fun cancel() {
                cancelled = true
                source.cancel()
            }
        }
    }

    override suspend fun postInbound(
        config: OpenClawConfig,
        text: String,
        clientMsgId: String,
    ): Result<InboundResponse> {
        val payload = json.encodeToString(
            InboundRequest.serializer(),
            InboundRequest(
                conversationId = config.conversationId,
                text = text,
                clientMsgId = clientMsgId,
            ),
        )
        return request(
            config = config,
            path = "plugins/eta/inbound",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
        ) { json.decodeFromString<InboundResponse>(it) }
            .mapCatching { response ->
                // 200 但 accepted=false 代表网关没有接受这条消息
                if (!response.accepted) {
                    throw Failure.Http(202, response.error ?: "消息未被网关接受")
                }
                response
            }
    }

    override suspend fun pull(
        config: OpenClawConfig,
        since: Long,
        waitMs: Long,
    ): Result<PullResponse> = request(
        config = config,
        path = "plugins/eta/pull",
        query = mapOf("since" to since.toString(), "wait_ms" to waitMs.toString()),
    ) { json.decodeFromString(it) }

    override suspend fun ack(config: OpenClawConfig, seq: Long): Result<AckResponse> = request(
        config = config,
        path = "plugins/eta/ack",
        query = mapOf("seq" to seq.toString()),
    ) { json.decodeFromString(it) }

    override suspend fun fetchMedia(config: OpenClawConfig, src: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val base = config.normalizedBaseUrl()
                ?: return@withContext Result.failure(
                    if (config.baseUrl.isBlank()) Failure.NotConfigured() else Failure.InvalidUrl(config.baseUrl),
                )
            val builder = (base + "/plugins/eta/file").toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(Failure.InvalidUrl(base))
            builder.addQueryParameter("src", src)
            val request = Request.Builder()
                .url(builder.build())
                .header("Authorization", "Bearer ${config.token}")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Failure.Http(response.code, ""))
                    }
                    val body = response.body
                    // 先看声明长度，再流式读取并限长，避免异常网关把内存打满
                    if (body.contentLength() > MAX_MEDIA_BYTES) {
                        return@withContext Result.failure(Failure.Http(413, "附件过大"))
                    }
                    val source = body.source()
                    val buffer = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer, 8 * 1024)
                        if (read == -1L) break
                        total += read
                        if (total > MAX_MEDIA_BYTES) {
                            return@withContext Result.failure(Failure.Http(413, "附件过大"))
                        }
                    }
                    Result.success(buffer.readByteArray())
                }
            } catch (error: IOException) {
                Result.failure(Failure.Network(error))
            }
        }

    /** 插件用 {ok:false,error:"..."} 表达业务失败；HTTP 仍是 200，必须单独识别。 */
    private fun businessErrorOrNull(body: String): String? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
        if (ok != false) return null
        return obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "服务端返回失败"
    }

    override suspend fun uploadMedia(
        config: OpenClawConfig,
        name: String,
        mime: String,
        text: String,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val base = config.normalizedBaseUrl()
            ?: return@withContext Result.failure(
                if (config.baseUrl.isBlank()) Failure.NotConfigured() else Failure.InvalidUrl(config.baseUrl),
            )
        val builder = (base + "/plugins/eta/upload").toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext Result.failure(Failure.InvalidUrl(base))
        builder.addQueryParameter("conversationId", config.conversationId)
        builder.addQueryParameter("name", name)
        builder.addQueryParameter("type", mime)
        if (text.isNotBlank()) builder.addQueryParameter("text", text)
        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer ${config.token}")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Result.failure(Failure.Http(response.code, body.take(512)))
                } else {
                    val businessError = businessErrorOrNull(body)
                    if (businessError != null) {
                        Result.failure(Failure.Http(response.code, businessError))
                    } else {
                        Result.success(Unit)
                    }
                }
            }
        } catch (error: IOException) {
            Result.failure(Failure.Network(error))
        }
    }

    override suspend fun registerPushToken(
        config: OpenClawConfig,
        deviceId: String,
        token: String,
        platform: String,
    ): Result<Unit> {
        val payload = buildString {
            append("{\"deviceId\":")
            append(json.encodeToString(kotlinx.serialization.serializer<String>(), deviceId))
            append(",\"token\":")
            append(json.encodeToString(kotlinx.serialization.serializer<String>(), token))
            append(",\"platform\":")
            append(json.encodeToString(kotlinx.serialization.serializer<String>(), platform))
            append('}')
        }
        return request(
            config = config,
            path = "plugins/eta/push/register",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
            decode = {},
        )
    }

    override suspend fun unregisterPushToken(
        config: OpenClawConfig,
        deviceId: String,
        token: String,
    ): Result<Unit> {
        val payload = buildString {
            append("{\"deviceId\":")
            append(json.encodeToString(kotlinx.serialization.serializer<String>(), deviceId))
            append(",\"token\":")
            append(json.encodeToString(kotlinx.serialization.serializer<String>(), token))
            append('}')
        }
        return request(
            config = config,
            path = "plugins/eta/push/unregister",
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
            decode = {},
        )
    }

    private suspend fun <T> request(
        config: OpenClawConfig,
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: okhttp3.RequestBody? = null,
        decode: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val base = config.normalizedBaseUrl()
            ?: return@withContext Result.failure(
                if (config.baseUrl.isBlank()) Failure.NotConfigured() else Failure.InvalidUrl(config.baseUrl),
            )
        val builder = (base + "/" + path).toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext Result.failure(Failure.InvalidUrl(base))
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer ${config.token}")
            .apply {
                if (method == "POST") post(body ?: EMPTY_BODY)
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    Result.failure(Failure.Http(response.code, text.take(512)))
                } else {
                    val businessError = businessErrorOrNull(text)
                    if (businessError != null) {
                        return@use Result.failure(Failure.Http(response.code, businessError))
                    }
                    runCatching { decode(text) }
                        .fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { Result.failure(Failure.Decode(it)) },
                        )
                }
            }
        } catch (io: IOException) {
            Result.failure(Failure.Network(io))
        }
    }

    private companion object {
        const val MAX_MEDIA_BYTES = 64 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
    }
}
