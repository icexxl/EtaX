package io.github.icexxl.etax.channel.openclaw

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.icexxl.etax.host.EtaXHost.toUi
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.AgentContextUsageButton
import io.github.mangi.eta.ui.components.BottomFollowDecision
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.coui.rememberCouiPageBackdrop
import io.github.icexxl.etax.ui.components.openClawInputSurface
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import io.github.mangi.eta.ui.components.resolveBottomFollowDecision
import io.github.mangi.eta.ui.components.smoothBottomFollowStep
import io.github.mangi.eta.ui.markdown.EditorialLineHeightStyle
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import io.github.mangi.eta.ui.voice.SpeechDictationButton
import io.github.mangi.eta.ui.voice.SpeechInputFeedback
import io.github.mangi.eta.ui.voice.rememberSpeechInput
import io.github.suqi8.coui.kmp.blur.layerBackdrop as couiLayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import java.io.File
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 模式的主区域：当前会话的消息流 + 输入栏。
 *
 * 连接配置、agent 与会话选择都在侧栏完成；这里只负责对话本身。
 */
@Composable
internal fun OpenClawChatScreen(
    store: OpenClawHomeStore,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val uploadScope = rememberCoroutineScope()
    // Separate drafts prevent attachments selected for A being sent to B.
    val draft = androidx.compose.runtime.key(state.currentSessionKey) { rememberTextFieldState() }
    var pendingAttachment by remember(state.currentSessionKey) { mutableStateOf<OpenClawPendingAttachment?>(null) }
    var readingAttachment by remember(state.currentSessionKey) { mutableStateOf(false) }
    var pickerSession by remember { mutableStateOf<String?>(null) }
    val currentSession = androidx.compose.runtime.rememberUpdatedState(state.currentSessionKey)
    val listState = rememberLazyListState()

    // Selection only stages the file; sending waits for the send button.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val selectedSession = pickerSession ?: return@rememberLauncherForActivityResult
        if (currentSession.value != selectedSession) return@rememberLauncherForActivityResult
        readingAttachment = true
        uploadScope.launch {
            try {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                var name = "file"
                runCatching {
                    resolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                }
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { resolver.openInputStream(uri)?.use { it.readNBytes(64 * 1024 * 1024 + 1) } }.getOrNull()
                }
                if (bytes == null) {
                    Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (bytes.size > 64 * 1024 * 1024) {
                    Toast.makeText(context, "文件超过 64MB", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (currentSession.value == selectedSession) {
                    pendingAttachment = OpenClawPendingAttachment(name, mime, bytes)
                }
            } finally {
                if (currentSession.value == selectedSession) readingAttachment = false
            }
        }
    }
    val lastMessageId = state.messages.lastOrNull()?.id
    var previousLastMessageId by remember { mutableStateOf<String?>(null) }
    val hasLivePreview = state.liveActive &&
        (state.liveText.isNotBlank() || state.liveReasoning.isNotBlank() || state.liveTools.isNotEmpty() || state.liveCompacting)
    val density = LocalDensity.current
    val densityScale = density.density
    var composerHeightPx by remember { mutableStateOf(0) }
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var keepBottomAnchored by remember { mutableStateOf(true) }

    LaunchedEffect(state.currentSessionKey) {
        previousLastMessageId = null
        keepBottomAnchored = true
    }

    // 追踪用户手动上滑/回底：上滑查看历史时暂停自动跟底，滑回底部附近后恢复跟底。
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { it.key == OPENCLAW_BOTTOM_SENTINEL_KEY }
            val viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
            val nearBottom = sentinel != null && (sentinel.offset + sentinel.size) <= viewportEnd + (28f * densityScale)
            isUserDragging to nearBottom
        }
            .distinctUntilChanged()
            .collect { (dragging, nearBottom) ->
                if (dragging) {
                    keepBottomAnchored = nearBottom
                } else if (nearBottom && !keepBottomAnchored) {
                    keepBottomAnchored = true
                }
            }
    }

    // 新消息到来或首次进入会话时定位到最底部哨兵（而非长消息顶部）；加载更早历史不拉回底部。
    LaunchedEffect(lastMessageId) {
        val latest = lastMessageId ?: return@LaunchedEffect
        if (latest == previousLastMessageId) return@LaunchedEffect
        val isInitial = previousLastMessageId == null
        previousLastMessageId = latest
        if (isInitial || keepBottomAnchored) {
            keepBottomAnchored = true
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                if (isInitial) {
                    listState.scrollToItem(total - 1)
                } else {
                    listState.animateScrollToItem(total - 1)
                }
            }
        }
    }

    // 复用主对话同款帧时钟平滑跟底：持续追踪底部哨兵相对输入框顶沿的溢出像素，
    // 不反复打断重启滚动动画，彻底避免文字掉到输入框下面或上滚卡顿。
    val shouldFollowBottom by rememberUpdatedState(
        keepBottomAnchored && !isUserDragging && (hasLivePreview || state.sending || state.running),
    )
    val bottomFollowDecisions = remember(listState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val bottomIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { it.key == OPENCLAW_BOTTOM_SENTINEL_KEY }
            Triple(
                shouldFollowBottom,
                bottomIndex,
                Triple(
                    sentinel?.let { it.offset + it.size },
                    layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                    layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                ),
            )
        }
            .distinctUntilChanged()
            .collect { (enabled, bottomIndex, metrics) ->
                val decision = resolveBottomFollowDecision(
                    enabled = enabled,
                    bottomItemIndex = bottomIndex,
                    sentinelBottom = metrics.first,
                    viewportEnd = metrics.second,
                    lastVisibleIndex = metrics.third,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    LaunchedEffect(listState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }
            val targetIndex = requestIndex
            if (targetIndex != null) {
                listState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                continue
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                listState.scroll {
                    scrollBy(step)
                    consumedStep = step
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val chatBackdrop = if (isCoui) null else rememberGlassBackdrop()
    val couiChatBackdrop = if (isCoui) rememberCouiPageBackdrop(CouiTheme.colorScheme.backgroundWithCard) else null
    val couiCapture = if (couiChatBackdrop == null) Modifier else Modifier.couiLayerBackdrop(couiChatBackdrop)
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val recognitionEnabled = speechSettings?.recognitionEnabled == true
    val latestPendingAttachment = androidx.compose.runtime.rememberUpdatedState(pendingAttachment)
    val latestSending = androidx.compose.runtime.rememberUpdatedState(state.sending)

    fun submitTextAndAttachment(text: String) {
        val body = text.trim()
        val attachment = latestPendingAttachment.value
        if (attachment != null) {
            if (store.uploadFile(attachment.name, attachment.mime, attachment.bytes, body)) {
                keepBottomAnchored = true
                pendingAttachment = null
                draft.clearText()
            }
        } else if (body.isNotEmpty()) {
            keepBottomAnchored = true
            store.send(body)
            draft.clearText()
        }
    }

    val dictation = rememberSpeechInput(
        onHoldResult = { recognized ->
            val spoken = recognized.trim()
            if (spoken.isEmpty() || currentSession.value == null || latestSending.value) {
                return@rememberSpeechInput
            }
            val currentDraft = draft.text.toString()
            val toSubmit = if (currentDraft.isBlank()) {
                spoken
            } else {
                draft.edit {
                    val range = selection
                    replace(range.min, range.max, spoken)
                    selection = TextRange(range.min + spoken.length)
                }
                draft.text.toString().trim()
            }
            submitTextAndAttachment(toSubmit)
        },
        onResult = { recognized ->
            if (currentSession.value == null) return@rememberSpeechInput
            draft.edit {
                val range = selection
                replace(range.min, range.max, recognized)
                selection = TextRange(range.min + recognized.length)
            }
        },
    )
    val dictationState by dictation.state.collectAsState()
    var holdCancelling by remember { mutableStateOf(false) }
    val isHoldingSpeech =
        dictationState.active && dictationState.holdToTalk &&
            dictationState.phase != EtaSpeechPhase.RECOGNIZING
    LaunchedEffect(isHoldingSpeech) {
        if (!isHoldingSpeech) holdCancelling = false
    }
    LaunchedEffect(state.currentSessionKey, recognitionEnabled) {
        dictation.cancel()
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .captureGlassBackdrop(chatBackdrop)
                .then(couiCapture),
            contentPadding = PaddingValues(top = topInset + 10.dp, bottom = composerHeight + 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (state.currentSessionKey == null) {
                item {
                    Text(
                        text = "点击顶栏选择会话，或新建会话",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (state.hasMoreHistory || state.isLoadingMoreHistory) {
                item(key = "load-earlier") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isLoadingMoreHistory) {
                                store.loadMoreHistory()
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (state.isLoadingMoreHistory) {
                                "正在加载更早的消息…"
                            } else {
                                "加载更早的消息"
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
            itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                val previous = state.messages.getOrNull(index - 1)
                // 间隔超过 5 分钟才显示时间，避免每条都挂一行
                if (previous == null || message.timestamp - previous.timestamp > 5 * 60_000L) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                ChatBubble(
                    message = message,
                    mediaClient = store.mediaClient(),
                    configProvider = store::config,
                    onRetry = { store.retryMessage(message.id) },
                )
            }
            if (hasLivePreview) {
                item(key = "live-streaming-bubble") {
                    ChatBubble(
                        message = OpenClawMessage(
                            id = state.liveRunId ?: "live",
                            conversationId = state.currentSessionKey.orEmpty(),
                            role = OpenClawMessage.Role.ASSISTANT,
                            text = state.liveText,
                            timestamp = System.currentTimeMillis(),
                            reasoning = state.liveReasoning.takeIf { it.isNotBlank() },
                            tools = state.liveTools,
                            streaming = true,
                        ),
                        mediaClient = store.mediaClient(),
                        configProvider = store::config,
                        onRetry = {},
                        compacting = state.liveCompacting,
                    )
                }
            }
            item(key = OPENCLAW_BOTTOM_SENTINEL_KEY) {
                Spacer(Modifier.height(1.dp))
            }
        }

        val surface = MiuixTheme.colorScheme.surface
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { composerHeightPx = it.height }
                .imePadding()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.26f to surface.copy(alpha = 0.86f),
                        0.55f to surface.copy(alpha = 0.98f),
                        1.0f to surface,
                    ),
                )
                .navigationBarsPadding(),
        ) {
            if (state.currentSessionDeleted) {
                NoticeLine("该会话已在 OpenClaw 中被删除，请从顶栏选择其他会话")
            }
            state.error?.let { NoticeLine(it) }
            val bannerText = when {
                state.isCompacting -> "正在压缩上下文…"
                state.isResetting -> "正在重置当前会话…"
                state.liveCompacting -> "OpenClaw 正在自动压缩上下文…"
                else -> state.statusBanner
            }
            if (bannerText != null) {
                OpenClawStatusBanner(
                    text = bannerText,
                    dismissible = !state.isCompacting && !state.isResetting && !state.liveCompacting,
                    onDismiss = { store.dismissStatusBanner() },
                )
            }
            val activity = when {
                state.sending -> "发送中…"
                state.isRunActive -> {
                    val agentName = state.agents.firstOrNull { it.id == state.currentAgentId }?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: state.currentAgentId.ifBlank { "OpenClaw" }
                    "$agentName 回复中…"
                }
                state.isLoadingHistory -> "正在加载历史…"
                else -> null
            }
            if (activity != null) {
                // 与主对话一致的扫光：主色高光缓慢掠过文字
                io.github.mangi.eta.ui.components.ShimmerText(
                    text = activity,
                    active = state.sending || state.isRunActive,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            // 与模型对话 AgentChatInputBar 完全同构的双层玻璃输入栏 + 思考强度 + 语音听写 + 上下文用量圆环 + 停止按钮 + / 斜杠指令补全。
            val enabled = state.currentSessionKey != null
            val draftText = draft.text.toString()
            val showSlashSheet = enabled && !state.sending &&
                draftText.startsWith("/") && !draftText.contains("\n") && !draftText.contains(" ")
            LaunchedEffect(showSlashSheet) {
                if (showSlashSheet && state.commands.isEmpty() && !state.isLoadingCommands) {
                    store.loadCommands(force = true)
                }
            }
            val canSend = enabled && !state.sending && !readingAttachment &&
                (draft.text.isNotBlank() || pendingAttachment != null)
            val showStopButton = enabled && state.isRunActive && !canSend
            val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
            val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
            val inputTextStyle = MiuixTheme.textStyles.body1.copy(
                lineHeight = 22.sp,
                lineHeightStyle = EditorialLineHeightStyle,
            )

            if (showSlashSheet) {
                OpenClawSlashCommandSheet(
                    query = draftText,
                    commands = state.commands,
                    onPick = { cmd ->
                        when (cmd.command.lowercase()) {
                            "/compact" -> {
                                draft.clearText()
                                store.compactSession()
                            }
                            "/new", "/reset" -> {
                                draft.clearText()
                                store.resetSession()
                            }
                            "/stop" -> {
                                draft.clearText()
                                store.abortRun()
                            }
                            else -> if (cmd.acceptsArgs) {
                                val next = "${cmd.command} "
                                draft.edit {
                                    replace(0, length, next)
                                    selection = TextRange(next.length)
                                }
                            } else {
                                submitTextAndAttachment(cmd.command)
                            }
                        }
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
            ) {
                if (recognitionEnabled) {
                    SpeechInputFeedback(dictation, holdCancelling = holdCancelling)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .openClawInputSurface(
                            glassBackdrop = chatBackdrop,
                            couiBackdrop = couiChatBackdrop,
                            isHoldingSpeech = isHoldingSpeech,
                            holdCancelling = holdCancelling,
                        )
                        .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (readingAttachment) {
                        Text(
                            text = "正在读取附件…",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    pendingAttachment?.let { attachment ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 2.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                            Text(
                                text = attachment.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            IconButton(
                                onClick = { pendingAttachment = null },
                                enabled = !state.sending,
                                minWidth = 30.dp,
                                minHeight = 30.dp,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 36.dp)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (draft.text.isBlank()) {
                            val hintColor by animateColorAsState(
                                targetValue = when {
                                    isHoldingSpeech && holdCancelling -> StatusError
                                    isHoldingSpeech -> MiuixTheme.colorScheme.primary
                                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                animationSpec = tween(durationMillis = 150),
                                label = "openclaw_input_hint_color",
                            )
                            Text(
                                text = when {
                                    isHoldingSpeech && holdCancelling ->
                                        stringResource(R.string.speech_hold_release_to_cancel)
                                    isHoldingSpeech -> stringResource(R.string.voice_release_to_send)
                                    !enabled -> "先选择会话"
                                    else -> "发消息给 OpenClaw…"
                                },
                                style = inputTextStyle,
                                color = hintColor,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            state = draft,
                            enabled = enabled && !state.sending,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(
                                minHeightInLines = 1,
                                maxHeightInLines = 6,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                pickerSession = state.currentSessionKey
                                filePicker.launch(arrayOf("*/*"))
                            },
                            enabled = enabled && !state.sending && !readingAttachment,
                            minWidth = 34.dp,
                            minHeight = 34.dp,
                        ) {
                            Icon(
                                imageVector = if (isCoui) Icons.Rounded.Add else MiuixIcons.Glass.Add,
                                contentDescription = "添加附件",
                                modifier = Modifier.size(if (isCoui) 21.dp else 23.dp),
                                tint = if (enabled) {
                                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                } else {
                                    MiuixTheme.colorScheme.disabledOnSurface
                                },
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        OpenClawThinkingChip(
                            currentLevel = state.activeThinkingLevel,
                            enabled = enabled && !state.sending,
                            switching = state.isSwitchingThinking,
                            onSelect = { level -> store.selectThinkingLevel(level) },
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        state.activeContextUsage?.let { usage ->
                            AgentContextUsageButton(
                                usage = usage.toUi(),
                                canCompact = !state.isCompacting && !state.isRunActive,
                                onCompact = { store.compactSession() },
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        if (recognitionEnabled) {
                            SpeechDictationButton(
                                controller = dictation,
                                enabled = enabled && !state.sending,
                                holdCancelling = holdCancelling,
                                onHoldCancellingChange = { holdCancelling = it },
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        val sendButtonColor by animateColorAsState(
                            targetValue = when {
                                showStopButton -> MiuixTheme.colorScheme.onSurface
                                state.sending -> MiuixTheme.colorScheme.onSurface
                                canSend -> MiuixTheme.colorScheme.primary
                                else -> ink.copy(alpha = if (isDark) 0.08f else 0.06f)
                            },
                            animationSpec = tween(durationMillis = 160),
                            label = "openclaw_send_button_color",
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable(
                                    enabled = if (showStopButton) !state.isAborting else canSend,
                                    onClick = {
                                        if (showStopButton) {
                                            store.abortRun()
                                        } else {
                                            dictation.cancel()
                                            submitTextAndAttachment(draft.text.toString())
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(sendButtonColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (showStopButton) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                                    contentDescription = if (showStopButton) "停止生成" else "发送",
                                    modifier = Modifier.size(16.dp),
                                    tint = when {
                                        showStopButton -> MiuixTheme.colorScheme.surface
                                        canSend -> MiuixTheme.colorScheme.onPrimary
                                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaAttachment(
    message: OpenClawMessage,
    client: OpenClawApi,
    configProvider: () -> OpenClawConfig,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = OpenClawMedia.displayName(message.mediaUrl, message.mediaName)
    val isImage = OpenClawMedia.isImage(name)
    var bitmap by remember(message.id) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(message.id) { mutableStateOf(isImage) }
    var failed by remember(message.id) { mutableStateOf(false) }
    var localFile by remember(message.id) { mutableStateOf<File?>(null) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun openFile() {
        val src = message.mediaUrl ?: return
        val cached = localFile
        if (cached != null) {
            if (!OpenClawMedia.open(context, cached, OpenClawMedia.mimeOf(name))) toast("没有可打开此文件的应用")
            return
        }
        scope.launch {
            OpenClawMedia.downloadWithRetry(context, client, configProvider(), src, name).fold(
                onSuccess = { file ->
                    localFile = file
                    if (!OpenClawMedia.open(context, file, OpenClawMedia.mimeOf(name))) {
                        toast("已下载，但没有可打开此文件的应用")
                    }
                },
                onFailure = { toast(it.message ?: "下载失败") },
            )
        }
    }

    LaunchedEffect(message.mediaUrl, isImage) {
        val src = message.mediaUrl ?: return@LaunchedEffect
        if (!isImage) return@LaunchedEffect
        loading = true
        OpenClawMedia.downloadWithRetry(context, client, configProvider(), src, name).fold(
            onSuccess = { file ->
                localFile = file
                val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OpenClawMedia.decodeBitmap(file)?.asImageBitmap()
                }
                bitmap = decoded
                failed = decoded == null
                loading = false
            },
            onFailure = {
                failed = true
                loading = false
            },
        )
    }

    when {
        isImage && bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { openFile() },
        )
        isImage && loading -> Text(
            text = "正在加载图片…",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        else -> Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable { openFile() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(text = name, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                Text(
                    text = if (failed) "加载失败，点击重试" else "点击打开",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 今天 HH:mm / 昨天 HH:mm / MM-dd HH:mm。 */
private fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val zone = java.util.TimeZone.getDefault()
    val message = java.util.Calendar.getInstance(zone).apply { timeInMillis = timestamp }
    val now = java.util.Calendar.getInstance(zone)
    val sameDay = message.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        message.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = now.clone() as java.util.Calendar
    yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val isYesterday = message.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
        message.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)
    val hm = String.format(
        java.util.Locale.getDefault(),
        "%02d:%02d",
        message.get(java.util.Calendar.HOUR_OF_DAY),
        message.get(java.util.Calendar.MINUTE),
    )
    return when {
        sameDay -> hm
        isYesterday -> "昨天 $hm"
        else -> String.format(
            java.util.Locale.getDefault(),
            "%02d-%02d %s",
            message.get(java.util.Calendar.MONTH) + 1,
            message.get(java.util.Calendar.DAY_OF_MONTH),
            hm,
        )
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun ChatBubble(
    message: OpenClawMessage,
    mediaClient: OpenClawApi,
    configProvider: () -> OpenClawConfig,
    onRetry: () -> Unit,
    compacting: Boolean = false,
) {
    val textStyle = MiuixTheme.textStyles.body1.copy(
        color = MiuixTheme.colorScheme.onSurface,
        lineHeight = 22.sp,
    )
    if (message.role == OpenClawMessage.Role.SYSTEM) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = message.text,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        return
    }
    if (message.role == OpenClawMessage.Role.USER) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 316.dp)
                    .clip(OpenClawUserBubbleShape)
                    .background(
                        color = if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                            if (CouiTheme.isDark) Color(0xFF202634) else Color(0xFFEEF2FA)
                        } else MiuixTheme.colorScheme.surfaceContainerHigh,
                        shape = OpenClawUserBubbleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.5.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!message.mediaUrl.isNullOrBlank()) {
                        MediaAttachment(message, mediaClient, configProvider)
                    }
                    val mediaName = message.mediaName
                    if (mediaName != null && message.mediaUrl == null) {
                        Text(text = mediaName, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                    if (message.text.isNotBlank()) {
                        SelectionContainer {
                            androidx.compose.foundation.text.BasicText(
                                text = message.text,
                                style = textStyle,
                            )
                        }
                    }
                    if (message.failed) {
                        Text(
                            text = "发送失败 · 点击重试",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.clickable(onClick = onRetry),
                        )
                    } else if (message.pending) {
                        Text(text = "发送中…", fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        return
    }
    // assistant：和主对话一样直接铺在背景上，不用气泡
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (compacting) {
            Text(
                text = "正在自动压缩上下文…",
                fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        val reasoning = message.reasoning
        if (!reasoning.isNullOrBlank()) {
            OpenClawReasoningBlock(
                reasoning = reasoning,
                streaming = message.streaming && message.text.isBlank(),
            )
        }
        if (message.tools.isNotEmpty()) {
            OpenClawToolsStrip(
                tools = message.tools,
                running = message.streaming,
            )
        }
        if (!message.mediaUrl.isNullOrBlank()) {
            MediaAttachment(message, mediaClient, configProvider)
        }
        if (message.text.isNotBlank()) {
            // 流式阶段接入与主对话一致的逐字显现引擎（控制速度并柔和淡入）；完成后切回可选择的静态视图
            if (message.streaming) {
                val streamState = remember(message.id) { StreamingMarkdownState() }
                StreamingMarkdown(
                    state = streamState,
                    content = message.text,
                    isStreaming = true,
                    tone = MarkdownTone.Answer,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SelectionContainer {
                    StaticMarkdown(
                        content = message.text,
                        tone = MarkdownTone.Answer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private const val OPENCLAW_BOTTOM_SENTINEL_KEY = "openclaw-bottom-sentinel"

internal data class OpenClawPendingAttachment(val name: String, val mime: String, val bytes: ByteArray)

private val OpenClawUserBubbleShape = RoundedCornerShape(
    topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp,
)
