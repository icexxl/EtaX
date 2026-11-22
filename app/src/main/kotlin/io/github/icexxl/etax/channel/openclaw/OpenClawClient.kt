package io.github.icexxl.etax.channel.openclaw

import io.github.icexxl.etax.agent.model.AgentHttpClient
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * openclaw-channel-eta 的 HTTP 客户端。
 *
 * 复用模块的 OkHttp 连接池；所有方法都在 IO 线程执行并把异常收敛成 Result，
 * 避免把网络细节泄漏到 UI 层。
 */
/** 渠道 HTTP 能力的抽象，便于在测试中注入假实现。 */
internal interface OpenClawApi {
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
    suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String): Result<InboundResponse>
    suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long = 0): Result<PullResponse>
    suspend fun ack(config: OpenClawConfig, seq: Long): Result<AckResponse>

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

internal class OpenClawClient(
    private val client: okhttp3.OkHttpClient = AgentHttpClient.client,
) : OpenClawApi {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
    ): Result<SessionsResponse> = request(
        config = config,
        path = "plugins/eta/sessions",
        query = buildMap {
            put("agentId", agentId)
            if (includeArchived) put("includeArchived", "1")
        },
    ) { json.decodeFromString(it) }

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
                    val body = response.body ?: return@withContext Result.success(ByteArray(0))
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
                val body = response.body?.string().orEmpty()
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
                val text = response.body?.string().orEmpty()
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
