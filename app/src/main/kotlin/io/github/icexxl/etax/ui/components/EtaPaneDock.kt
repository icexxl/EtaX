package io.github.icexxl.etax.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.icexxl.etax.channel.openclaw.HomeMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 侧栏底部图标区：只保留「模型对话 / OpenClaw 对话 / 设置」三个入口。
 *
 * 模型、工具、Skills、角色、权限等入口仍然可以从设置页进入，底部不再重复占位。
 */
@Composable
internal fun EtaPaneDock(
    homeMode: HomeMode,
    onHomeModeChange: (HomeMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DockEntry(
            icon = Icons.Rounded.Forum,
            label = "模型对话",
            selected = homeMode == HomeMode.ETA,
            onClick = { onHomeModeChange(HomeMode.ETA) },
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.SmartToy,
            label = "OpenClaw 对话",
            selected = homeMode == HomeMode.OPENCLAW,
            onClick = { onHomeModeChange(HomeMode.OPENCLAW) },
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Rounded.Settings,
            label = "设置",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DockEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(ConversationPanelMetrics.DockEntryCornerRadius))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(ConversationPanelMetrics.DockEntryIconSize),
            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
    }
}
