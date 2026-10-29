package io.github.icexxl.etax.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.icexxl.etax.R
import io.github.icexxl.etax.ui.components.EtaArrowPreference
import io.github.icexxl.etax.ui.components.EtaPreferenceColors
import io.github.icexxl.etax.ui.components.EtaPreferenceDivider
import io.github.icexxl.etax.ui.components.EtaPreferenceGroup
import io.github.icexxl.etax.ui.components.EtaPreferenceGroupTitle
import io.github.icexxl.etax.ui.components.EtaPreferenceIcon
import io.github.icexxl.etax.ui.components.MiuixScaffoldPage
import io.github.icexxl.etax.ui.components.color
import io.github.icexxl.etax.ui.components.label
import io.github.icexxl.etax.ui.model.PermissionHealthAction
import io.github.icexxl.etax.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.icexxl.etax.ui.model.PermissionHealthItemUi
import io.github.icexxl.etax.ui.model.PermissionHealthUiState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "title") {
            EtaPreferenceGroupTitle(stringResource(R.string.ui_permissions_and_status_35f368))
        }
        item(key = "card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                state.items.forEachIndexed { index, item ->
                    if (index > 0) EtaPreferenceDivider()
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    val icon = when (item.id) {
        "accessibility" -> Icons.Rounded.AccessibilityNew
        "overlay" -> Icons.Rounded.Layers
        "model" -> Icons.Rounded.Memory
        "terminal" -> Icons.Rounded.Terminal
        "notification" -> Icons.Rounded.Notifications
        "root" -> Icons.Rounded.Key
        "shizuku" -> Icons.Rounded.Memory
        "xposed" -> Icons.Rounded.AccountTree
        "background" -> Icons.Rounded.History
        "app_list" -> Icons.Rounded.Dashboard
        LOCAL_NETWORK_PERMISSION_ITEM_ID -> Icons.Rounded.AccountTree
        "location" -> Icons.Rounded.LocationOn
        "notification_history" -> Icons.Rounded.NotificationsActive
        "usage_access" -> Icons.Rounded.QueryStats
        "notifications" -> Icons.Rounded.Notifications
        else -> Icons.Rounded.Shield
    }

    EtaArrowPreference(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        startAction = {
            EtaPreferenceIcon(icon = icon, tint = EtaPreferenceColors.Blue)
        },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
