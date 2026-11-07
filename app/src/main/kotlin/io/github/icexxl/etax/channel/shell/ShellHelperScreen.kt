package io.github.icexxl.etax.channel.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.icexxl.etax.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 免 Root 虚拟屏的设置页：引导用户开无线调试、配对、拉起 shell 助手，并显示连接状态。
 *
 * 配对码只在配对那一次需要；配对成功后钥匙留在本机，之后只需要「连接」。
 */
@Composable
internal fun ShellHelperScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checkBusy by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<ShellDisplaySelfCheck.Result?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    // 订阅连接状态：助手连上/断开时这一页要立刻反映出来，而不是停在进入时的快照
    val shell by EtaShellConnection.hub(context).shell.collectAsState()
    val connected = shell != null

    fun report(message: String, ok: Boolean) {
        status = message
        statusOk = ok
    }

    MiuixScaffoldPage(title = "免 Root 虚拟屏", onBack = onBack, modifier = modifier) {
        item(key = "status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (connected) "已连接 shell 助手" else "未连接",
                    fontSize = 15.sp,
                    color = if (connected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = "没有 Root 时，虚拟屏靠系统「无线调试」以 shell 身份运行一个常驻助手。" +
                        "先在开发者选项里打开「无线调试」，用「使用配对码配对设备」配对一次。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item(key = "steps") {
            Actions(
                pairPort = pairPort,
                onPairPortChange = { pairPort = it.filter(Char::isDigit).take(5) },
                connectPort = connectPort,
                onConnectPortChange = { connectPort = it.filter(Char::isDigit).take(5) },
                code = code,
                onCodeChange = { code = it.filter(Char::isDigit).take(6) },
                busy = busy,
                onPair = {
                    val portValue = pairPort.toIntOrNull()
                    if (portValue == null || code.isBlank()) {
                        report("请填写配对弹窗里的端口和配对码", false)
                        return@Actions
                    }
                    busy = true
                    scope.launch {
                        val ok = HelperLauncher.pair(context, portValue, code)
                        busy = false
                        report(
                            if (ok) "配对成功，可以点「连接助手」" else "配对失败：${HelperLauncher.lastError ?: "未知错误"}",
                            ok,
                        )
                    }
                },
                onLaunch = {
                    busy = true
                    scope.launch {
                        val ok = HelperLauncher.launch(context, connectPort.toIntOrNull())
                        busy = false
                        val connectedNow = EtaShellConnection.isConnected(context)
                        val detail = HelperLauncher.lastError
                            ?: HelperLauncher.helperOutput?.takeIf { it.isNotBlank() }?.let { "助手输出：$it" }
                        report(
                            when {
                                connectedNow -> "助手已连接，虚拟屏可用"
                                else -> "连接失败：${detail ?: "助手未回报连接"}"
                            },
                            connectedNow,
                        )
                    }
                },
                onForget = {
                    busy = true
                    scope.launch {
                        val cleared = EtaShellConnection.forget(context)
                        busy = false
                        report(
                            if (cleared) "已断开并清除配对" else "断开未完全成功：${HelperLauncher.lastError ?: "助手资源释放失败"}",
                            cleared,
                        )
                    }
                },
                checkBusy = checkBusy,
                onSelfCheck = {
                    checkBusy = true
                    scope.launch {
                        val metrics = context.resources.displayMetrics
                        val result = ShellDisplaySelfCheck.run(
                            context = context,
                            width = metrics.widthPixels,
                            height = metrics.heightPixels,
                            dpi = metrics.densityDpi,
                        )
                        checkBusy = false
                        checkResult = result
                    }
                },
            )
            checkResult?.let { result ->
                Text(
                    text = if (result.ok) "自检：${result.message}" else "自检失败：${result.message}",
                    fontSize = 12.sp,
                    color = if (result.ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            status?.let { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = if (statusOk) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Actions(
    pairPort: String,
    onPairPortChange: (String) -> Unit,
    connectPort: String,
    onConnectPortChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    onPair: () -> Unit,
    onLaunch: () -> Unit,
    onForget: () -> Unit,
    checkBusy: Boolean,
    onSelfCheck: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(
            label = "配对端口",
            value = pairPort,
            placeholder = "配对弹窗里的端口，例如 37251",
            onValueChange = onPairPortChange,
        )
        Field(label = "配对码", value = code, placeholder = "6 位数字", onValueChange = onCodeChange, numeric = true)
        Field(
            label = "连接端口（可留空自动查找）",
            value = connectPort,
            placeholder = "无线调试主页显示的端口",
            onValueChange = onConnectPortChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(text = "配对", onClick = onPair, enabled = !busy && !checkBusy)
            TextButton(text = "连接助手", onClick = onLaunch, enabled = !busy && !checkBusy)
            TextButton(text = "断开/清除配对", onClick = onForget, enabled = !busy && !checkBusy)
            TextButton(text = if (checkBusy) "自检中…" else "自检", onClick = onSelfCheck, enabled = !busy && !checkBusy)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                inner()
            },
        )
    }
}
