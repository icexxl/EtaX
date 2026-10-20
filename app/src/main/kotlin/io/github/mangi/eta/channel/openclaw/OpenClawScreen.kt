package io.github.mangi.eta.channel.openclaw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 渠道页：连接配置 + 对话。
 *
 * MVP 说明：
 * - 消息仅保存在内存（OpenClawChatStore），离开页面即清空；
 * - 文案暂为硬编码中文，未接入 stringResource。
 */
@Composable
fun OpenClawScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) {
        OpenClawChatStore(
            client = OpenClawClient(),
            settings = OpenClawSettings(context),
            history = OpenClawHistory(context),
        ).also { it.attach(scope) }
    }
    val state by store.state.collectAsState()
    var showSettings by remember { mutableStateOf(!state.config.isConfigured) }
    var attachOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        onDispose { store.disconnect() }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Header(
            title = "OpenClaw",
            subtitle = state.connection.label(),
            onBack = onBack,
            onToggleSettings = { showSettings = !showSettings },
            onReconnect = {
                store.disconnect()
                store.connect()
            },
        )

        ConversationBar(
            conversations = state.conversations,
            activeId = state.activeConversationId,
            onSelect = store::switchConversation,
            onCreate = { store.createConversation() },
            onAttach = { attachOpen = true },
        )

        if (state.error != null) {
            Text(
                text = state.error.orEmpty(),
                color = MiuixTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (showSettings) {
            SettingsCard(
                config = state.config,
                onSave = { config ->
                    store.saveConfig(config)
                    showSettings = false
                    store.connect()
                },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }

        val activityLabel = when {
            state.sending -> "发送中…"
            state.running -> "OpenClaw 正在回复…"
            else -> null
        }
        if (activityLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = activityLabel,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        InputBar(
            value = draft,
            enabled = state.config.isConfigured,
            sending = state.sending,
            onValueChange = { draft = it },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    store.send(text)
                    draft = ""
                }
            },
        )
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onToggleSettings: () -> Unit,
    onReconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        IconButton(onClick = onReconnect) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "重新连接",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        IconButton(onClick = onToggleSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "连接设置",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@Composable
private fun ConversationBar(
    conversations: List<OpenClawChatStore.Conversation>,
    activeId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onAttach: () -> Unit,
) {
    val listState = rememberLazyListState()
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(conversations, key = { it.id }) { conversation ->
            val selected = conversation.id == activeId
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(conversation.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (conversation.unread > 0) {
                        "${conversation.id} · ${conversation.unread}"
                    } else {
                        conversation.id
                    },
                    fontSize = 12.sp,
                    color = if (selected) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            Box(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .clickable { onCreate() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "＋ 新会话",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            Box(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .clickable { onAttach() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "附加…",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AttachSessionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "附加到已有会话",
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "填入 Gateway 的会话 key（例如 agent:main:main），" +
                    "即可接着在 Discord/Telegram 里聊的那个会话继续。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Field(
                label = "会话 key",
                value = key,
                placeholder = "agent:main:main",
                onValueChange = { key = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "取消",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { if (key.isNotBlank()) onConfirm(key.trim()) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "附加",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    config: OpenClawConfig,
    onSave: (OpenClawConfig) -> Unit,
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var agentId by remember(config) { mutableStateOf(config.agentId) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                MiuixTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(label = "Gateway 地址", value = baseUrl, placeholder = "https://openclaw.example.com:55443", onValueChange = { baseUrl = it })
        Field(label = "Token", value = token, placeholder = "channels.eta.accounts.default.token", onValueChange = { token = it })
        Field(label = "Agent", value = agentId, placeholder = "main", onValueChange = { agentId = it })
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .clickable {
                    onSave(
                        OpenClawConfig(
                            baseUrl = baseUrl,
                            token = token,
                            conversationId = config.conversationId,
                            agentId = agentId,
                        ),
                    )
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "保存并连接",
                color = MiuixTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.disabledOnSurface,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun MessageBubble(message: OpenClawMessage) {
    if (message.role == OpenClawMessage.Role.SYSTEM) {
        // 系统提示（失败原因、状态）居中显示，不参与对话气泡语义
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.text,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        return
    }
    val isUser = message.role == OpenClawMessage.Role.USER
    val bubbleColor = when {
        message.failed -> MiuixTheme.colorScheme.error
        isUser -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val textColor = when {
        message.failed -> MiuixTheme.colorScheme.onPrimary
        isUser -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled && !sending,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = if (enabled) "发消息给 OpenClaw…" else "请先配置连接",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.disabledOnSurface,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (enabled && !sending) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(20.dp),
                )
                .clickable(enabled = enabled && !sending) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Send,
                contentDescription = "发送",
                tint = if (enabled && !sending) {
                    MiuixTheme.colorScheme.onPrimary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun OpenClawChatStore.Connection.label(): String = when (this) {
    OpenClawChatStore.Connection.DISCONNECTED -> "未连接"
    OpenClawChatStore.Connection.CONNECTING -> "连接中…"
    OpenClawChatStore.Connection.CONNECTED -> "已连接"
    OpenClawChatStore.Connection.ERROR -> "连接异常"
}
