package io.github.icexxl.etax.channel.openclaw

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.components.TopLayer
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.NewMessage
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 对话页顶栏居中标题与模型切换胶囊，样式对齐模型对话的 `AgentTopBarChatHeader`：
 * - 居中呈现当前 agent 名字 + 当前模型与下拉箭头 `[ Agent名 / 模型 ▾ ]`
 * - 点击展开下拉菜单切换当前会话使用的模型（支持恢复默认模型）
 */
@Composable
internal fun OpenClawTopBarHeader(
    store: OpenClawHomeStore,
    backdrop: Backdrop? = null,
    isCurrentRoute: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsState()
    var showPopup by remember { mutableStateOf(false) }
    var headerAnchorBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(isCurrentRoute) {
        if (!isCurrentRoute) showPopup = false
    }

    val agentName = state.agents.firstOrNull { it.id == state.currentAgentId }?.name
        ?.takeIf { it.isNotBlank() }
        ?: state.currentAgentId.ifBlank { "OpenClaw" }
    val activeModel = state.activeModelLabel
    val activeProvider = state.activeModelProvider
    val subtitleText = when {
        state.isSwitchingModel -> "切换模型中…"
        activeModel != null -> activeModel
        else -> null
    }
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    val subtitleColor = ink.copy(alpha = if (isDark) 0.56f else 0.52f)
    val chevronColor = ink.copy(alpha = if (isDark) 0.46f else 0.42f)
    val interactionSource = remember { MutableInteractionSource() }
    val pageBackdrop = backdrop ?: LocalGlassPageBackdrop.current

    val pillShape = GlassShape(20.dp)
    val capsuleModifier = Modifier
        .heightIn(min = 40.dp)
        .glassPress(interactionSource, enabled = true)
        .glassPanel(
            backdrop = pageBackdrop,
            shape = pillShape,
            style = GlassDefaults.style(isDark),
            material = GlassMaterials.puredThinGlass(isDark),
            stroke = GlassEdgeStrokes.forTheme(
                isDark = isDark,
                light = GlassEdgeStrokes.SmallLight,
                dark = GlassEdgeStrokes.SmallDark,
            ),
            shadow = GlassShadows.Float,
            shading = false,
            fallback = Modifier
                .clip(CircleShape)
                .background(GlassTopAppBarDefaults.buttonFill()),
        )
        .padding(start = 15.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)

    val openModelPicker: () -> Unit = {
        if (!state.isLoadingModels) {
            store.loadModels(force = true)
        }
        showPopup = true
    }

    Box(modifier = modifier) {
        if (subtitleText != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .onGloballyPositioned { headerAnchorBounds = it.boundsInWindow() }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .glassPress(interactionSource, enabled = true)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = openModelPicker,
                    )
                    .semantics { contentDescription = "$agentName，$subtitleText，切换模型" }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = agentName,
                    style = MiuixTheme.textStyles.body1.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 1.dp),
                ) {
                    Text(
                        text = subtitleText,
                        style = MiuixTheme.textStyles.footnote2.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                        ),
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 168.dp),
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = chevronColor,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onGloballyPositioned { headerAnchorBounds = it.boundsInWindow() }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = openModelPicker,
                    )
                    .semantics { contentDescription = "$agentName，切换模型" }
                    .then(capsuleModifier),
            ) {
                Text(
                    text = agentName,
                    style = MiuixTheme.textStyles.body2.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = chevronColor,
                )
            }
        }

        if (isCurrentRoute) {
            val overlayBackdrop = currentGlassOverlayBackdrop() ?: pageBackdrop
            TopLayer {
                GlassPopup(
                    show = showPopup,
                    onDismissRequest = { showPopup = false },
                    anchorBounds = headerAnchorBounds,
                    backdrop = overlayBackdrop,
                    sizing = GlassPopupSizing(
                        minWidth = 228.dp,
                        maxWidth = 310.dp,
                        maxHeight = 380.dp,
                    ),
                ) {
                    if (state.models.isEmpty()) {
                        GlassPopupHint(
                            if (state.isLoadingModels) "正在加载模型…" else "暂无可切换模型（需插件 0.7.14+）",
                        )
                    }
                    state.models.forEach { option ->
                        GlassSelectablePopupItem(
                            title = option.name?.takeIf { it.isNotBlank() } ?: option.model,
                            summary = buildModelSummary(option),
                            selected = isModelOptionSelected(option, activeModel, activeProvider),
                            onClick = {
                                showPopup = false
                                store.selectModel(
                                    model = option.model,
                                    modelProvider = option.modelProvider,
                                    isDefault = option.isDefault,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 右段：三点菜单，用于切换会话、新建会话和进入设置（多 agent 时附带切换 agent）。 */
@Composable
internal fun OpenClawTopBarMenu(
    store: OpenClawHomeStore,
    onOpenSettings: () -> Unit,
    backdrop: Backdrop? = null,
    isCurrentRoute: Boolean = true,
) {
    val state by store.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(isCurrentRoute) {
        if (!isCurrentRoute) show = false
    }
    val moreAnchor = rememberGlassPopupAnchor()
    val overlayBackdrop = currentGlassOverlayBackdrop() ?: backdrop
    GlassIconButton(
        onClick = { show = true },
        backdrop = backdrop,
        modifier = Modifier.glassPopupAnchor(
            anchor = moreAnchor,
            cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
            floating = true,
        ),
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.More,
            modifier = Modifier
                .size(28.dp)
                .glassPopupAnchorContent(moreAnchor),
            tint = MiuixTheme.colorScheme.onSurface,
            contentDescription = "切换会话",
        )
    }

    if (isCurrentRoute) {
        TopLayer {
            GlassTransformPopup(
                show = show,
                onDismissRequest = { show = false },
                anchor = moreAnchor,
                backdrop = overlayBackdrop,
                anchorContent = {
                    Icon(
                        imageVector = MiuixIcons.Glass.More,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                },
            ) {
                if (state.sessions.isEmpty()) {
                    GlassPopupHint(if (state.isLoadingSessions) "正在加载…" else "还没有会话")
                }
                state.sessions.forEach { session ->
                    GlassSelectablePopupItem(
                        title = readableSessionLabel(session.label.ifBlank { session.sessionKey }),
                        summary = buildSessionSummary(session),
                        selected = session.sessionKey == state.currentSessionKey,
                        unread = session.sessionKey in state.unreadSessions,
                        onClick = {
                            show = false
                            store.selectSession(session.sessionKey)
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                        vertical = GlassPopupDefaults.DividerPaddingVertical,
                    ),
                    thickness = GlassPopupDefaults.DividerThickness,
                )
                GlassPopupItem(
                    text = "新建会话",
                    icon = MiuixIcons.Glass.NewMessage,
                    onClick = {
                        show = false
                        store.newSession(state.currentAgentId)
                    },
                )
                if (state.agents.size > 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                            vertical = GlassPopupDefaults.DividerPaddingVertical,
                        ),
                        thickness = GlassPopupDefaults.DividerThickness,
                    )
                    state.agents.forEach { agent ->
                        GlassSelectablePopupItem(
                            title = agent.name.ifBlank { agent.id },
                            summary = agent.id.takeIf { agent.name.isNotBlank() && agent.name != it },
                            selected = agent.id == state.currentAgentId,
                            onClick = {
                                show = false
                                store.selectAgent(agent.id)
                            },
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                        vertical = GlassPopupDefaults.DividerPaddingVertical,
                    ),
                    thickness = GlassPopupDefaults.DividerThickness,
                )
                GlassPopupItem(
                    text = "设置",
                    icon = MiuixIcons.Glass.SettingsOutline,
                    onClick = {
                        show = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

internal fun isModelOptionSelected(
    option: ModelOptionDto,
    activeModel: String?,
    activeProvider: String?,
): Boolean {
    if (activeModel.isNullOrBlank()) return false
    if (!option.model.equals(activeModel, ignoreCase = false)) return false
    val optionProvider = option.modelProvider?.takeIf { it.isNotBlank() }
    val currentProvider = activeProvider?.takeIf { it.isNotBlank() }
    return optionProvider == null || currentProvider == null || optionProvider.equals(currentProvider, ignoreCase = true)
}

internal fun buildModelSummary(option: ModelOptionDto): String? {
    val parts = buildList {
        option.modelProvider?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (option.isDefault) add("默认")
        option.alias?.takeIf { it.isNotBlank() }?.let { add(it) }
        option.contextTokens?.takeIf { it > 0 }?.let { add(formatContextWindowLabel(it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatContextWindowLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (tokens % 1_000_000 == 0) "${tokens / 1_000_000}M" else String.format("%.1fM", m)
    }
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

/** 把 agent:jarvis:eta:direct:... 这类 key 收敛成短标签。 */
internal fun readableSessionLabel(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "未命名会话"
    if (trimmed.startsWith("agent:")) {
        val parts = trimmed.split(":")
        // agent:<id>:<channel>[:direct:<target>] -> 取 channel 段作为短标签
        if (parts.size >= 3) {
            val channel = parts[2]
            return when {
                channel == "main" -> "主会话"
                channel == "eta" -> "EtaX 直连"
                channel.startsWith("node-") -> "节点会话"
                else -> channel
            }
        }
    }
    return trimmed
}

private fun buildSessionSummary(session: SessionDto): String? {
    val parts = buildList {
        if (session.pinned) add("置顶")
        if (session.archived) add("已归档")
        val relative = formatRelative(session.updatedAt)
        if (relative.isNotEmpty()) add(relative)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前。 */
internal fun formatRelative(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}

@Composable
private fun GlassPopupHint(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        modifier = Modifier.padding(
            horizontal = GlassPopupDefaults.ItemPaddingHorizontal,
            vertical = GlassPopupDefaults.ItemPaddingVertical,
        ),
    )
}

@Composable
internal fun GlassSelectablePopupItem(
    title: String,
    summary: String? = null,
    selected: Boolean,
    unread: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedColor = GlassPopupDefaults.itemPressedColor()
    val pressFill by animateColorAsState(
        targetValue = if (pressed) pressedColor else Color.Transparent,
        animationSpec = if (pressed) snap() else tween(durationMillis = 150),
        label = "openClawGlassPopupItemPress",
    )
    val selectedColor = MiuixTheme.colorScheme.primary
    val textColor = if (selected) selectedColor else MiuixTheme.colorScheme.onSurfaceContainer
    val summaryColor = MiuixTheme.colorScheme.onSurfaceContainerVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GlassPopupDefaults.ItemMinHeight)
            .padding(horizontal = GlassPopupDefaults.ItemPressInset)
            .drawBehind {
                drawRoundRect(
                    color = pressFill,
                    cornerRadius = CornerRadius(GlassPopupDefaults.ItemPressRadius.toPx()),
                )
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = GlassPopupDefaults.ItemPaddingHorizontal - GlassPopupDefaults.ItemPressInset,
                vertical = GlassPopupDefaults.ItemPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(selectedColor),
                    )
                }
            }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            modifier = Modifier
                .padding(start = GlassPopupDefaults.ItemIconGap)
                .size(GlassPopupDefaults.ItemTickSize),
            tint = if (selected) selectedColor else Color.Transparent,
        )
    }
}
