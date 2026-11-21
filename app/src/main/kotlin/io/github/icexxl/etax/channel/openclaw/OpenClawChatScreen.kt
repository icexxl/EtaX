package io.github.icexxl.etax.channel.openclaw

import com.itos.coui.theme.CouiTheme
import io.github.icexxl.etax.ui.app.LocalUiStyle
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import io.github.icexxl.etax.ui.components.chatInputSurface
import io.github.icexxl.etax.ui.components.ChatInputLayout
import io.github.icexxl.etax.ui.components.rememberChatInputExpansion
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import io.github.icexxl.etax.ui.components.ChatInputActionIconSize
import io.github.icexxl.etax.ui.components.ChatInputActionSize
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AttachFile
import io.github.icexxl.etax.ui.markdown.MarkdownTone
import io.github.icexxl.etax.ui.markdown.StaticMarkdown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableIntStateOf

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


    LaunchedEffect(state.currentSessionKey) {
        previousLastMessageId = null
    }

    // 只在「最新一条」变化时滚到底；加载更早的历史不应把视图拉回底部。
    LaunchedEffect(lastMessageId) {
        val latest = lastMessageId ?: return@LaunchedEffect
        if (latest == previousLastMessageId) return@LaunchedEffect
        previousLastMessageId = latest
        val headerItems = if (state.hasMoreHistory || state.isLoadingMoreHistory) 1 else 0
        listState.animateScrollToItem(state.messages.lastIndex + headerItems)
    }

    val density = LocalDensity.current
    var composerHeightPx by remember { mutableStateOf(0) }
    val composerHeight = with(density) { composerHeightPx.toDp() }
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(top = topInset + 10.dp, bottom = composerHeight + 14.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (state.currentSessionKey == null) {
                item {
                    Text(
                        text = "从左侧选择一个会话，或点 ＋ 新建",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
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
                ChatBubble(message, onRetry = { store.retryMessage(message.id) })
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { composerHeightPx = it.height }
                .imePadding()
                .background(Brush.verticalGradient(listOf(Color.Transparent, MiuixTheme.colorScheme.surface)))
                .navigationBarsPadding(),
        ) {
            if (state.currentSessionDeleted) {
                NoticeLine("该会话已在 OpenClaw 中被删除，请从左侧选择其他会话")
            }
            state.error?.let { NoticeLine(it) }
            val activity = when {
                state.sending -> "发送中…"
                state.running -> {
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
                io.github.icexxl.etax.ui.components.ShimmerText(
                    text = activity,
                    active = state.sending || state.running,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            // Share the model composer surface and automatic multiline expansion.
            val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
            val couiDark = CouiTheme.isDark
            val canSend = state.currentSessionKey != null && !state.sending && !readingAttachment &&
                (draft.text.isNotBlank() || pendingAttachment != null)
            val inputExpansion = rememberChatInputExpansion(draft)
            val expanded = inputExpansion.expanded
            val enabled = state.currentSessionKey != null
            val inputTextStyle = MiuixTheme.textStyles.body1.copy(lineHeight = 22.sp)

            val textArea: @Composable (Modifier) -> Unit = { areaModifier ->
                Box(
                    modifier = areaModifier
                        .defaultMinSize(minHeight = ChatInputActionSize)
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.text.isBlank()) {
                        Text(
                            text = if (enabled) "发消息给 OpenClaw…" else "先选择会话",
                            style = inputTextStyle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        state = draft,
                        enabled = enabled && !state.sending,
                        textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        onTextLayout = { result -> inputExpansion.textLayout = result() },
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            val attachButton: @Composable () -> Unit = {
                IconButton(
                    onClick = {
                        pickerSession = state.currentSessionKey
                        filePicker.launch(arrayOf("*/*"))
                    },
                    enabled = state.currentSessionKey != null && !state.sending && !readingAttachment,
                    minWidth = ChatInputActionSize,
                    minHeight = ChatInputActionSize,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "添加附件",
                        modifier = Modifier.size(ChatInputActionIconSize),
                        tint = if (state.currentSessionKey != null) {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                    )
                }
            }
            val sendButton: @Composable () -> Unit = {
                IconButton(
                    onClick = {
                        val text = draft.text.toString().trim()
                        val attachment = pendingAttachment
                        if (attachment != null) {
                            if (store.uploadFile(attachment.name, attachment.mime, attachment.bytes, text)) {
                                pendingAttachment = null
                                draft.clearText()
                            }
                        } else if (text.isNotEmpty()) {
                            store.send(text)
                            draft.clearText()
                        }
                    },
                    enabled = canSend,
                    minWidth = ChatInputActionSize,
                    minHeight = ChatInputActionSize,
                ) {
                    val sendColor = when {
                        state.sending -> MiuixTheme.colorScheme.onSurface
                        canSend -> MiuixTheme.colorScheme.primary
                        isCoui -> if (couiDark) Color(0xFF2E3136) else Color(0xFFE1E4E9)
                        else -> MiuixTheme.colorScheme.surfaceContainerHigh
                    }
                    Box(
                        modifier = Modifier
                            .size(if (isCoui) 32.dp else ChatInputActionIconSize)
                            .clip(CircleShape)
                            .background(sendColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "发送",
                            modifier = Modifier.size(if (isCoui) 18.dp else 16.dp),
                            tint = when {
                                canSend -> MiuixTheme.colorScheme.onPrimary
                                else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp)
                    .chatInputSurface()
                    .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                if (readingAttachment) {
                    Text("正在读取附件…", modifier = Modifier.padding(12.dp), style = MiuixTheme.textStyles.body2)
                }
                pendingAttachment?.let { attachment ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            attachment.name,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.body2,
                        )
                        IconButton(onClick = { pendingAttachment = null }, enabled = !state.sending) {
                            Icon(Icons.Rounded.Close, contentDescription = "移除附件", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                ChatInputLayout(
                    expanded = expanded,
                    expansion = inputExpansion,
                    leading = attachButton,
                    editor = { textArea(Modifier) },
                    trailing = sendButton,
                )
            }
        }
    }
}

@Composable
private fun MediaAttachment(message: OpenClawMessage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { OpenClawClient() }
    val config = remember(context) { OpenClawSettings(context).load() }
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
            OpenClawMedia.downloadWithRetry(context, client, config, src, name).fold(
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
        OpenClawMedia.downloadWithRetry(context, client, config, src, name).fold(
            onSuccess = { file ->
                localFile = file
                val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OpenClawMedia.decodeImage(file)
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
                imageVector = Icons.Rounded.InsertDriveFile,
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
private fun ChatBubble(message: OpenClawMessage, onRetry: () -> Unit) {
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
                    if (!message.mediaUrl.isNullOrBlank()) MediaAttachment(message)
                    if (message.mediaName != null && message.mediaUrl == null) {
                        Text(text = message.mediaName, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
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
        if (!message.mediaUrl.isNullOrBlank()) MediaAttachment(message)
        if (message.text.isNotBlank()) {
            // 助手回复按 Markdown 渲染（标题/列表/代码块/表格），与主对话一致
            SelectionContainer {
                StaticMarkdown(
                    content = message.text,
                    tone = MarkdownTone.Answer,
                    modifier = Modifier.fillMaxWidth(),
                    selectable = true,
                )
            }
        }
    }
}

internal data class OpenClawPendingAttachment(val name: String, val mime: String, val bytes: ByteArray)

private val OpenClawUserBubbleShape = RoundedCornerShape(
    topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp,
)
