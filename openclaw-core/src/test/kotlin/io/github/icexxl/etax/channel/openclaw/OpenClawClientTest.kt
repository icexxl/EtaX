package io.github.icexxl.etax.channel.openclaw

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用本地 HTTP 服务模拟 openclaw-channel-eta 的三个端点，
 * 端到端验证 Eta 客户端的请求形状与响应解析（不需要真实网关）。
 */
class OpenClawClientTest {

    private lateinit var server: HttpServer
    private lateinit var config: OpenClawConfig

    private val seen = mutableMapOf<String, String>()
    private var lastBody = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/plugins/eta/health") { exchange ->
            seen["health.auth"] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (!requireAuth(exchange)) return@createContext
            respond(exchange, 200, """{"ok":true,"channel":"eta","ackedSeq":2}""")
        }
        server.createContext("/plugins/eta/pull") { exchange ->
            seen["pull.query"] = exchange.requestURI.query.orEmpty()
            seen["pull.auth"] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (!requireAuth(exchange)) return@createContext
            respond(
                exchange,
                200,
                """{"ok":true,"records":[{"conversationId":"c_a","role":"assistant","text":"hi","seq":9,"timestamp":1700000000000}]}""",
            )
        }
        server.createContext("/plugins/eta/ack") { exchange ->
            seen["ack.query"] = exchange.requestURI.query.orEmpty()
            if (!requireAuth(exchange)) return@createContext
            respond(exchange, 200, """{"ok":true,"ackedSeq":9}""")
        }
        server.createContext("/plugins/eta/inbound") { exchange ->
            lastBody = exchange.requestBody.readBytes().decodeToString()
            seen["inbound.auth"] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (!requireAuth(exchange)) return@createContext
            respond(exchange, 202, """{"ok":true,"accepted":true,"messageId":"m1"}""")
        }
        server.start()
        config = OpenClawConfig(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            token = "test-token",
            conversationId = "c_a",
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `health sends bearer token and parses ackedSeq`() = runBlocking {
        val result = OpenClawClient().health(config)
        assertTrue(result.isSuccess)
        assertEquals(2L, result.getOrThrow().ackedSeq)
        assertEquals("Bearer test-token", seen["health.auth"])
    }

    @Test
    fun `pull passes cursor and decodes records`() = runBlocking {
        val result = OpenClawClient().pull(config, since = 7, waitMs = 0)
        assertTrue(result.isSuccess)
        val record = result.getOrThrow().records.single()
        assertEquals(9L, record.seq)
        assertEquals("c_a", record.conversationId)
        assertTrue(seen["pull.query"].orEmpty().contains("since=7"))
    }

    @Test
    fun `inbound posts conversation and client message id`() = runBlocking {
        val result = OpenClawClient().postInbound(config, "hello", "local-1")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().accepted)
        assertTrue(lastBody.contains("\"conversationId\":\"c_a\""))
        assertTrue(lastBody.contains("\"clientMsgId\":\"local-1\""))
        assertEquals("Bearer test-token", seen["inbound.auth"])
    }

    @Test
    fun `ack reports sequence`() = runBlocking {
        val result = OpenClawClient().ack(config, 9)
        assertTrue(result.isSuccess)
        assertEquals(9L, result.getOrThrow().ackedSeq)
        assertTrue(seen["ack.query"].orEmpty().contains("seq=9"))
    }

    @Test
    fun `unauthorized surfaces as http failure`() = runBlocking {
        val unauthorized = OpenClawConfig(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            token = "",
        )
        val result = OpenClawClient().health(unauthorized)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OpenClawClient.Failure.Http)
    }

    @Test
    fun `invalid base url fails without network`() = runBlocking {
        val result = OpenClawClient().health(OpenClawConfig(baseUrl = "not-a-url", token = "x"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OpenClawClient.Failure.InvalidUrl)
    }

    /** 与插件一致：每个端点都校验 Bearer Token。 */
    private fun requireAuth(exchange: com.sun.net.httpserver.HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        if (header != "Bearer test-token") {
            respond(exchange, 401, """{"ok":false,"error":"UNAUTHORIZED"}""")
            return false
        }
        return true
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
