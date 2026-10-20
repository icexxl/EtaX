package io.github.mangi.eta.channel.openclaw

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSocket 帧解析测试。
 * 帧内容直接取自插件 ws.ts 在真实网关上的输出（已用 Node 客户端抓取核对）。
 */
class OpenClawWebSocketFrameTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `snapshot frame carries backlog`() {
        val frame = """
            {"type":"snapshot","records":[{"conversationId":"c_ws","role":"assistant","text":"ws hello","seq":1,"timestamp":1792502759723}]}
        """.trimIndent()

        val event = parseWebSocketFrame(json, frame)
        requireNotNull(event)
        assertTrue(event is OpenClawWebSocket.Event.Records)
        val records = (event as OpenClawWebSocket.Event.Records)
        assertTrue(records.snapshot)
        assertEquals(1, records.records.size)
        assertEquals(1L, records.records.first().seq)
        assertEquals("ws hello", records.records.first().text)
    }

    @Test
    fun `message frame is incremental`() {
        val frame =
            """{"type":"message","record":{"conversationId":"c_ws","role":"assistant","text":"hi","seq":2,"timestamp":1792502759724}}"""

        val event = parseWebSocketFrame(json, frame) as OpenClawWebSocket.Event.Records
        assertEquals(false, event.snapshot)
        assertEquals(2L, event.records.single().seq)
    }

    @Test
    fun `status frames report run state`() {
        val running = parseWebSocketFrame(
            json,
            """{"type":"status","status":{"conversationId":"c_x","state":"running","at":1792504288948}}""",
        ) as OpenClawWebSocket.Event.Status
        assertEquals("c_x", running.conversationId)
        assertEquals("running", running.state)

        val failed = parseWebSocketFrame(
            json,
            """{"type":"status","status":{"conversationId":"c_x","state":"error","detail":"网关正在关闭/重启，本次未执行。请稍后重试。","at":1792504289048}}""",
        ) as OpenClawWebSocket.Event.Status
        assertEquals("error", failed.state)
        assertEquals("网关正在关闭/重启，本次未执行。请稍后重试。", failed.detail)
    }

    @Test
    fun `pong and unknown frames are ignored`() {
        assertNull(parseWebSocketFrame(json, """{"type":"pong"}"""))
        assertNull(parseWebSocketFrame(json, """{"type":"something-new"}"""))
        assertNull(parseWebSocketFrame(json, "not json"))
    }

    @Test
    fun `empty snapshot is valid`() {
        val event = parseWebSocketFrame(json, """{"type":"snapshot","records":[]}""")
        val records = (event as OpenClawWebSocket.Event.Records)
        assertTrue(records.records.isEmpty())
    }
}
