package io.github.mangi.eta.channel.openclaw

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 多会话分桶、去重与状态切换的行为测试（不上网络）。 */
@RunWith(RobolectricTestRunner::class)
class OpenClawChatStoreTest {

    private lateinit var settings: OpenClawSettings
    private lateinit var history: OpenClawHistory

    private class FakeApi : OpenClawApi {
        override suspend fun health(config: OpenClawConfig) = Result.failure<HealthResponse>(UnsupportedOperationException())
        override suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String) =
            Result.success(InboundResponse(ok = true, accepted = true))
        override suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long) =
            Result.success(PullResponse(ok = true))
        override suspend fun ack(config: OpenClawConfig, seq: Long) =
            Result.success(AckResponse(ok = true, ackedSeq = seq))
    }

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        settings = OpenClawSettings(context)
        history = OpenClawHistory(context)
        settings.save(OpenClawConfig(baseUrl = "http://127.0.0.1:1", token = "t", conversationId = "c_default"))
    }

    private fun record(seq: Long, conversationId: String, text: String, role: String = "assistant") =
        OpenClawMessage(
            id = "srv-$seq",
            conversationId = conversationId,
            role = when (role) {
                "system" -> OpenClawMessage.Role.SYSTEM
                else -> OpenClawMessage.Role.ASSISTANT
            },
            text = text,
            timestamp = seq * 1000,
        )

    @Test
    fun `records are bucketed per conversation`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        store.appendMessages(listOf(record(1, "c_a", "for a"), record(2, "c_b", "for b")))

        assertEquals("c_default", store.state.value.activeConversationId)
        assertTrue(store.state.value.messages.isEmpty())

        store.switchConversation("c_a")
        assertEquals(listOf("for a"), store.state.value.messages.map { it.text })

        store.switchConversation("c_b")
        assertEquals(listOf("for b"), store.state.value.messages.map { it.text })
    }

    @Test
    fun `duplicate records are ignored`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        val first = record(1, "c_dup", "once")
        store.appendMessages(listOf(first))
        store.appendMessages(listOf(first))
        store.switchConversation("c_dup")
        assertEquals(1, store.state.value.messages.size)
    }

    @Test
    fun `conversation list is ordered by recency`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        store.appendMessages(listOf(record(1, "c_old", "old"), record(9, "c_new", "new")))
        val ids = store.state.value.conversations.map { it.id }
        assertTrue(ids.indexOf("c_new") < ids.indexOf("c_old"))
    }

    @Test
    fun `status events toggle running flag for active conversation`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        val config = store.state.value.config

        store.handleWebSocketEvent(
            OpenClawWebSocket.Event.Status("c_default", "running", null),
            config,
        )
        assertTrue(store.state.value.running)

        store.handleWebSocketEvent(
            OpenClawWebSocket.Event.Status("c_default", "done", null),
            config,
        )
        assertFalse(store.state.value.running)
    }

    @Test
    fun `unread counts accumulate for inactive conversations and clear on switch`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        store.appendMessages(
            listOf(
                OpenClawMessage("srv-1", "c_other", OpenClawMessage.Role.ASSISTANT, "a", 1_000),
                OpenClawMessage("srv-2", "c_other", OpenClawMessage.Role.ASSISTANT, "b", 2_000),
            ),
        )
        assertEquals(
            2,
            store.state.value.conversations.first { it.id == "c_other" }.unread,
        )

        store.switchConversation("c_other")
        assertEquals(
            0,
            store.state.value.conversations.first { it.id == "c_other" }.unread,
        )
    }

    @Test
    fun `cursor survives reload`() {
        history.save(mapOf("c_cursor" to emptyList()), lastSeq = 42)
        val reloaded = OpenClawHistory(RuntimeEnvironment.getApplication())
        val loaded = reloaded.load()
        assertEquals(42L, loaded.lastSeq)

        // Store 启动时应从持久化游标继续，而不是从 0 重放
        val store = OpenClawChatStore(FakeApi(), settings, reloaded)
        assertEquals(42L, store.state.value.lastSeq)
    }

    @Test
    fun `assistant record clears running flag`() {
        val store = OpenClawChatStore(FakeApi(), settings, history)
        val config = store.state.value.config
        store.handleWebSocketEvent(OpenClawWebSocket.Event.Status("c_default", "running", null), config)

        store.handleWebSocketEvent(
            OpenClawWebSocket.Event.Records(listOf(OutboundRecord(5, "c_default", "assistant", "hi")), snapshot = false),
            config,
        )
        assertFalse(store.state.value.running)
    }
}
