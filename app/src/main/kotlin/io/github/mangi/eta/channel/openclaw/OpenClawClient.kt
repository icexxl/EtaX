package io.github.mangi.eta.channel.openclaw

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * openclaw-channel-eta 的 HTTP 客户端。
 *
 * 复用模块的 OkHttp 连接池；所有方法都在 IO 线程执行并把异常收敛成 Result，
 * 避免把网络细节泄漏到 UI 层。
 */
/** 渠道 HTTP 能力的抽象，便于在测试中注入假实现。 */
internal interface OpenClawApi {
    suspend fun health(config: OpenClawConfig): Result<HealthResponse>
    suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String): Result<InboundResponse>
    suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long = 0): Result<PullResponse>
    suspend fun ack(config: OpenClawConfig, seq: Long): Result<AckResponse>
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
        ) { json.decodeFromString(it) }
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
    }
}
