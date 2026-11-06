package io.github.icexxl.etax.ui.components

import io.github.icexxl.etax.ui.voice.SpeechDictationButton
import io.github.icexxl.etax.ui.voice.SpeechInputFeedback
import io.github.icexxl.etax.ui.voice.rememberSpeechInput
import androidx.compose.ui.text.TextRange
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.icexxl.etax.R
import io.github.icexxl.etax.data.model.ReasoningEffort
import io.github.icexxl.etax.data.repository.SpeechSettingsRepository
import io.github.icexxl.etax.ui.model.AgentContextUsageUi
import io.github.icexxl.etax.ui.model.AgentModelPickerUiState
import io.github.icexxl.etax.ui.model.PendingFileReferenceUi
import io.github.icexxl.etax.ui.model.PendingImageUi
import io.github.icexxl.etax.ui.model.QueuedMessageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)

/** 输入卡片底色：深灰实色，和黑底光效分层。 */
private val ChatInputCardColor = Color(0xFF1E1F21)

@Composable
internal fun AgentChatInputBar(
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    focusInputOnSync: Boolean = true,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = input)
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val recognitionEnabled = speechSettings?.recognitionEnabled == true
    val dictation = rememberSpeechInput { recognized ->
        textFieldState.edit {
            val range = selection
            replace(range.min, range.max, recognized)
            selection = TextRange(range.min + recognized.length)
        }
    }
    LaunchedEffect(isEditingMessage, recognitionEnabled) { dictation.cancel() }
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    var syncedVersion by remember { mutableIntStateOf(inputSyncVersion) }
    val canSend = textFieldState.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage, inputSyncVersion) {
        // 编辑态与排队消息撤回由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        val versionChanged = inputSyncVersion != syncedVersion
        if (isEditingMessage || wasEditingMessage || versionChanged) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage || (versionChanged && input.isNotEmpty() && focusInputOnSync)) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
        syncedVersion = inputSyncVersion
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (recognitionEnabled) SpeechInputFeedback(dictation)
        AnimatedVisibility(
            visible = queuedMessages.isNotEmpty(),
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            AgentChatQueueStrip(
                queuedMessages = queuedMessages,
                isStreaming = isStreaming,
                onSteer = onSteerQueuedMessage,
                onRecall = { id -> onRecallQueuedMessage(id, textFieldState.text.toString()) },
                onRemove = onRemoveQueuedMessage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    stringResource(R.string.chat_edit_preserve_following)
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 与输入框内正文同一起点（容器 10dp + 文本 8dp）。
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
        }

        // 跑马灯：光标进入输入框、已有文字或正在回复时，一道彩光沿边框绕行
        var inputFocused by remember { mutableStateOf(false) }
        // 整圈幻彩边框（缓慢流动、不闪烁）只在"空闲时点进输入框或有文字"时显示。
        // 刚启动、没聚焦也没文字时不显示；思考和输出回答全程（isStreaming）都不显示，
        // 即使这时点进输入框或打了字。
        val pulseActive = !isStreaming &&
            (inputFocused || textFieldState.text.isNotBlank())
        val pulseT = rememberInputPulseProgress(pulseActive, marquee = false)
        val pulseGlow = Modifier.inputPulseBorder(pulseT, cornerRadius = 26.dp, iridescent = true)

        // 单行 / 多行：空或一行时是一排（+ 输入 麦克风 发送）；
        // 文字换行后卡片展开，输入区占满整行，按钮落到下方一排。
        var textLineCount by remember { mutableIntStateOf(1) }
        // 单行宽度下排不下就展开；展开后按满宽重新排版，少于 2 行时收回（留一点回差避免来回跳）
        val expanded = textLineCount > 1 || isEditingMessage

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape = InputContainerShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = 0.08f,
                        ),
                    )
                    .squircleSurface(
                        // 深色实底：和背景光效分开，文字更清楚
                        color = ChatInputCardColor,
                        cornerRadius = 26.dp,
                    )
                    .then(pulseGlow)
                    .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                @Composable
                fun SendControls() {
                    val showStreamingSend = isStreaming && !isCompacting && canSend
                    val submitCurrentDraft: (Boolean) -> Unit = { interrupt ->
                        if (canSend) {
                            dictation.cancel()
                            val submittedText = textFieldState.text.toString()
                            textFieldState.clearText()
                            if (interrupt) {
                                onInterruptAndSubmit(submittedText)
                            } else {
                                onSubmit(submittedText)
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showStreamingSend,
                        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.78f) + expandHorizontally(tween(160)),
                        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f) + shrinkHorizontally(tween(140)),
                    ) {
                        IconButton(
                            onClick = onStop,
                            minWidth = ChatInputActionSize,
                            minHeight = ChatInputActionSize,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(SendButtonVisualSize)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.chat_stop),
                                    modifier = Modifier.size(StopIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    val showPrimaryStop = isStreaming && !showStreamingSend
                    val sendButtonColor by animateColorAsState(
                        targetValue = when {
                            showPrimaryStop -> MiuixTheme.colorScheme.onSurface
                            canSend -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                        animationSpec = tween(durationMillis = 160),
                        label = "send_button_color",
                    )
                    Box(
                        modifier = Modifier
                            .size(ChatInputActionSize)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = isStreaming || canSend,
                                onClick = {
                                    if (showPrimaryStop) {
                                        onStop()
                                    } else {
                                        submitCurrentDraft(false)
                                    }
                                },
                                onLongClick = if (showStreamingSend) {
                                    {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        submitCurrentDraft(true)
                                    }
                                } else {
                                    null
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 保留统一的点击区域，仅让可见圆形与相邻操作图标保持同一尺寸。
                        Box(
                            modifier = Modifier
                                .size(SendButtonVisualSize)
                                .clip(CircleShape)
                                .background(sendButtonColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                targetState = showPrimaryStop,
                                transitionSpec = {
                                    (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                        .togetherWith(
                                            fadeOut(tween(90)) +
                                                scaleOut(tween(110), targetScale = 0.72f)
                                        )
                                },
                                label = "send_stop_icon",
                            ) { stopIcon ->
                                Icon(
                                    imageVector = if (stopIcon) {
                                        Icons.Rounded.Stop
                                    } else {
                                        Icons.Rounded.ArrowUpward
                                    },
                                    contentDescription = when {
                                        stopIcon -> stringResource(R.string.chat_stop)
                                        isEditingMessage && preserveFollowingMessages -> stringResource(R.string.chat_save_message)
                                        else -> stringResource(R.string.chat_send)
                                    },
                                    modifier = Modifier.size(
                                        if (stopIcon) StopIconSize else SendIconSize
                                    ),
                                    tint = when {
                                        stopIcon -> MiuixTheme.colorScheme.surface
                                        canSend -> MiuixTheme.colorScheme.onPrimary
                                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                    }
                }

                val inputTextStyle = MiuixTheme.textStyles.body1.copy(lineHeight = 22.sp)
                val character = LocalRoleplayChat.current

                // 文字区：单行时夹在按钮之间，多行时独占一整行
                val textArea: @Composable (Modifier) -> Unit = { areaModifier ->
                    Box(
                        modifier = areaModifier
                            .defaultMinSize(minHeight = ChatInputActionSize)
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // 占位文字与输入正文共用同一行高，首字输入时基线不跳动。
                        if (textFieldState.text.isBlank()) {
                            Text(
                                text = when {
                                    // 角色对话的占位文字跟随角色，避免“交给 Eta”打断沉浸感。
                                    character != null && isStreaming ->
                                        stringResource(R.string.chat_character_typing, character.characterName)
                                    character != null -> stringResource(R.string.chat_character_input_hint, character.characterName)
                                    isStreaming -> stringResource(R.string.chat_eta_working)
                                    else -> stringResource(R.string.chat_input_hint)
                                },
                                style = inputTextStyle,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { inputFocused = it.isFocused },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(
                                minHeightInLines = 1,
                                maxHeightInLines = 6,
                            ),
                            onTextLayout = { result ->
                                result()?.let { layout ->
                                    val lines = layout.lineCount.coerceAtLeast(1)
                                    if (!expanded) {
                                        // 单行模式下溢出（换行）→ 展开
                                        if (lines > 1) textLineCount = lines
                                    } else {
                                        // 展开模式：满宽下仍不足一半行宽且只有一行，或内容清空 → 收回
                                        val shortEnough = lines == 1 &&
                                            layout.getLineRight(0) < layout.size.width * 0.55f
                                        if (textFieldState.text.isEmpty() || shortEnough) {
                                            textLineCount = 1
                                        } else if (lines != textLineCount && lines > 1) {
                                            textLineCount = lines
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                // 左侧按钮：附件 + 思考档位（编辑消息时换成取消）
                val leadingActions: @Composable () -> Unit = {
                    if (isEditingMessage) {
                        IconButton(
                            onClick = onCancelMessageEdit,
                            minWidth = ChatInputActionSize,
                            minHeight = ChatInputActionSize,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                modifier = Modifier.size(ChatInputActionIconSize),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        AgentAttachmentPickerButton(
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onAttachImage = onAttachImage,
                            onAttachFiles = onAttachFiles,
                            onAttachFolder = onAttachFolder,
                            onAttachFilePath = onAttachFilePath,
                        )
                        if (expanded && availableReasoningEfforts.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(2.dp))
                            ThinkingEffortChip(
                                effort = reasoningEffort,
                                options = availableReasoningEfforts,
                                enabled = !isStreaming,
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onEffortChange = onReasoningEffortChange,
                            )
                        }
                    }
                }

                // 右侧按钮：麦克风、模型选择、发送常驻；展开后再补上上下文用量
                val trailingActions: @Composable () -> Unit = {
                    if (expanded && showContextUsage) {
                        AgentContextUsageButton(usage = contextUsage, onCompact = onCompactContext, canCompact = canCompactContext && !isStreaming)
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    if (recognitionEnabled) {
                        SpeechDictationButton(dictation, enabled = !isCompacting)
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    run {
                        AgentModelPickerButton(
                            state = modelPickerState,
                            isStreaming = isStreaming,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                        )
                    }
                    SendControls()
                }

                // 同一棵布局树：输入框始终在同一位置，切换单行/多行时不会重建、不会丢焦点。
                // 单行：[左按钮][输入框][右按钮] 一排；多行：输入框占满整行，按钮落到下面一排。
                androidx.compose.ui.layout.Layout(
                    content = {
                        Row(verticalAlignment = Alignment.CenterVertically) { leadingActions() }
                        textArea(Modifier)
                        Row(verticalAlignment = Alignment.CenterVertically) { trailingActions() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { measurables, constraints ->
                    val loose = constraints.copy(minWidth = 0, minHeight = 0)
                    val lead = measurables[0].measure(loose)
                    val trail = measurables[2].measure(loose)
                    val full = constraints.maxWidth
                    if (!expanded) {
                        val textWidth = (full - lead.width - trail.width).coerceAtLeast(0)
                        val text = measurables[1].measure(
                            loose.copy(minWidth = textWidth, maxWidth = textWidth),
                        )
                        val height = maxOf(lead.height, text.height, trail.height)
                        layout(full, height) {
                            lead.placeRelative(0, (height - lead.height) / 2)
                            text.placeRelative(lead.width, (height - text.height) / 2)
                            trail.placeRelative(full - trail.width, (height - trail.height) / 2)
                        }
                    } else {
                        val text = measurables[1].measure(loose.copy(minWidth = full, maxWidth = full))
                        val rowHeight = maxOf(lead.height, trail.height)
                        val height = text.height + rowHeight
                        layout(full, height) {
                            text.placeRelative(0, 0)
                            lead.placeRelative(0, text.height + (rowHeight - lead.height) / 2)
                            trail.placeRelative(full - trail.width, text.height + (rowHeight - trail.height) / 2)
                        }
                    }
                }
            }
        }
    }

}

/** 思考强度选择保持为单一图标，当前状态仅通过图标颜色表达。 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.size > 1
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            enabled = menuEnabled,
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = stringResource(R.string.chat_reasoning_effort, effort.displayName),
                modifier = Modifier.size(ThinkingIconSize),
                tint = contentColor,
            )
        }
        WindowListPopup(
            show = showPopup && menuEnabled && popupAnchorTopPx > 0,
            popupPositionProvider = popupPositionProvider,
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option.displayName,
                        optionSize = options.size,
                        isSelected = option == effort,
                        index = index,
                        onSelectedIndexChange = {
                            onEffortChange(option)
                            dismiss?.invoke()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(images.size) }
    LaunchedEffect(images.size) {
        if (images.size > previousCount && images.isNotEmpty()) {
            listState.animateScrollToItem(images.lastIndex)
        }
        previousCount = images.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = images,
            key = { it.id },
        ) { image ->
            Box(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(durationMillis = 120),
                    )
                    .attachmentItemEntrance()
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 可见圆点保持小巧，触控区放大到右上角 32dp，避免误触缩略图。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clickable(interactionSource = null, indication = null) { onRemoveImage(image.id) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ui_remove_image_089db3),
                            modifier = Modifier.size(11.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
