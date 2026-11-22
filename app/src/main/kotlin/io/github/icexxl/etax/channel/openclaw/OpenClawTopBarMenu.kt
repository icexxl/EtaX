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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.icexxl.etax.ui.components.coui.CouiMenuItem
import io.github.icexxl.etax.ui.components.coui.CouiMenu
import io.github.icexxl.etax.ui.components.coui.couiMenuAnchor
import io.github.icexxl.etax.ui.components.coui.rememberCouiMenuAnchor
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import io.github.icexxl.etax.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import io.github.icexxl.etax.ui.components.coui.CouiPageDefaults
import io.github.icexxl.etax.ui.components.coui.CouiTopAppBarIconButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More

/**
 * OpenClaw 对话页右上角的切换按钮（和模型对话页的「更多」按钮同一位置）。
 *
 * 一级菜单：agent 列表，点名字直接切到该 agent；右侧「›」进入二级菜单。
 * 二级菜单：该 agent 的会话列表，点会话切过去；顶部「‹」返回一级，底部可新建会话。
 * 进入别的 agent 的二级菜单时会先切到那个 agent，再展示它的会话。
 */
@Composable
internal fun OpenClawTopBarMenu(store: OpenClawHomeStore, onOpenSettings: () -> Unit) {
    val state by store.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val anchor = rememberCouiMenuAnchor()

    // 一级菜单同时列出 agent 与会话：切 agent、切会话、新建会话、进设置都在同一个列表，
    // 与模型对话页顶栏的溢出菜单保持同样的扁平结构。
    val sessionHint = when {
        state.currentAgentId.isBlank() -> "先选择一个 agent"
        state.sessions.isEmpty() -> if (state.isLoadingSessions) "正在加载…" else "还没有会话"
        else -> null
    }
    val canCreateSession = state.currentAgentId.isNotBlank()

    Box {
        if (isCoui) {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors()
            CouiTopAppBarIconButton(
                icon = COUIIcons.More,
                contentDescription = "切换 agent 和会话",
                modifier = Modifier.couiMenuAnchor(anchor),
                dark = dark,
                colors = colors,
                onClick = { anchor.open { show = true } },
            )
            val items = remember(
                state.agents,
                state.currentAgentId,
                state.sessions,
                state.currentSessionKey,
                state.isLoadingAgents,
                state.isLoadingSessions,
            ) {
                buildList {
                    add(CouiMenuItem(value = "agents_header", text = "切换 agent", enabled = false))
                    if (state.agents.isEmpty()) {
                        add(
                            CouiMenuItem(
                                value = "no_agent",
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
                            value = "sessions_header",
                            text = "当前 agent 的会话",
                            enabled = false,
                            dividerBefore = true,
                        ),
                    )
                    if (state.currentAgentId.isBlank()) {
                        add(CouiMenuItem(value = "no_agent", text = "先选择一个 agent", enabled = false))
                    } else {
                        if (state.sessions.isEmpty()) {
                            add(
                                CouiMenuItem(
                                    value = "sessions_hint",
                                    text = if (state.isLoadingSessions) "正在加载…" else "还没有会话",
                                    enabled = false,
                                ),
                            )
                        }
                        state.sessions.forEach { session ->
                            add(
                                CouiMenuItem(
                                    value = "session:${session.sessionKey}",
                                    text = session.label.ifBlank { "未命名会话" },
                                    selected = session.sessionKey == state.currentSessionKey,
                                ),
                            )
                        }
                        add(CouiMenuItem(value = "new", text = "新建会话", icon = Icons.Rounded.Add, dividerBefore = true))
                    }
                    add(CouiMenuItem(value = "settings", text = "设置", icon = Icons.Rounded.Settings, dividerBefore = true))
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
                        item.value.startsWith("session:") -> store.selectSession(item.value.removePrefix("session:"))
                        item.value == "new" -> store.newSession(state.currentAgentId)
                        item.value == "settings" -> onOpenSettings()
                    }
                },
                anchor = anchor,
            )
        } else {
            IconButton(onClick = { show = true }) {
                Icon(
                    imageVector = Icons.Rounded.SwitchAccount,
                    modifier = Modifier.size(22.dp),
                    contentDescription = "切换 agent 和会话",
                )
            }
            WindowListPopup(
                show = show,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { show = false },
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 300.dp)
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
                    MenuHeader("当前 agent 的会话")
                    sessionHint?.let { MenuHint(it) }
                    state.sessions.forEach { session ->
                        MenuRow(
                            text = session.label.ifBlank { "未命名会话" },
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
                            if (canCreateSession) store.newSession(state.currentAgentId)
                            show = false
                        },
                    )
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
