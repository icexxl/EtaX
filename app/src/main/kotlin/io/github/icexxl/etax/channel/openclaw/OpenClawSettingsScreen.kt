package io.github.icexxl.etax.channel.openclaw

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.icexxl.etax.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 渠道设置页（纯配置，不含会话/消息）。
 *
 * 流程：填地址和 Token → 测试连接 → 自动拉取 agent → 勾选要显示的 agent → 保存。
 */
@Composable
internal fun OpenClawSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { OpenClawSettings(context) }
    val client = remember { OpenClawClient() }
    val loaded = remember { settings.load() }

    var baseUrl by remember { mutableStateOf(loaded.baseUrl) }
    var token by remember { mutableStateOf(loaded.token) }
    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var selected by remember { mutableStateOf(settings.visibleAgentIds().toSet()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var notificationsGranted by remember { mutableStateOf(context.hasNotificationPermission()) }
    var tokenVisible by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = context.hasNotificationPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun currentConfig() = OpenClawConfig(
        baseUrl = baseUrl.trim(),
        token = token.trim(),
        agentId = loaded.agentId,
        conversationId = loaded.conversationId,
    )

    fun testConnection() {
        if (testing) return
        val config = currentConfig()
        if (!config.isConfigured) {
            status = "请先填写 Gateway 地址和 Token"
            statusOk = false
            return
        }
        testing = true
        status = "正在连接…"
        statusOk = true
        val startedAt = System.currentTimeMillis()
        scope.launch {
            client.agents(config).fold(
                onSuccess = { response ->
                    val elapsed = System.currentTimeMillis() - startedAt
                    agents = response.agents
                    // 首次连接默认全选；已有选择则保留仍存在的项
                    val available = response.agents.map { it.id }.toSet()
                    selected = if (selected.isEmpty()) available else selected.intersect(available)
                    status = "连接成功 · ${response.agents.size} 个 agent · ${elapsed}ms"
                    statusOk = true
                    testing = false
                },
                onFailure = { failure ->
                    agents = emptyList()
                    status = "连接失败：${failure.message ?: "未知错误"}"
                    statusOk = false
                    testing = false
                },
            )
        }
    }

    fun save() {
        if (saving) return
        saving = true
        settings.save(currentConfig())
        settings.saveVisibleAgentIds(selected.toList())
        status = "已保存"
        statusOk = true
        saving = false
        if (!notificationsGranted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onSaved()
    }

    MiuixScaffoldPage(
        title = "OpenClaw 渠道",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "connection") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(
                    label = "Gateway 地址",
                    value = baseUrl,
                    placeholder = "http://192.168.1.10:18789 或 https://openclaw.example.com",
                    onValueChange = { baseUrl = it },
                )
                Field(
                    label = "Token",
                    value = token,
                    placeholder = "channels.eta.accounts.default.token",
                    onValueChange = { token = it },
                    password = !tokenVisible,
                    trailing = {
                        Text(
                            text = if (tokenVisible) "隐藏" else "显示",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { tokenVisible = !tokenVisible },
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = if (testing) "连接中…" else "测试连接",
                        onClick = { testConnection() },
                        enabled = !testing && !saving,
                    )
                    TextButton(
                        text = if (saving) "保存中…" else "保存",
                        onClick = { save() },
                        enabled = !saving,
                    )
                }
                status?.let { text ->
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = if (statusOk) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                    )
                }
                if (!notificationsGranted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "通知权限未授权，收不到新消息提醒",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "授权",
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                    }
                }
            }
        }

        item(key = "agents_header") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "显示的 agent",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (agents.isEmpty()) {
                        "测试连接成功后会自动拉取，可多选"
                    } else {
                        "已选 ${selected.size}/${agents.size}，主界面的 agent 切换只显示这些"
                    },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (agents.isNotEmpty()) {
            items(count = agents.size, key = { index -> agents[index].id }) { index ->
                val agent = agents[index]
                val checked = agent.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - agent.id else selected + agent.id
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(
                            text = agent.name.ifBlank { agent.id },
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = listOf(agent.id)
                                .plus(agent.channels.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "无渠道绑定")
                                .joinToString(" · "),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        item(key = "footer") {
            Text(
                text = "提示：同一局域网可直接填 http://内网IP:18789；公网访问请使用 HTTPS，避免 Token 在明文链路上泄露。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

private fun android.content.Context.hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (password) {
                    androidx.compose.ui.text.input.KeyboardType.Password
                } else {
                    androidx.compose.ui.text.input.KeyboardType.Text
                },
            ),
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
