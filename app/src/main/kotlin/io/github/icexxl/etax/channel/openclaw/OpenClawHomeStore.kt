package io.github.icexxl.etax.channel.openclaw

import io.github.icexxl.etax.agent.model.AgentHttpClient

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主界面 OpenClaw 模式的状态机。
 *
 * 与旧的 OpenClawChatStore 的区别：
 * - 会话列表来自服务端（/sessions），3 秒刷新，删除会同步消失
 * - agent 可切换，每个 agent 独立会话列表
 * - 打开会话时拉 /history，之后用全局游标增量补齐
 */
internal class OpenClawHomeStore(
    private val client: OpenClawApi,
    private val settings: OpenClawSettings,
    private val notifier: OpenClawNotifier? = null,
) {
    data class UiState(
        val agents: List<AgentDto> = emptyList(),
        val currentAgentId: String = "",
        val sessions: List<SessionDto> = emptyList(),
        val currentSessionKey: String? = null,
        val messages: List<OpenClawMessage> = emptyList(),
        val isLoadingAgents: Boolean = false,
        val isLoadingSessions: Boolean = false,
        val isLoadingHistory: Boolean = false,
        /** 当前会话是否还有更早的消息可加载。 */
        val hasMoreHistory: Boolean = false,
        /** 正在加载更早的一页（顶部「加载更早」）。 */
        val isLoadingMoreHistory: Boolean = false,
        val sending: Boolean = false,
        val running: Boolean = false,
        val error: String? = null,
        /** 当前会话在最近一次刷新中被服务端删除了。 */
        val currentSessionDeleted: Boolean = false,
        /** 有未读活动的会话 key。 */
        val unreadSessions: Set<String> = emptySet(),
        /** 最近一次轮询成功的时间（0=从未）。 */
        val lastPollAt: Long = 0L,
        /** 最近一次真正收到新消息的时间（0=从未）。 */
        val lastRecordAt: Long = 0L,
        /** 侧栏是否显示归档会话（持久化在设置里）。 */
        val showArchived: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(showArchived = settings.showArchivedSessions()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 恢复上次的拉取游标，避免重启后重复处理或丢失积压。 */
    private var cursorLoaded = false
    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var sessionPollJob: Job? = null
    private var messagePollJob: Job? = null
    private var lastSeq = 0L
    private var runningSince = 0L
    /** 当前会话历史分页游标（插件 transcript seq）。 */
    private var historyCursor: Long? = null
    /** sessionKey -> 上次已读时间（毫秒）；从 SharedPreferences 恢复，重启不丢未读。 */
    private val lastSeenAt = settings.loadLastSeenAt().toMutableMap()
    private var pollingEnabled = true
    private var pollFailures = 0
    private var sessionsJob: Job? = null
    private var appForeground = true
    private var pendingOpenSession: String? = null
    private var connection = settings.load()
    private var generation = 0L
    private var sessionsRequest = 0L
    /** 本地新建、服务端尚未出现的会话 key。 */
    private val locallyCreated = mutableSetOf<String>()
    private data class UploadRetry(val mime: String, val bytes: ByteArray)
    private val failedUploads = mutableMapOf<String, UploadRetry>()

    /** 上次加载 agent 列表时的可见性签名，用于检测设置页的勾选变化。 */
    private var visibleSignature: String? = null
    /** 首轮只对齐游标，避免把历史消息全部弹成通知。 */
    private var primed = false

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        if (!cursorLoaded) {
            cursorLoaded = true
            lastSeq = settings.lastSeq()
            val restoredSession = settings.lastSessionKey()
            // agent 可能还没被显式选过，从会话 key（agent:<id>:...）里补出来
            val agentFromSession = Regex("^agent:([^:]+):").find(restoredSession)
                ?.groupValues?.getOrNull(1)
                ?: ""
            val restoredAgent = settings.lastAgentId().ifBlank { agentFromSession }
            val matchingSession = restoredSession.takeIf {
                agentFromSession.isNotBlank() && agentFromSession == restoredAgent
            }
            if (restoredAgent.isNotBlank() || matchingSession != null) {
                _state.value = _state.value.copy(
                    currentAgentId = restoredAgent,
                    currentSessionKey = matchingSession,
                )
            }
        }
    }

    /**
     * 拉取某个会话的历史并合并进当前列表。
     * 进程被系统回收后重建、或从后台恢复时也用它，避免「会话在但消息是空的」。
     */
    private fun loadHistoryInto(sessionKey: String) {
        val activeScope = scope ?: return
        historyCursor = null
        _state.value = _state.value.copy(
            isLoadingHistory = true,
            hasMoreHistory = false,
            isLoadingMoreHistory = false,
        )
        val requestGeneration = generation
        val config = config()
        activeScope.launch {
            client.history(config, sessionKey, HISTORY_LIMIT).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    val loaded = response.messages.mapNotNull { it.toMessage() }
                        .map { it.copy(conversationId = sessionKey) }
                    historyCursor = response.nextBefore
                    _state.value = _state.value.copy(
                        messages = mergeMessages(_state.value.messages, loaded),
                        isLoadingHistory = false,
                        hasMoreHistory = response.hasMore,
                        error = null,
                    )
                },
                onFailure = { failure ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    _state.value = _state.value.copy(
                        isLoadingHistory = false,
                        hasMoreHistory = false,
                        error = failure.message ?: "加载历史失败",
                    )
                },
            )
        }
    }

    /**
     * 回到前台（或进程被回收后重建）：丢弃可能已失效的连接、清掉错误状态，
     * 并强制刷新 agent / 会话 / 当前会话历史，避免长时间后台后画面空白。
     */
    fun onAppResumed() {
        syncConnection()
        appForeground = true
        pollFailures = 0
        // 休眠或切换网络后连接池里的旧 socket 往往已经失效，先清掉
        runCatching { AgentHttpClient.client.connectionPool.evictAll() }
        if (!config().isConfigured) return
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
        loadAgents()
        if (_state.value.currentAgentId.isNotBlank()) loadSessions()
        val sessionKey = _state.value.currentSessionKey
        if (sessionKey != null && _state.value.messages.isEmpty()) {
            _state.value = _state.value.copy(isLoadingHistory = true)
            loadHistoryInto(sessionKey)
        }
        ensureJobs()
    }

    fun config(): OpenClawConfig = settings.load()

    fun saveConfig(config: OpenClawConfig) {
        settings.save(config)
        syncConnection()
        _state.value = _state.value.copy(error = null)
    }

    /** 首次进入 OpenClaw 模式时调用。 */
    fun start() {
        syncConnection()
        val activeScope = scope ?: return
        val config = config()
        if (!config.isConfigured) {
            _state.value = _state.value.copy(error = "请先在设置里填写 Gateway 地址与 Token")
            return
        }
        if (_state.value.agents.isNotEmpty()) {
            ensureJobs()
            return
        }
        loadAgents()
    }

    /** 自检：立刻拉取一次（不等长轮询）。 */
    fun checkNow() {
        syncConnection()
        val activeScope = scope ?: return
        val config = config()
        if (!config.isConfigured) return
        val requestGeneration = generation
        activeScope.launch {
            client.pull(config, lastSeq, 0).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation) return@fold
                    pollFailures = 0
                    if (_state.value.error?.startsWith("OpenClaw 连接中断") == true) {
                        _state.value = _state.value.copy(error = null)
                    }
                    _state.value = _state.value.copy(lastPollAt = System.currentTimeMillis())
                    handleRecords(response.records, config)
                },
                onFailure = { },
            )
        }
    }

    /** 设置页保存后强制重新拉取 agent（同一 Activity 内导航不会触发 ON_START）。 */
    fun reloadAgents() {
        syncConnection()
        visibleSignature = null
        loadAgents()
    }

    /** 设置页改了勾选后，回到前台时刷新 agent 列表。 */
    fun refreshAgentsIfSelectionChanged() {
        val signature = settings.visibleAgentIds().sorted().joinToString(",")
        if (signature == visibleSignature) return
        loadAgents()
    }

    private fun loadAgents() {
        syncConnection()
        val activeScope = scope ?: return
        _state.value = _state.value.copy(isLoadingAgents = true, error = null)
        visibleSignature = settings.visibleAgentIds().sorted().joinToString(",")
        val config = config()
        val requestGeneration = generation
        activeScope.launch {
            client.agents(config).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation) return@fold
                    // 只保留设置页勾选的 agent；没勾选过则显示全部
                    val visible = settings.visibleAgentIds().toSet()
                    val agents = if (visible.isEmpty()) {
                        response.agents
                    } else {
                        response.agents.filter { it.id in visible }.ifEmpty { response.agents }
                    }
                    val ids = agents.map { it.id }
                    val preferred = when {
                        _state.value.currentAgentId in ids -> _state.value.currentAgentId
                        else -> response.defaultAgentId.takeIf { it in ids }
                            ?: ids.firstOrNull().orEmpty()
                    }
                    val agentChanged = _state.value.currentAgentId.isNotBlank() &&
                        _state.value.currentAgentId != preferred
                    if (agentChanged) {
                        settings.saveLastAgentId(preferred)
                        settings.saveLastSessionKey("")
                        historyCursor = null
                        failedUploads.clear()
                    }
                    _state.value = _state.value.copy(
                        agents = agents,
                        currentAgentId = preferred,
                        currentSessionKey = if (agentChanged) null else _state.value.currentSessionKey,
                        sessions = if (agentChanged) emptyList() else _state.value.sessions,
                        messages = if (agentChanged) emptyList() else _state.value.messages,
                        isLoadingHistory = if (agentChanged) false else _state.value.isLoadingHistory,
                        isLoadingAgents = false,
                    )
                    pendingOpenSession?.let { pending ->
                        val target = agentOf(pending)
                        if (target in ids && target != _state.value.currentAgentId) {
                            selectAgent(target)
                            ensureJobs()
                            return@fold
                        }
                    }
                    loadSessions()
                    ensureJobs()
                },
                onFailure = { failure ->
                    if (requestGeneration != generation) return@fold
                    _state.value = _state.value.copy(
                        isLoadingAgents = false,
                        // 已有列表时保持原样，避免后台恢复瞬间的抖动弹出一整条错误
                        error = if (_state.value.agents.isEmpty()) {
                            failure.message ?: "获取 agent 列表失败"
                        } else {
                            _state.value.error
                        },
                    )
                },
            )
        }
    }

    fun stop() {
        sessionPollJob?.cancel()
        messagePollJob?.cancel()
        sessionPollJob = null
        messagePollJob = null
    }

    /** 前后台切换：前台用快节奏，后台降频但仍保持轮询。 */
    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        setPollingEnabled(true)
    }

    /** 从通知点击进入指定会话。 */
    fun openSession(sessionKey: String) {
        syncConnection()
        val agentId = agentOf(sessionKey).takeIf { it.isNotBlank() } ?: return
        pendingOpenSession = sessionKey
        when {
            _state.value.agents.isEmpty() -> {
                selectAgent(agentId)
                start()
            }
            _state.value.currentAgentId != agentId -> selectAgent(agentId)
            else -> {
                selectSession(sessionKey)
                pendingOpenSession = null
            }
        }
    }

    /** 前后台切换时暂停/恢复轮询，避免后台耗电。 */
    fun setPollingEnabled(enabled: Boolean) {
        pollingEnabled = enabled
        if (enabled) {
            if (scope != null && _state.value.agents.isNotEmpty()) ensureJobs()
        } else {
            sessionPollJob?.cancel()
            messagePollJob?.cancel()
            sessionPollJob = null
            messagePollJob = null
        }
    }

    fun selectAgent(agentId: String) {
        if (agentId.isBlank() || agentId == _state.value.currentAgentId) return
        if (pendingOpenSession != null && agentOf(pendingOpenSession.orEmpty()) != agentId) {
            pendingOpenSession = null
        }
        generation++
        sessionsRequest++
        historyCursor = null
        failedUploads.clear()
        settings.saveLastAgentId(agentId)
        settings.saveLastSessionKey("")
        _state.value = _state.value.copy(
            currentAgentId = agentId,
            sessions = emptyList(),
            currentSessionKey = null,
            messages = emptyList(),
            isLoadingHistory = false,
            isLoadingSessions = false,
            currentSessionDeleted = false,
            sending = false,
            running = false,
            hasMoreHistory = false,
            isLoadingMoreHistory = false,
            error = null,
        )
        loadSessions()
    }

    /** 侧栏过滤开关：显示/隐藏 OpenClaw 侧已归档的会话。 */
    fun setShowArchived(show: Boolean) {
        if (show == _state.value.showArchived) return
        settings.saveShowArchivedSessions(show)
        sessionsRequest++
        _state.value = _state.value.copy(showArchived = show, sessions = emptyList())
        loadSessions()
    }

    fun selectSession(sessionKey: String) {
        pendingOpenSession = null
        if (sessionKey == _state.value.currentSessionKey) return
        markSeen(sessionKey)
        // P1-9：进入会话即清掉该会话的通知
        runCatching { notifier?.clear(sessionKey) }
        settings.saveLastSessionKey(sessionKey)
        failedUploads.clear()
        historyCursor = null
        _state.value = _state.value.copy(
            currentSessionKey = sessionKey,
            messages = emptyList(),
            isLoadingHistory = true,
            hasMoreHistory = false,
            isLoadingMoreHistory = false,
            currentSessionDeleted = false,
            unreadSessions = _state.value.unreadSessions - sessionKey,
            error = null,
        )
        loadHistoryInto(sessionKey)
    }

    /** 顶部「加载更早」：按游标取上一页 50 条，插入到当前消息列表前面。 */
    fun loadMoreHistory() {
        val sessionKey = _state.value.currentSessionKey ?: return
        val cursor = historyCursor ?: return
        if (_state.value.isLoadingMoreHistory || !_state.value.hasMoreHistory) return
        val activeScope = scope ?: return
        _state.value = _state.value.copy(isLoadingMoreHistory = true, error = null)
        val requestGeneration = generation
        val config = config()
        activeScope.launch {
            client.history(config, sessionKey, HISTORY_LIMIT, cursor).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    val loaded = response.messages.mapNotNull { it.toMessage() }
                        .map { it.copy(conversationId = sessionKey) }
                    historyCursor = response.nextBefore
                    _state.value = _state.value.copy(
                        messages = mergeMessages(_state.value.messages, loaded),
                        isLoadingMoreHistory = false,
                        hasMoreHistory = response.hasMore,
                    )
                },
                onFailure = { failure ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    _state.value = _state.value.copy(
                        isLoadingMoreHistory = false,
                        error = failure.message ?: "加载更早的消息失败",
                    )
                },
            )
        }
    }

    /** 在指定 agent 下新建会话并切换过去。 */
    fun newSession(agentId: String = _state.value.currentAgentId) {
        val id = "c_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val key = "agent:$agentId:eta:direct:$id"
        locallyCreated.add(key)
        val placeholder = SessionDto(
            sessionKey = key,
            label = "新会话",
            agentId = agentId,
            channel = "eta",
            updatedAt = System.currentTimeMillis(),
            hasActiveRun = false,
        )
        markSeen(key)
        settings.saveLastSessionKey(key)
        historyCursor = null
        _state.value = _state.value.copy(
            currentSessionKey = key,
            sessions = listOf(placeholder) + _state.value.sessions,
            messages = emptyList(),
            hasMoreHistory = false,
            isLoadingMoreHistory = false,
            currentSessionDeleted = false,
            error = null,
        )
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val sessionKey = _state.value.currentSessionKey ?: return
        val config = config()
        if (!config.isConfigured) {
            _state.value = _state.value.copy(error = "请先在设置里填写 Gateway 地址与 Token")
            return
        }
        if (scope == null || _state.value.sending) return
        val localId = "local-" + UUID.randomUUID()
        appendMessages(
            listOf(
                OpenClawMessage(
                    id = localId,
                    conversationId = sessionKey,
                    role = OpenClawMessage.Role.USER,
                    text = body,
                    timestamp = System.currentTimeMillis(),
                    pending = true,
                ),
            ),
        )
        sendOptimistic(sessionKey, localId, body, config)
    }

    private fun sendOptimistic(sessionKey: String, localId: String, body: String, config: OpenClawConfig) {
        val activeScope = scope ?: return
        val requestGeneration = generation
        runningSince = System.currentTimeMillis()
        _state.value = _state.value.copy(sending = true, running = true, error = null)
        activeScope.launch {
            client.postInbound(config.copy(conversationId = sessionKey), body, localId).fold(
                onSuccess = {
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    updateOptimistic(localId, failed = false)
                    _state.value = _state.value.copy(sending = false)
                },
                onFailure = { failure ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    updateOptimistic(localId, failed = true)
                    runningSince = 0L
                    _state.value = _state.value.copy(
                        sending = false,
                        running = false,
                        error = failure.message ?: "发送失败",
                    )
                },
            )
        }
    }

    /** 上传附件给当前会话对应的 OpenClaw 会话。 */
    fun uploadFile(name: String, mime: String, bytes: ByteArray, caption: String = "") {
        val sessionKey = _state.value.currentSessionKey ?: return
        val config = config()
        if (!config.isConfigured) {
            _state.value = _state.value.copy(error = "请先在设置里填写 Gateway 地址与 Token")
            return
        }
        if (scope == null || _state.value.sending) return
        val localId = "local-up-" + UUID.randomUUID()
        appendMessages(
            listOf(
                OpenClawMessage(
                    id = localId,
                    conversationId = sessionKey,
                    role = OpenClawMessage.Role.USER,
                    text = caption,
                    timestamp = System.currentTimeMillis(),
                    mediaName = name,
                    pending = true,
                ),
            ),
        )
        uploadOptimistic(sessionKey, localId, name, mime, bytes, caption, config)
    }

    private fun uploadOptimistic(
        sessionKey: String, localId: String, name: String, mime: String,
        bytes: ByteArray, caption: String, config: OpenClawConfig,
    ) {
        val activeScope = scope ?: return
        val requestGeneration = generation
        runningSince = System.currentTimeMillis()
        _state.value = _state.value.copy(sending = true, running = true, error = null)
        activeScope.launch {
            client.uploadMedia(config.copy(conversationId = sessionKey), name, mime, caption, bytes).fold(
                onSuccess = {
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    failedUploads.remove(localId)
                    updateOptimistic(localId, failed = false)
                    _state.value = _state.value.copy(sending = false)
                },
                onFailure = { failure ->
                    if (requestGeneration != generation || _state.value.currentSessionKey != sessionKey) return@fold
                    failedUploads[localId] = UploadRetry(mime, bytes)
                    updateOptimistic(localId, failed = true)
                    runningSince = 0L
                    _state.value = _state.value.copy(
                        sending = false,
                        running = false,
                        error = failure.message ?: "上传失败",
                    )
                },
            )
        }
    }

    fun retryMessage(localId: String) {
        if (_state.value.sending) return
        val message = _state.value.messages.firstOrNull {
            it.id == localId && it.failed && it.conversationId == _state.value.currentSessionKey
        } ?: return
        val config = config()
        if (!config.isConfigured) return
        updateOptimistic(localId, failed = false, pending = true)
        val upload = failedUploads[localId]
        if (upload != null) {
            uploadOptimistic(message.conversationId, localId, message.mediaName.orEmpty(),
                upload.mime, upload.bytes, message.text, config)
        } else {
            sendOptimistic(message.conversationId, localId, message.text, config)
        }
    }

    private fun updateOptimistic(localId: String, failed: Boolean, pending: Boolean = false) {
        _state.value = _state.value.copy(messages = _state.value.messages.map {
            if (it.id == localId) it.copy(failed = failed, pending = pending) else it
        })
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun agentOf(sessionKey: String): String =
        Regex("^agent:([^:]+):").find(sessionKey)?.groupValues?.getOrNull(1).orEmpty()

    private fun syncConnection() {
        val current = settings.load()
        if (connection.normalizedBaseUrl() == current.normalizedBaseUrl() &&
            connection.token == current.token
        ) return
        connection = current
        generation++
        sessionsRequest++
        messagePollJob?.cancel()
        messagePollJob = null
        lastSeq = settings.lastSeq()
        lastSeenAt.clear()
        lastSeenAt.putAll(settings.loadLastSeenAt())
        primed = false
        pollFailures = 0
        runningSince = 0L
        historyCursor = null
        locallyCreated.clear()
        failedUploads.clear()
        pendingOpenSession = null
        visibleSignature = null
        _state.value = UiState(showArchived = settings.showArchivedSessions())
    }

    private fun ensureJobs() {
        if (!pollingEnabled) return
        val activeScope = scope ?: return
        if (sessionPollJob?.isActive != true) {
            sessionPollJob = activeScope.launch {
                while (activeScope.isActive) {
                    delay(if (appForeground) SESSION_POLL_MS else SESSION_POLL_BG_MS)
                    loadSessions()
                }
            }
        }
        if (messagePollJob?.isActive != true) {
            messagePollJob = activeScope.launch { pollMessages() }
        }
    }

    private fun loadSessions() {
        syncConnection()
        val activeScope = scope ?: return
        val agentId = _state.value.currentAgentId
        if (agentId.isBlank()) return
        val includeArchived = _state.value.showArchived
        val config = config()
        val requestGeneration = generation
        val request = ++sessionsRequest
        activeScope.launch {
            if (requestGeneration != generation || request != sessionsRequest) return@launch
            _state.value = _state.value.copy(isLoadingSessions = true)
            client.sessions(config, agentId, includeArchived).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation || request != sessionsRequest ||
                        agentId != _state.value.currentAgentId || includeArchived != _state.value.showArchived
                    ) return@fold
                    val current = _state.value.currentSessionKey
                    var sessions = response.sessions
                    // 过滤开关关闭时，仍保留正在查看的归档会话，避免切换到「已删除」提示。
                    if (current != null && sessions.none { it.sessionKey == current }) {
                        val kept = _state.value.sessions.firstOrNull {
                            it.sessionKey == current && it.archived
                        }
                        if (kept != null) sessions = listOf(kept) + sessions
                    }
                    if (current != null) {
                        locallyCreated.removeAll { key -> sessions.any { it.sessionKey == key } }
                    }
                    val deleted = current != null &&
                        current !in locallyCreated &&
                        sessions.none { it.sessionKey == current }
                    val known = _state.value.sessions.associate { it.sessionKey to it.updatedAt }
                    val unread = _state.value.unreadSessions.toMutableSet()
                    var lastSeenChanged = false
                    for (session in sessions) {
                        val seen = lastSeenAt[session.sessionKey]
                        if (seen == null) {
                            // 第一次见到该会话：把当前 updatedAt 作为已读基线。
                            lastSeenAt[session.sessionKey] = session.updatedAt
                            lastSeenChanged = true
                            continue
                        }
                        val previous = known[session.sessionKey]
                        val isFresh = session.updatedAt > seen && (previous == null || session.updatedAt > previous)
                        if (isFresh && session.sessionKey != current) {
                            unread.add(session.sessionKey)
                        }
                    }
                    unread.removeAll { key ->
                        agentOf(key) == agentId && sessions.none { it.sessionKey == key }
                    }
                    // 只清理本次拉取的 agent；其他 agent 的已读基线仍需保留。
                    val keep = sessions.mapTo(mutableSetOf()) { it.sessionKey }
                    if (current != null) keep.add(current)
                    lastSeenChanged = lastSeenAt.keys.removeAll { key ->
                        agentOf(key) == agentId && key !in keep
                    } || lastSeenChanged
                    if (lastSeenChanged) persistLastSeen()
                    _state.value = _state.value.copy(
                        sessions = sessions,
                        isLoadingSessions = false,
                        currentSessionDeleted = deleted,
                        unreadSessions = unread,
                    )
                    val pending = pendingOpenSession
                    if (pending != null && sessions.any { it.sessionKey == pending }) {
                        pendingOpenSession = null
                        selectSession(pending)
                    }
                },
                onFailure = { failure ->
                    if (requestGeneration != generation || request != sessionsRequest ||
                        agentId != _state.value.currentAgentId || includeArchived != _state.value.showArchived
                    ) return@fold
                    _state.value = _state.value.copy(
                        isLoadingSessions = false,
                        error = if (_state.value.sessions.isEmpty()) {
                            failure.message ?: "获取会话列表失败"
                        } else {
                            _state.value.error
                        },
                    )
                },
            )
        }
    }

    private suspend fun pollMessages() {
        val activeScope = scope ?: return
        while (activeScope.isActive) {
            try {
            syncConnection()
            val config = config()
            val requestGeneration = generation
            if (!config.isConfigured) {
                delay(2_000)
                continue
            }
            client.pull(config, lastSeq, if (appForeground) MESSAGE_POLL_MS else MESSAGE_POLL_BG_MS).fold(
                onSuccess = { response ->
                    if (requestGeneration != generation) return@fold
                    val records = response.records
                    if (!primed) {
                        primed = true
                        if (records.isNotEmpty()) {
                            // 冷启动积压：静默导入（当前会话补消息、其它会话记未读），不弹通知
                            silentImport(records)
                            lastSeq = maxOf(lastSeq, records.maxOf { it.seq })
                            runCatching { client.ack(config, lastSeq) }
                            settings.saveLastSeq(lastSeq)
                        }
                    } else {
                        handleRecords(records, config)
                        // 服务端若立即返回空（长轮询失效），避免忙循环
                        if (records.isEmpty()) delay(500)
                    }
                    pollFailures = 0
                    if (_state.value.error?.startsWith("OpenClaw 连接中断") == true) {
                        _state.value = _state.value.copy(error = null)
                    }
                    _state.value = _state.value.copy(lastPollAt = System.currentTimeMillis())
                    if (runningSince > 0 && System.currentTimeMillis() - runningSince > 120_000) {
                        runningSince = 0L
                        _state.value = _state.value.copy(running = false)
                    }
                },
                onFailure = {
                    if (requestGeneration != generation) return@fold
                    pollFailures += 1
                    if (pollFailures >= 3) {
                        _state.value = _state.value.copy(error = "OpenClaw 连接中断，正在重试…")
                    }
                    delay(2_000)
                },
            )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                delay(2_000)
            }
        }
    }

    /** 冷启动积压：补消息与未读，但不打扰用户（不发通知）。 */
    private fun silentImport(records: List<io.github.icexxl.etax.channel.openclaw.OutboundRecord>) {
        if (records.isEmpty()) return
        val sessionKey = _state.value.currentSessionKey
        val forCurrent = records.filter { sessionKey != null && it.conversationId == sessionKey }
        if (forCurrent.isNotEmpty()) {
            appendMessages(forCurrent.mapNotNull { it.toMessage() })
        }
        val unread = _state.value.unreadSessions.toMutableSet()
        records
            .filter { it.conversationId != sessionKey }
            .forEach { unread.add(it.conversationId) }
        _state.value = _state.value.copy(
            unreadSessions = unread,
            lastRecordAt = System.currentTimeMillis(),
        )
    }

    /** 处理一批新记录：更新游标、追加当前会话、其余发通知。 */
    private suspend fun handleRecords(
        records: List<io.github.icexxl.etax.channel.openclaw.OutboundRecord>,
        config: OpenClawConfig,
    ) {
        if (records.isEmpty()) return
        lastSeq = maxOf(lastSeq, records.maxOf { it.seq })
        runCatching { client.ack(config, lastSeq) }
        settings.saveLastSeq(lastSeq)
        _state.value = _state.value.copy(lastRecordAt = System.currentTimeMillis())
        val sessionKey = _state.value.currentSessionKey
        val forCurrent = records.filter { sessionKey != null && it.conversationId == sessionKey }
        if (forCurrent.isNotEmpty()) {
            appendMessages(forCurrent.mapNotNull { it.toMessage() })
            // 当前会话的消息立即可见，同步推进已读时间，避免重启后被误判为未读。
            markSeen(sessionKey ?: forCurrent.first().conversationId)
            runningSince = 0L
            _state.value = _state.value.copy(running = false)
            if (!appForeground) {
                forCurrent.forEach { record ->
                    handleBackgroundRecord(record.conversationId, record.text, record.mediaName, markUnread = false)
                }
            }
        }
        records
            .filter { it.conversationId != sessionKey }
            .forEach { record ->
                runCatching { handleBackgroundRecord(record.conversationId, record.text, record.mediaName) }
            }
    }

    /** 非当前会话的新消息：记未读并发通知。 */
    private fun handleBackgroundRecord(
        sessionKey: String,
        text: String,
        mediaName: String? = null,
        markUnread: Boolean = true,
    ) {
        // 纯附件消息没有正文，用文件名兜底，避免通知/未读被丢弃
        val body = text.ifBlank { mediaName?.takeIf { it.isNotBlank() }?.let { "发来文件：$it" } ?: "" }
        if (body.isBlank()) return
        if (markUnread) {
            _state.value = _state.value.copy(
                unreadSessions = _state.value.unreadSessions + sessionKey,
            )
        }
        // 标题只用 agent 名字，不暴露会话 key/label
        val agentId = Regex("^agent:([^:]+):").find(sessionKey)?.groupValues?.getOrNull(1)
        val agentName = _state.value.agents.firstOrNull { it.id == agentId }?.name
            ?: agentId
            ?: "OpenClaw"
        runCatching { notifier?.notify(sessionKey, agentName, body) }
    }

    /** 标记会话已读并立即落盘，重启后未读状态仍能正确恢复。 */
    private fun markSeen(sessionKey: String) {
        // 用 max(本机时间, 服务端 updatedAt)，避免两端时钟偏差让已读会话在重启后又变未读。
        val serverUpdatedAt = _state.value.sessions
            .firstOrNull { it.sessionKey == sessionKey }
            ?.updatedAt
            ?: 0L
        lastSeenAt[sessionKey] = maxOf(System.currentTimeMillis(), serverUpdatedAt)
        persistLastSeen()
    }

    private fun persistLastSeen() {
        runCatching { settings.saveLastSeenAt(lastSeenAt) }
    }

    private fun appendMessages(incoming: List<OpenClawMessage>) {
        if (incoming.isEmpty()) return
        synchronized(lock) {
            _state.value = _state.value.copy(messages = mergeMessages(_state.value.messages, incoming))
        }
    }

    private fun mergeMessages(
        existing: List<OpenClawMessage>,
        incoming: List<OpenClawMessage>,
    ): List<OpenClawMessage> {
        val known = existing.asSequence().map { it.id }.toHashSet()
        val fresh = incoming.filter { known.add(it.id) }
        if (fresh.isEmpty()) return existing
        return (existing + fresh).sortedBy { it.timestamp }
    }

    private companion object {
        const val SESSION_POLL_MS = 3_000L
        const val MESSAGE_POLL_MS = 10_000L
        const val SESSION_POLL_BG_MS = 15_000L
        const val MESSAGE_POLL_BG_MS = 30_000L
        const val HISTORY_LIMIT = 50
    }
}
