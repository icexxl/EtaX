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
        data class UploadPayload(val name: String, val text: String, val bytes: ByteArray)
        val uploadPayloads = mutableListOf<UploadPayload>()
        val uploadCalls = mutableListOf<CompletableDeferred<Result<Unit>>>()
        val historyKeys = mutableListOf<String>()
        var historyMessages: List<HistoryDto> = emptyList()
        var modelsResponse: Result<ModelsResponse> = Result.success(ModelsResponse(ok = true))
        data class SwitchModelCall(
            val sessionKey: String,
            val model: String,
            val modelProvider: String?,
            val isDefault: Boolean,
            val result: CompletableDeferred<Result<SetSessionModelResponse>>,
        )
        val switchModelCalls = mutableListOf<SwitchModelCall>()
        var commandsResponse: Result<CommandsResponse> = Result.success(CommandsResponse(ok = true))
        var thinkingCalls = mutableListOf<Pair<String, String>>()
        var abortCalls = mutableListOf<String>()
        var compactCalls = mutableListOf<String>()
        var resetCalls = mutableListOf<String>()
        var pullCount = 0
        var enableSse = false
        val registeredPushTokens = mutableListOf<Pair<String, String>>()
        data class SseCall(
            val since: Long,
            val sessionKey: String?,
            val onEvent: (OpenClawSseEvent) -> Unit,
            val onClosed: (Int?, Throwable?) -> Unit,
            var cancelled: Boolean = false,
        )
        val sseCalls = mutableListOf<SseCall>()
        override suspend fun health(config: OpenClawConfig) = Result.success(HealthResponse())
        override suspend fun agents(config: OpenClawConfig): Result<AgentsResponse> =
            CompletableDeferred<Result<AgentsResponse>>().also(agentCalls::add).await()
        override suspend fun sessions(config: OpenClawConfig, agentId: String, includeArchived: Boolean): Result<SessionsResponse> =
            CompletableDeferred<Result<SessionsResponse>>().also { sessionCalls.add(SessionCall(agentId, it)) }.await()
        override suspend fun history(config: OpenClawConfig, sessionKey: String, limit: Int, before: Long?): Result<HistoryResponse> {
            historyKeys.add(sessionKey)
            return Result.success(HistoryResponse(sessionKey = sessionKey, messages = historyMessages))
        }
        override suspend fun models(config: OpenClawConfig, agentId: String): Result<ModelsResponse> = modelsResponse
        override suspend fun setSessionModel(
            config: OpenClawConfig,
            sessionKey: String,
            model: String,
            modelProvider: String?,
            isDefault: Boolean,
        ): Result<SetSessionModelResponse> = CompletableDeferred<Result<SetSessionModelResponse>>()
            .also { switchModelCalls.add(SwitchModelCall(sessionKey, model, modelProvider, isDefault, it)) }
            .await()
        override suspend fun commands(config: OpenClawConfig, agentId: String): Result<CommandsResponse> = commandsResponse
        override suspend fun setSessionThinking(config: OpenClawConfig, sessionKey: String, thinkingLevel: String): Result<SetSessionThinkingResponse> {
            thinkingCalls.add(sessionKey to thinkingLevel)
            return Result.success(SetSessionThinkingResponse(ok = true, sessionKey = sessionKey, thinkingLevel = thinkingLevel.takeUnless { it == "default" }))
        }
        override suspend fun abortSession(config: OpenClawConfig, sessionKey: String): Result<AbortSessionResponse> {
            abortCalls.add(sessionKey)
            return Result.success(AbortSessionResponse(ok = true, sessionKey = sessionKey, aborted = true))
        }
        override suspend fun compactSession(config: OpenClawConfig, sessionKey: String, instructions: String?): Result<SessionCommandResponse> {
            compactCalls.add(sessionKey)
            return Result.success(SessionCommandResponse(ok = true, sessionKey = sessionKey, summary = "已压缩", totalTokens = 8_000, contextTokens = 200_000, compactionCount = 1))
        }
        override suspend fun resetSession(config: OpenClawConfig, sessionKey: String): Result<SessionCommandResponse> {
            resetCalls.add(sessionKey)
            return Result.success(SessionCommandResponse(ok = true, sessionKey = sessionKey, summary = "已重置"))
        }
        override suspend fun postInbound(config: OpenClawConfig, text: String, clientMsgId: String): Result<InboundResponse> =
            CompletableDeferred<Result<InboundResponse>>().also(sendCalls::add).await()
        override suspend fun pull(config: OpenClawConfig, since: Long, waitMs: Long): Result<PullResponse> {
            pullCount += 1
            return CompletableDeferred<Result<PullResponse>>().await()
        }
        override fun openEvents(
            config: OpenClawConfig,
            since: Long,
            sessionKey: String?,
            onEvent: (OpenClawSseEvent) -> Unit,
            onClosed: (httpCode: Int?, failure: Throwable?) -> Unit,
        ): OpenClawEventSubscription? {
            if (!enableSse) return null
            val call = SseCall(since, sessionKey, onEvent, onClosed)
            sseCalls.add(call)
            return object : OpenClawEventSubscription {
                override fun cancel() { call.cancelled = true }
            }
        }
        override suspend fun ack(config: OpenClawConfig, seq: Long) = Result.success(AckResponse())
        override suspend fun registerPushToken(
            config: OpenClawConfig,
            deviceId: String,
            token: String,
            platform: String,
        ): Result<Unit> {
            registeredPushTokens.add(deviceId to token)
            return Result.success(Unit)
        }
        override suspend fun fetchMedia(config: OpenClawConfig, src: String) = Result.success(byteArrayOf())
        override suspend fun uploadMedia(config: OpenClawConfig, name: String, mime: String, text: String, bytes: ByteArray): Result<Unit> =
            CompletableDeferred<Result<Unit>>().also {
                uploadPayloads.add(UploadPayload(name, text, bytes))
                uploadCalls.add(it)
            }.await()
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

    @Test fun `attachment and caption submit together and rejected submissions keep draft ownership`() {
        assertFalse(store.uploadFile("note.txt", "text/plain", byteArrayOf(1), "caption"))
        store.selectAgent("a")
        store.newSession()
        assertTrue(store.uploadFile("note.txt", "text/plain", byteArrayOf(1, 2), "分析这个附件"))
        assertEquals("分析这个附件", api.uploadPayloads.single().text)
        assertEquals("note.txt", api.uploadPayloads.single().name)
        assertEquals("分析这个附件", store.state.value.messages.single().text)
        assertTrue(api.sendCalls.isEmpty())
        assertFalse(store.uploadFile("other.txt", "text/plain", byteArrayOf(3)))
        assertEquals(1, api.uploadCalls.size)
        api.uploadCalls.single().complete(Result.failure(IllegalStateException("offline")))
        store.retryMessage(store.state.value.messages.single().id)
        assertEquals("分析这个附件", api.uploadPayloads.last().text)
        assertTrue(api.uploadPayloads.last().bytes.contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun `cold start restores history for matching agent and deduplicates concurrent start`() {
        val key = "agent:a:eta:direct:saved"
        settings.saveLastAgentId("a")
        settings.saveLastSessionKey(key)
        api.historyMessages = listOf(HistoryDto(role = "assistant", text = "welcome back", at = 1_000L))

        val restored = OpenClawHomeStore(api, settings)
        restored.attach(scope)
        restored.setPollingEnabled(false)
        restored.start()
        restored.start()
        assertEquals(1, api.agentCalls.size)

        api.agentCalls.single().complete(
            Result.success(AgentsResponse(agents = listOf(AgentDto("a")), defaultAgentId = "a")),
        )
        assertEquals(listOf(key), api.historyKeys)
        assertEquals(listOf("welcome back"), restored.state.value.messages.map { it.text })
    }

    @Test fun `host foreground transition refreshes after background and skips redundant reload while foreground`() {
        store.start()
        api.agentCalls.single().complete(
            Result.success(AgentsResponse(agents = listOf(AgentDto("a")), defaultAgentId = "a")),
        )
        assertEquals(1, api.agentCalls.size)

        // 仍在前景且可见 agent 未变化，不触发整套重载
        store.onHostForegroundChanged(true)
        assertEquals(1, api.agentCalls.size)

        // 退到后台再回到前台，触发 onAppResumed 刷新
        store.onHostForegroundChanged(false)
        store.setPollingEnabled(false)
        store.onHostForegroundChanged(true)
        assertEquals(2, api.agentCalls.size)
        store.stop()
    }

    @Test fun `sessions refresh auto-selects first session when none is active`() {
        val target = "agent:a:main"
        api.historyMessages = listOf(HistoryDto(role = "assistant", text = "auto opened", at = 2_000L))
        store.selectAgent("a")
        api.sessionCalls.single().result.complete(
            Result.success(SessionsResponse(sessions = listOf(session("a", target)))),
        )
        assertEquals(target, store.state.value.currentSessionKey)
        assertEquals(listOf(target), api.historyKeys)
        assertEquals(listOf("auto opened"), store.state.value.messages.map { it.text })
    }

    @Test fun `selectModel loads catalog and updates active session model and context window`() {
        val target = "agent:a:main"
        api.modelsResponse = Result.success(
            ModelsResponse(
                ok = true,
                agentId = "a",
                defaultModel = "claude-sonnet-4-5",
                defaultModelProvider = "anthropic",
                models = listOf(
                    ModelOptionDto("claude-sonnet-4-5", "anthropic", isDefault = true, contextTokens = 200_000),
                    ModelOptionDto("gpt-5.4", "openai", isDefault = false, contextTokens = 400_000),
                ),
            ),
        )
        store.selectAgent("a")
        api.sessionCalls.single().result.complete(
            Result.success(
                SessionsResponse(
                    sessions = listOf(
                        SessionDto(
                            sessionKey = target,
                            agentId = "a",
                            model = "claude-sonnet-4-5",
                            modelProvider = "anthropic",
                            totalTokens = 12_000,
                            contextTokens = 200_000,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, store.state.value.models.size)
        assertEquals("claude-sonnet-4-5", store.state.value.activeModelLabel)

        store.selectModel("gpt-5.4", "openai", isDefault = false)
        assertTrue(store.state.value.isSwitchingModel)
        val switchCall = api.switchModelCalls.single()
        assertEquals(target, switchCall.sessionKey)
        assertEquals("gpt-5.4", switchCall.model)
        assertEquals("openai", switchCall.modelProvider)

        switchCall.result.complete(
            Result.success(
                SetSessionModelResponse(
                    ok = true,
                    sessionKey = target,
                    model = "gpt-5.4",
                    modelProvider = "openai",
                    contextTokens = 400_000,
                    isDefault = false,
                ),
            ),
        )
        assertFalse(store.state.value.isSwitchingModel)
        assertEquals("gpt-5.4", store.state.value.activeModelLabel)
        assertEquals("openai", store.state.value.activeModelProvider)
        assertEquals(400_000, store.state.value.activeContextUsage?.contextWindow)
    }

    @Test fun `thinking abort compact reset and commands update store state cleanly`() {
        val target = "agent:a:main"
        api.commandsResponse = Result.success(
            CommandsResponse(
                ok = true,
                agentId = "a",
                commands = listOf(CommandOptionDto("/compact", "compact", "压缩上下文")),
            ),
        )
        store.selectAgent("a")
        api.sessionCalls.single().result.complete(
            Result.success(SessionsResponse(sessions = listOf(SessionDto(sessionKey = target, agentId = "a", totalTokens = 50_000, contextTokens = 200_000)))),
        )
        assertEquals(listOf("/compact"), store.state.value.commands.map { it.command })

        store.selectThinkingLevel("high")
        assertEquals("high", store.state.value.activeThinkingLevel)
        assertEquals(listOf(target to "high"), api.thinkingCalls)

        store.compactSession()
        assertEquals(8_000, store.state.value.activeContextUsage?.contextTokens)
        assertEquals(1, store.state.value.activeContextUsage?.compactionCount)
        assertEquals(listOf(target), api.compactCalls)

        store.abortRun()
        assertFalse(store.state.value.isRunActive)
        assertEquals(listOf(target), api.abortCalls)

        store.resetSession()
        assertTrue(store.state.value.messages.isEmpty())
        assertEquals(listOf(target), api.resetCalls)
    }

    @Test fun `sse stream delivers live updates records and reconnects on network change`() {
        api.enableSse = true
        var evicted = 0
        val sseStore = OpenClawHomeStore(api, settings, onEvictConnections = { evicted++ })
        sseStore.attach(scope)
        val target = "agent:a:main"
        sseStore.start()
        api.agentCalls.single().complete(
            Result.success(AgentsResponse(agents = listOf(AgentDto("a")), defaultAgentId = "a")),
        )
        api.sessionCalls.single().result.complete(
            Result.success(SessionsResponse(sessions = listOf(session("a", target)))),
        )

        val firstSub = api.sseCalls.last()
        assertEquals(target, firstSub.sessionKey)
        assertEquals(0, api.pullCount)

        firstSub.onEvent(OpenClawSseEvent.Connected)
        firstSub.onEvent(
            OpenClawSseEvent.Stream(
                LiveStreamResponse(
                    ok = true,
                    sessionKey = target,
                    active = true,
                    rev = 2L,
                    text = "流式输出中",
                    reasoning = "先分析问题",
                    tools = listOf(ToolStepDto("read", "config.json", 1_000L)),
                ),
            ),
        )
        assertTrue(sseStore.state.value.liveActive)
        assertEquals("流式输出中", sseStore.state.value.liveText)
        assertEquals("先分析问题", sseStore.state.value.liveReasoning)
        assertEquals(1, sseStore.state.value.liveTools.size)

        firstSub.onEvent(
            OpenClawSseEvent.Record(
                OutboundRecord(
                    seq = 7L,
                    conversationId = target,
                    role = "assistant",
                    text = "最终回复",
                    timestamp = 2_000L,
                ),
            ),
        )
        assertFalse(sseStore.state.value.liveActive)
        assertEquals(listOf("最终回复"), sseStore.state.value.messages.map { it.text })
        assertEquals("先分析问题", sseStore.state.value.messages.single().reasoning)
        assertEquals(7L, settings.lastSeq())

        val sessionsBefore = api.sessionCalls.size
        firstSub.onEvent(OpenClawSseEvent.SessionsChanged("record", target))
        assertTrue(api.sessionCalls.size > sessionsBefore)

        // 切网立即清池、断开旧 SSE 并重连
        val sseCountBefore = api.sseCalls.size
        sseStore.onNetworkChanged()
        assertTrue(evicted >= 1)
        assertTrue(firstSub.cancelled)
        assertEquals(sseCountBefore + 1, api.sseCalls.size)
        assertEquals(7L, api.sseCalls.last().since)
        sseStore.stop()
    }

    @Test fun `sse 404 falls back cleanly to legacy long polling`() {
        api.enableSse = true
        val fallbackStore = OpenClawHomeStore(api, settings)
        fallbackStore.attach(scope)
        fallbackStore.start()
        api.agentCalls.single().complete(
            Result.success(AgentsResponse(agents = listOf(AgentDto("a")), defaultAgentId = "a")),
        )
        assertEquals(1, api.sseCalls.size)
        assertEquals(0, api.pullCount)

        api.sseCalls.single().onClosed(404, null)
        assertEquals(1, api.pullCount)
        fallbackStore.stop()
    }

    @Test fun `background sleep stops polling and syncPushToken deduplicates registration`() {
        api.enableSse = true
        settings.saveBackgroundSleepEnabled(true)
        val fcmStore = OpenClawHomeStore(api, settings)
        fcmStore.attach(scope)
        fcmStore.start()
        api.agentCalls.single().complete(
            Result.success(AgentsResponse(agents = listOf(AgentDto("a")), defaultAgentId = "a")),
        )
        assertEquals(1, api.sseCalls.size)

        // 切后台休眠省电：断开 SSE 且不启动后台轮询
        fcmStore.setAppForeground(false)
        assertTrue(api.sseCalls.single().cancelled)
        assertEquals(0, api.pullCount)

        // 相同 token 多次同步仅上报一次
        fcmStore.syncPushToken("fcm-tok-1")
        fcmStore.syncPushToken("fcm-tok-1")
        assertEquals(1, api.registeredPushTokens.size)
        assertEquals("fcm-tok-1", api.registeredPushTokens.single().second)

        // token 变更后重新上报
        fcmStore.syncPushToken("fcm-tok-2")
        assertEquals(2, api.registeredPushTokens.size)
        assertEquals("fcm-tok-2", api.registeredPushTokens.last().second)
        fcmStore.stop()
    }

    private fun session(agentId: String, key: String = "agent:$agentId:eta:direct:one") =
        SessionDto(sessionKey = key, agentId = agentId)
}
