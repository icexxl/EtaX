package io.github.icexxl.etax.channel.openclaw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import io.github.icexxl.etax.ui.app.LocalUiStyle
import io.github.icexxl.etax.ui.components.coui.CouiMenu
import io.github.icexxl.etax.ui.components.coui.CouiMenuItem
import io.github.icexxl.etax.ui.components.coui.CouiPageDefaults
import io.github.icexxl.etax.ui.components.coui.CouiTopAppBarIconButton
import io.github.icexxl.etax.ui.components.coui.couiMenuAnchor
import io.github.icexxl.etax.ui.components.coui.rememberCouiMenuAnchor
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * OpenClaw 对话页顶栏的三段式布局。
 *
 * 左侧写当前 agent 名（只显示，不点击）；中间用下拉箭头选会话；
 * 右侧三点菜单只在 agent 之间切换。这样"换会话"和"换 agent"是两条独立路径，
 * 不会像早期那样挤在同一个列表里。
 */

/** 左段：当前 agent 名。 */
@Composable
internal fun OpenClawTopBarAgentName(store: OpenClawHomeStore, onOpenPane: () -> Unit = {}) {
    val state by store.state.collectAsState()
    val name = state.agents.firstOrNull { it.id == state.currentAgentId }?.name
        ?.takeIf { it.isNotBlank() }
        ?: state.currentAgentId.ifBlank { "OpenClaw" }
    // 手势导航会吃掉左边缘滑动，顶栏标题兼作打开侧栏的入口。
    Text(
        text = name,
        style = MiuixTheme.textStyles.headline1,
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(onClick = onOpenPane)
            .padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/** 中段：下拉箭头 + 当前会话名，点开选会话。 */
@Composable
internal fun OpenClawTopBarSessionPicker(store: OpenClawHomeStore) {
    val state by store.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val anchor = rememberCouiMenuAnchor()
    val current = state.sessions.firstOrNull { it.sessionKey == state.currentSessionKey }
    // 服务端 label 常等于完整 sessionKey（agent:jarvis:main），顶栏只显示可读的末段。
    val label = current?.let { readableSessionLabel(it.label.ifBlank { it.sessionKey }) } ?: "选择会话"

    Box {
        Row(
            modifier = Modifier
                .clickable { if (isCoui) anchor.open { show = true } else show = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = "选择会话",
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (isCoui) {
            val items = remember(state.sessions, state.currentSessionKey, state.isLoadingSessions) {
                buildList {
                    if (state.sessions.isEmpty()) {
                        add(
                            CouiMenuItem(
                                value = "hint",
                                text = if (state.isLoadingSessions) "正在加载…" else "还没有会话",
                                enabled = false,
                            ),
                        )
                    }
                    state.sessions.forEach { session ->
                        add(
                            CouiMenuItem(
                                value = "session:${session.sessionKey}",
                                text = readableSessionLabel(session.label.ifBlank { session.sessionKey }),
                                selected = session.sessionKey == state.currentSessionKey,
                            ),
                        )
                    }
                    add(CouiMenuItem(value = "new", text = "新建会话", icon = Icons.Rounded.Add, dividerBefore = true))
                }
            }
            CouiMenu(
                expanded = show,
                onDismissRequest = { show = false },
                items = items,
                onItemClick = { item ->
                    show = false
                    when {
                        item.value.startsWith("session:") ->
                            store.selectSession(item.value.removePrefix("session:"))
                        item.value == "new" -> store.newSession(state.currentAgentId)
                    }
                },
                anchor = anchor,
            )
        } else {
            WindowListPopup(
                show = show,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { show = false },
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 200.dp, max = 300.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    MenuHeader("当前 agent 的会话")
                    if (state.sessions.isEmpty()) {
                        MenuHint(if (state.isLoadingSessions) "正在加载…" else "还没有会话")
                    }
                    state.sessions.forEach { session ->
                        MenuRow(
                            text = readableSessionLabel(session.label.ifBlank { session.sessionKey }),
                            selected = session.sessionKey == state.currentSessionKey,
                            onClick = {
                                store.selectSession(session.sessionKey)
                                show = false
                            },
                        )
                    }
                    MenuRow(
                        text = "新建会话",
                        selected = false,
                        leading = {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            store.newSession(state.currentAgentId)
                            show = false
                        },
                    )
                }
            }
        }
    }
}

/** 右段：三点菜单，只用于切换 agent 和进设置。 */
@Composable
internal fun OpenClawTopBarMenu(store: OpenClawHomeStore, onOpenSettings: () -> Unit) {
    val state by store.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val anchor = rememberCouiMenuAnchor()

    Box {
        if (isCoui) {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors()
            CouiTopAppBarIconButton(
                icon = COUIIcons.More,
                contentDescription = "切换 agent",
                modifier = Modifier.couiMenuAnchor(anchor),
                dark = dark,
                colors = colors,
                onClick = { anchor.open { show = true } },
            )
            val items = remember(state.agents, state.currentAgentId, state.isLoadingAgents) {
                buildList {
                    add(CouiMenuItem(value = "header", text = "切换 agent", enabled = false))
                    if (state.agents.isEmpty()) {
                        add(
                            CouiMenuItem(
                                value = "hint",
                                text = if (state.isLoadingAgents) "正在加载…" else "还没有可用的 agent",
                                enabled = false,
                            ),
                        )
                    }
                    state.agents.forEach { agent ->
                        add(
                            CouiMenuItem(
                                value = "agent:${agent.id}",
                                text = agent.name.ifBlank { agent.id },
                                selected = agent.id == state.currentAgentId,
                            ),
                        )
                    }
                    add(
                        CouiMenuItem(
                            value = "settings",
                            text = "设置",
                            icon = Icons.Rounded.Settings,
                            dividerBefore = true,
                        ),
                    )
                }
            }
            CouiMenu(
                expanded = show,
                onDismissRequest = { show = false },
                items = items,
                onItemClick = { item ->
                    show = false
                    when {
                        item.value.startsWith("agent:") -> store.selectAgent(item.value.removePrefix("agent:"))
                        item.value == "settings" -> onOpenSettings()
                    }
                },
                anchor = anchor,
            )
        } else {
            IconButton(onClick = { show = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "切换 agent",
                    modifier = Modifier.size(24.dp),
                )
            }
            WindowListPopup(
                show = show,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { show = false },
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 200.dp, max = 300.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    MenuHeader("切换 agent")
                    if (state.agents.isEmpty()) {
                        MenuHint(if (state.isLoadingAgents) "正在加载…" else "还没有可用的 agent")
                    }
                    state.agents.forEach { agent ->
                        MenuRow(
                            text = agent.name.ifBlank { agent.id },
                            selected = agent.id == state.currentAgentId,
                            onClick = {
                                store.selectAgent(agent.id)
                                show = false
                            },
                        )
                    }
                    MenuRow(
                        text = "设置",
                        selected = false,
                        leading = {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            show = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }
    }
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
                channel == "eta" -> "Eta 直连"
                channel.startsWith("node-") -> "节点会话"
                else -> channel
            }
        }
    }
    return trimmed
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MenuHint(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun MenuRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "当前",
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        trailing?.invoke()
    }
}
