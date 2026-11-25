package io.github.icexxl.etax.channel.openclaw

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 openclaw-channel-eta 的线协议契约测试：字段名与插件实现保持一致。 */
class OpenClawProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pull response decodes plugin payload`() {
        // 与插件 queue.ts 输出的记录结构一致
        val payload = """
            {"ok":true,"records":[
              {"conversationId":"c_test","role":"assistant","text":"hello from cli","seq":1,"timestamp":1792499255009}
            ]}
        """.trimIndent()

        val response = json.decodeFromString(PullResponse.serializer(), payload)

        assertTrue(response.ok)
        assertEquals(1, response.records.size)
        val record = response.records.first()
        assertEquals(1L, record.seq)
        assertEquals("c_test", record.conversationId)
        assertEquals("assistant", record.role)
        assertEquals("hello from cli", record.text)
        assertEquals(1792499255009L, record.timestamp)
    }

    @Test
    fun `inbound request encodes expected field names`() {
        val encoded = json.encodeToString(
            InboundRequest.serializer(),
            InboundRequest(conversationId = "c_a", text = "hi", clientMsgId = "m1"),
        )

        assertTrue(encoded.contains("\"conversationId\":\"c_a\""))
        assertTrue(encoded.contains("\"text\":\"hi\""))
        assertTrue(encoded.contains("\"clientMsgId\":\"m1\""))
    }

    @Test
    fun `health and ack responses tolerate unknown fields`() {
        val health = json.decodeFromString(
            HealthResponse.serializer(),
            """{"ok":true,"channel":"eta","ackedSeq":7,"extra":"ignored"}""",
        )
        assertEquals(7L, health.ackedSeq)

        val ack = json.decodeFromString(AckResponse.serializer(), """{"ok":true,"ackedSeq":3}""")
        assertEquals(3L, ack.ackedSeq)
    }

    @Test
    fun `outbound record maps to assistant message`() {
        val message = OutboundRecord(
            seq = 5,
            conversationId = "c_a",
            role = "assistant",
            text = "answer",
            timestamp = 1_000,
        ).toMessage()

        requireNotNull(message)
        assertEquals("srv-5", message.id)
        assertEquals(OpenClawMessage.Role.ASSISTANT, message.role)
        assertEquals("answer", message.text)
    }

    @Test
    fun `outbound record drops blank text and unknown roles`() {
        val blank = OutboundRecord(1, "c", "assistant", "   ", timestamp = 1)
        assertNull(blank.toMessage())

        val userEcho = OutboundRecord(2, "c", "user", "hi", timestamp = 1)
        assertNull(userEcho.toMessage())
    }

    @Test
    fun `system record maps to system message`() {
        val message = OutboundRecord(
            seq = 7,
            conversationId = "c_err",
            role = "system",
            text = "网关正在关闭/重启，本次未执行。请稍后重试。",
            timestamp = 2_000,
        ).toMessage()

        requireNotNull(message)
        assertEquals(OpenClawMessage.Role.SYSTEM, message.role)
    }

    @Test
    fun `base url normalization`() {
        assertEquals(
            "https://openclaw.icexxl.cn:55443",
            OpenClawConfig(baseUrl = " https://openclaw.icexxl.cn:55443/ ").normalizedBaseUrl(),
        )
        assertNull(OpenClawConfig(baseUrl = "openclaw.icexxl.cn").normalizedBaseUrl())
        assertNull(OpenClawConfig(baseUrl = "").normalizedBaseUrl())
    }

    @Test
    fun `config is configured only with url and token`() {
        assertTrue(OpenClawConfig(baseUrl = "https://x", token = "t").isConfigured)
        assertTrue(!OpenClawConfig(baseUrl = "https://x", token = "").isConfigured)
    }

    @Test
    fun `history response decodes the pagination cursor`() {
        val payload = """
            {"ok":true,"sessionKey":"agent:main:eta:direct:c_a","messages":[
              {"role":"user","text":"older","at":1000}
            ],"hasMore":true,"nextBefore":41}
        """.trimIndent()

        val response = json.decodeFromString(HistoryResponse.serializer(), payload)

        assertTrue(response.hasMore)
        assertEquals(41L, response.nextBefore)
        assertEquals("older", response.messages.single().text)
    }

    @Test
    fun `history response defaults pagination fields for old plugin builds`() {
        val response = json.decodeFromString(
            HistoryResponse.serializer(),
            """{"ok":true,"sessionKey":"agent:main:eta:direct:c_a","messages":[]}""",
        )

        assertTrue(!response.hasMore)
        assertNull(response.nextBefore)
    }

    @Test
    fun `session decodes pinned and archived flags`() {
        val response = json.decodeFromString(
            SessionsResponse.serializer(),
            """
            {"ok":true,"agentId":"main","sessions":[
              {"sessionKey":"agent:main:eta:direct:c_a","label":"置顶","pinned":true,"archived":false},
              {"sessionKey":"agent:main:eta:direct:c_b","label":"旧会话","archived":true}
            ]}
            """.trimIndent(),
        )

        assertTrue(response.sessions[0].pinned)
        assertTrue(!response.sessions[0].archived)
        assertTrue(!response.sessions[1].pinned)
        assertTrue(response.sessions[1].archived)
    }

    @Test
    fun `session and agent decode optional model and context usage metadata`() {
        val agents = json.decodeFromString(
            AgentsResponse.serializer(),
            """{"ok":true,"agents":[{"id":"main","name":"Jarvis","model":"claude-sonnet-4","contextTokens":200000}]}""",
        )
        assertEquals("claude-sonnet-4", agents.agents.single().model)
        assertEquals(200000, agents.agents.single().contextTokens)

        val sessions = json.decodeFromString(
            SessionsResponse.serializer(),
            """
            {"ok":true,"agentId":"main","sessions":[
              {"sessionKey":"agent:main:main","model":"gpt-5.4","totalTokens":18400,"contextTokens":272000}
            ]}
            """.trimIndent(),
        )
        val session = sessions.sessions.single()
        assertEquals("gpt-5.4", session.model)
        assertEquals(18400, session.totalTokens)
        assertEquals(272000, session.contextTokens)
    }

    @Test
    fun `models catalog and set session model decode cleanly`() {
        val models = json.decodeFromString(
            ModelsResponse.serializer(),
            """
            {"ok":true,"agentId":"main","defaultModel":"claude-sonnet-4-5","defaultModelProvider":"anthropic","models":[
              {"model":"claude-sonnet-4-5","modelProvider":"anthropic","name":"Claude Sonnet 4.5","alias":"sonnet","contextTokens":200000,"isDefault":true},
              {"model":"gpt-5.4","modelProvider":"openai","contextTokens":400000,"isDefault":false}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, models.models.size)
        assertTrue(models.models[0].isDefault)
        assertEquals("sonnet", models.models[0].alias)

        val updated = json.decodeFromString(
            SetSessionModelResponse.serializer(),
            """{"ok":true,"sessionKey":"agent:main:main","model":"gpt-5.4","modelProvider":"openai","contextTokens":400000,"isDefault":false}""",
        )
        assertTrue(updated.ok)
        assertEquals("gpt-5.4", updated.model)
        assertEquals(400000, updated.contextTokens)
    }

    @Test
    fun `stream commands thinking and history reasoning decode cleanly`() {
        val stream = json.decodeFromString(
            LiveStreamResponse.serializer(),
            """
            {"ok":true,"sessionKey":"agent:main:main","rev":5,"active":true,"phase":"running","compacting":false,
             "runId":"run-1","text":"Partial","reasoning":"Step 1","tools":[{"name":"read_file","summary":"/etc/hosts","at":1000}]}
            """.trimIndent(),
        )
        assertTrue(stream.active)
        assertEquals("Step 1", stream.reasoning)
        assertEquals("read_file", stream.tools.single().name)

        val commands = json.decodeFromString(
            CommandsResponse.serializer(),
            """{"ok":true,"agentId":"main","commands":[{"command":"/compact","name":"compact","description":"压缩","acceptsArgs":true,"category":"command"}]}""",
        )
        assertEquals("/compact", commands.commands.single().command)

        val hist = json.decodeFromString(
            HistoryResponse.serializer(),
            """{"ok":true,"sessionKey":"agent:main:main","thinkingLevel":"high","compactionCount":2,"messages":[{"role":"assistant","text":"Hi","at":1000,"reasoning":"Thought","tools":[{"name":"exec","summary":"ls"}]}]}""",
        )
        assertEquals("high", hist.thinkingLevel)
        assertEquals(2, hist.compactionCount)
        val msg = hist.messages.single().toMessage()!!
        assertEquals("Thought", msg.reasoning)
        assertEquals("exec", msg.tools.single().name)
    }
}
