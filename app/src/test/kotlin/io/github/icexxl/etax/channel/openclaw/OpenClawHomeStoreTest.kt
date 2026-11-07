package io.github.icexxl.etax.channel.openclaw

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawHomeStoreTest {
    private lateinit var settings: OpenClawSettings
    private lateinit var api: FakeApi
    private lateinit var store: OpenClawHomeStore
    private lateinit var scope: CoroutineScope

    private class TokenStore : OpenClawTokenStore {
        var value: String? = null
        override fun token(): String? = value
        override fun setToken(token: String) { value = token }
        override fun clear() { value = null }
    }

    private class FakeApi : OpenClawApi {
        data class SessionCall(val agentId: String, val result: CompletableDeferred<Result<SessionsResponse>>)
        val sessionCalls = mutableListOf<SessionCall>()
        val agentCalls = mutableListOf<CompletableDeferred<Result<AgentsResponse>>>()
        val sendCalls = mutableListOf<CompletableDeferred<Result<InboundResponse>>>()
        val uploadCalls = mutableListOf<CompletableDeferred<Result<Unit>>>()
        override suspend fun health(config: OpenClawConfig) = Result.success(HealthResponse())
        override suspend fun agents(config: OpenClawConfig): Result<AgentsResponse> =
            CompletableDeferred<Result<AgentsResponse>>().also(agentCalls::add).await()
        override suspend fun sessions(config: OpenClawConfig, agentId: String, includeArchived: Boolean): Result<SessionsResponse> =
            CompletableDeferred<Result<SessionsResponse>>().also { sessionCalls.add(SessionCall(agentId, it)) }.await()
        override suspend fun history(config: OpenClawConfig, sessionKey: String, limit: Int, before: Long?): Result<HistoryResponse> =
            Result.success(HistoryResponse(sessionKey = sessionKey))
        override suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String): Result<InboundResponse> =
            CompletableDeferred<Result<InboundResponse>>().also(sendCalls::add).await()
        override suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long): Result<PullResponse> =
            CompletableDeferred<Result<PullResponse>>().await()
        override suspend fun ack(config: OpenClawConfig, seq: Long) = Result.success(AckResponse())
        override suspend fun fetchMedia(config: OpenClawConfig, src: String) = Result.success(byteArrayOf())
        override suspend fun uploadMedia(config: OpenClawConfig, name: String, mime: String, text: String, bytes: ByteArray): Result<Unit> =
            CompletableDeferred<Result<Unit>>().also(uploadCalls::add).await()
    }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("openclaw_channel", Context.MODE_PRIVATE).edit().clear().commit()
        settings = OpenClawSettings(context, TokenStore())
        settings.save(OpenClawConfig("https://example.test", "secret"))
        api = FakeApi()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        store = OpenClawHomeStore(api, settings)
        store.attach(scope)
        store.setPollingEnabled(false)
    }

    @Test fun `old session response cannot replace newly selected agent`() {
        store.selectAgent("a")
        store.selectAgent("b")
        api.sessionCalls[1].result.complete(Result.success(SessionsResponse(sessions = listOf(session("b")))))
        api.sessionCalls[0].result.complete(Result.success(SessionsResponse(sessions = listOf(session("a")))))
        assertEquals("b", store.state.value.currentAgentId)
        assertEquals(listOf(session("b")), store.state.value.sessions)
    }

    @Test fun `newer refresh wins for same agent`() {
        store.selectAgent("a")
        store.setShowArchived(true)
        api.sessionCalls[1].result.complete(Result.success(SessionsResponse(sessions = listOf(session("a")))))
        api.sessionCalls[0].result.complete(Result.failure(IllegalStateException("old failure")))
        assertEquals(listOf(session("a")), store.state.value.sessions)
        assertNull(store.state.value.error)
    }

    @Test fun `restored session clears when server falls back to a different agent`() {
        settings.saveLastAgentId("missing")
        settings.saveLastSessionKey("agent:missing:eta:direct:old")
        val restored = OpenClawHomeStore(api, settings)
        restored.attach(scope)
        restored.setPollingEnabled(false)
        restored.start()
        api.agentCalls.last().complete(Result.success(AgentsResponse(
            agents = listOf(AgentDto("b")), defaultAgentId = "b")))
        assertEquals("b", restored.state.value.currentAgentId)
        assertNull(restored.state.value.currentSessionKey)
        assertEquals("", settings.lastSessionKey())
    }

    @Test fun `cold start notification selects its agent and session`() {
        val key = "agent:b:eta:direct:target"
        store.openSession(key)
        assertEquals("b", store.state.value.currentAgentId)
        api.agentCalls.single().complete(Result.success(AgentsResponse(
            agents = listOf(AgentDto("a"), AgentDto("b")), defaultAgentId = "a")))
        assertEquals("b", api.sessionCalls.last().agentId)
        api.sessionCalls.last().result.complete(Result.success(SessionsResponse(sessions = listOf(session("b", key)))))
        assertEquals(key, store.state.value.currentSessionKey)
    }

    @Test fun `switching agent clears persisted session and mismatched legacy session is ignored`() {
        settings.saveLastAgentId("a")
        settings.saveLastSessionKey("agent:a:eta:direct:old")
        store.selectAgent("b")
        assertEquals("", settings.lastSessionKey())
        val restored = OpenClawHomeStore(api, settings)
        restored.attach(scope)
        assertEquals("b", restored.state.value.currentAgentId)
        assertNull(restored.state.value.currentSessionKey)
        settings.saveLastSessionKey("agent:a:eta:direct:old")
        val legacy = OpenClawHomeStore(api, settings)
        legacy.attach(scope)
        assertNull(legacy.state.value.currentSessionKey)
    }

    @Test fun `server change invalidates pending response and clears state`() {
        store.selectAgent("a")
        settings.save(OpenClawConfig("https://other.test", "secret"))
        store.reloadAgents()
        api.sessionCalls.single().result.complete(Result.success(SessionsResponse(sessions = listOf(session("a")))))
        assertTrue(store.state.value.sessions.isEmpty())
        assertEquals("", store.state.value.currentAgentId)
        assertEquals(0L, settings.lastSeq())
    }

    @Test fun `failed text and upload can retry without adding another bubble`() {
        store.selectAgent("a")
        store.newSession()
        store.send("hello")
        val firstId = store.state.value.messages.single().id
        api.sendCalls[0].complete(Result.failure(IllegalStateException("offline")))
        assertTrue(store.state.value.messages.single().failed)
        store.retryMessage(firstId)
        assertEquals(firstId, store.state.value.messages.single().id)
        api.sendCalls[1].complete(Result.success(InboundResponse(accepted = true)))
        assertFalse(store.state.value.messages.single().failed)
        assertFalse(store.state.value.messages.single().pending)

        store.uploadFile("note.txt", "text/plain", byteArrayOf(1, 2))
        api.uploadCalls[0].complete(Result.failure(IllegalStateException("offline")))
        val uploaded = store.state.value.messages.last()
        assertTrue(uploaded.failed)
        store.retryMessage(uploaded.id)
        assertEquals(2, api.uploadCalls.size)
        api.uploadCalls[1].complete(Result.success(Unit))
        assertFalse(store.state.value.messages.last().failed)
    }

    private fun session(agentId: String, key: String = "agent:$agentId:eta:direct:one") =
        SessionDto(sessionKey = key, agentId = agentId)
}
