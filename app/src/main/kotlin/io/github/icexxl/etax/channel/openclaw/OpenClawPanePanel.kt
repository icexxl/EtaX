package io.github.icexxl.etax.channel.openclaw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.icexxl.etax.ui.components.EtaPaneDock
import io.github.icexxl.etax.ui.components.conversationPaneContainerColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn

/**
 * OpenClaw 模式下的侧栏面板：agent 切换 + 服务端会话列表 + 底部 dock。
 */
@Composable
internal fun OpenClawPanePanel(
    store: OpenClawHomeStore,
    width: Dp,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onHomeModeChange: (HomeMode) -> Unit,
    modifier: Modifier = Modifier,
    onSessionSelected: () -> Unit = {},
) {
    val state by store.state.collectAsState()
    val listState = rememberLazyListState()
    var agentPickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(conversationPaneContainerColor())
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = io.github.icexxl.etax.ui.components.ConversationPanelMetrics.PaneHorizontalPadding),
    ) {
        Spacer(
            Modifier.height(io.github.icexxl.etax.ui.components.ConversationPanelMetrics.TopInset + 6.dp),
        )

        // agent 切换器：点箭头在下方展开下拉列表（不再弹整窗对话框）
        Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .clickable { agentPickerOpen = !agentPickerOpen }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.agents.firstOrNull { it.id == state.currentAgentId }?.name
                        ?: state.currentAgentId.ifBlank { "选择 agent" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.currentAgentId.ifBlank { "—" },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = "切换 agent",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.rotate(if (agentPickerOpen) 180f else 0f),
            )
        }

        if (agentPickerOpen) {
            val density = LocalDensity.current
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, with(density) { 6.dp.roundToPx() }),
                onDismissRequest = { agentPickerOpen = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(with(density) { 260.dp })
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .padding(vertical = 6.dp)
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.agents.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.selectAgent(agent.id)
                                    agentPickerOpen = false
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = agent.name.ifBlank { agent.id },
                                fontSize = 13.sp,
                                color = if (agent.id == state.currentAgentId) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (agent.id == state.currentAgentId) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    state.isLoadingSessions && state.sessions.isEmpty() -> "加载中…"
                    state.error != null && state.sessions.isEmpty() -> "加载失败"
                    else -> "会话 ${state.sessions.size}"
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            // 归档过滤开关：默认隐藏，打开后随会话列表一起显示，重启保持。
            Text(
                text = if (state.showArchived) "隐藏归档" else "显示归档",
                fontSize = 11.sp,
                color = if (state.showArchived) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier
                    .clickable { store.setShowArchived(!state.showArchived) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "新建会话",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        store.newSession()
                        onSessionSelected()
                    }
                    .padding(4.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        text = if (state.currentAgentId.isBlank()) "先选择一个 agent" else "该 agent 还没有会话",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(state.sessions, key = { it.sessionKey }) { session ->
                val selected = session.sessionKey == state.currentSessionKey
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            store.selectSession(session.sessionKey)
                            onSessionSelected()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.label,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (session.pinned) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "置顶",
                                fontSize = 9.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        if (session.sessionKey in state.unreadSessions) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val channel = session.channel ?: "本地"
                        Text(
                            text = channel,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatRelative(session.updatedAt),
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (session.archived) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "已归档",
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ),
        ) {
        EtaPaneDock(
            homeMode = HomeMode.OPENCLAW,
            onHomeModeChange = onHomeModeChange,
            onOpenSettings = onOpenSettings,
            onOpenModelProviders = onOpenModelProviders,
            onOpenTools = onOpenTools,
            onOpenSkills = onOpenSkills,
            onOpenCharacters = onOpenCharacters,
            onOpenPermissions = onOpenPermissions,
        )
        Spacer(Modifier.height(8.dp))
        }
    }
}

/** 相对时间：刚刚 / N 分钟 / N 小时 / N 天。 */
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
