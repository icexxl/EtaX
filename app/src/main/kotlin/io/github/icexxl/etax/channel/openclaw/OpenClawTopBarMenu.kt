package io.github.icexxl.etax.channel.openclaw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * OpenClaw 对话页右上角的切换按钮（和模型对话页的「更多」按钮同一位置）。
 *
 * 一级菜单：agent 列表，点名字直接切到该 agent；右侧「›」进入二级菜单。
 * 二级菜单：该 agent 的会话列表，点会话切过去；顶部「‹」返回一级，底部可新建会话。
 * 进入别的 agent 的二级菜单时会先切到那个 agent，再展示它的会话。
 */
@Composable
internal fun OpenClawTopBarMenu(store: OpenClawHomeStore) {
    val state by store.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    // null = 一级（agent 列表）；非 null = 该 agent 的会话列表
    var sessionsOf by remember { mutableStateOf<String?>(null) }

    // 二级菜单里的 agent 不是当前 agent 时，切过去以便加载它的会话
    LaunchedEffect(sessionsOf) {
        val target = sessionsOf ?: return@LaunchedEffect
        if (target != state.currentAgentId) store.selectAgent(target)
    }

    Box {
        IconButton(onClick = { sessionsOf = null; show = true }) {
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
            AnimatedContent(
                targetState = sessionsOf,
                transitionSpec = {
                    val forward = targetState != null
                    (slideInHorizontally(tween(180)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(160)))
                        .togetherWith(
                            slideOutHorizontally(tween(160)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(120)),
                        )
                },
                label = "openclaw_menu_level",
            ) { level ->
                Column(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 300.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    if (level == null) {
                        MenuHeader("切换 agent")
                        if (state.agents.isEmpty()) {
                            MenuHint(if (state.isLoadingAgents) "正在加载…" else "还没有可用的 agent")
                        }
                        state.agents.forEach { agent ->
                            val name = agent.name.ifBlank { agent.id }
                            MenuRow(
                                text = name,
                                selected = agent.id == state.currentAgentId,
                                onClick = {
                                    store.selectAgent(agent.id)
                                    show = false
                                },
                                trailing = {
                                    // 「›」：进入该 agent 的会话列表（二级菜单）
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable { sessionsOf = agent.id },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = "查看 $name 的会话",
                                            modifier = Modifier.size(20.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        val agentName = state.agents.firstOrNull { it.id == level }?.name?.ifBlank { null } ?: level
                        Row(
                            modifier = Modifier
                                .clickable { sessionsOf = null }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回 agent 列表",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$agentName 的会话",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val ready = state.currentAgentId == level
                        val sessions = if (ready) state.sessions else emptyList()
                        if (!ready || (sessions.isEmpty() && state.isLoadingSessions)) {
                            MenuHint("正在加载…")
                        } else if (sessions.isEmpty()) {
                            MenuHint("还没有会话")
                        }
                        sessions.forEach { session ->
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
                                store.newSession(level)
                                show = false
                            },
                        )
                    }
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
