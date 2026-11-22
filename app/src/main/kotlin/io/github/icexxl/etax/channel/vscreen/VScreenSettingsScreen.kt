package io.github.icexxl.etax.channel.vscreen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.icexxl.etax.agent.virtualdisplay.VirtualDisplayController
import io.github.icexxl.etax.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun VScreenSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by VScreenBackend.readiness.collectAsState()
    val screen by VirtualDisplayController.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        VScreenBackend.refresh()
        onPauseOrDispose { }
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    MiuixScaffoldPage(title = "后台虚拟屏", onBack = onBack, modifier = modifier) {
        item(key = "vscreen_status") {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (state) {
                    VScreenBackend.State.NOT_INSTALLED -> "尚未安装 Shizuku"
                    VScreenBackend.State.NOT_RUNNING -> "Shizuku 尚未启动"
                    VScreenBackend.State.NEED_PERMISSION -> "等待授权 EtaX"
                    VScreenBackend.State.READY -> "Shizuku 已就绪"
                }, fontSize = 17.sp)
                Text("后台副屏基于 Agent-VScreen 的 Shizuku 独立服务。先在 Shizuku 中启动服务，再授权 EtaX；不需要在 EtaX 中输入无线调试端口或配对码。", fontSize = 14.sp)
                Text("服务存活时，重新打开 EtaX 可重新接入已有副屏；Shizuku 被停止或手机重启后，需要先重新启动 Shizuku。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (state == VScreenBackend.State.NOT_INSTALLED) {
                    TextButton(text = "获取 Shizuku", onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    })
                } else if (state == VScreenBackend.State.NEED_PERMISSION) {
                    TextButton(text = "授权 EtaX", onClick = { VScreenBackend.requestPermission() })
                } else {
                    TextButton(text = "打开 Shizuku", onClick = { VScreenBackend.openManager(context) })
                }
                Text(if (screen.active) "副屏 ${screen.displayId} · ${screen.width} × ${screen.height}" else "当前没有连接的副屏", fontSize = 14.sp)
                TextButton(text = if (busy) "正在连接…" else "连接或恢复副屏", enabled = !busy && state == VScreenBackend.State.READY, onClick = {
                    busy = true
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { VirtualDisplayController.ensureVirtualDisplay(context) }
                        result = if (id != null) "已连接副屏 $id" else "副屏连接失败，请确认 Shizuku 已授权且仍在运行"
                        busy = false
                    }
                })
                TextButton(text = "关闭副屏", enabled = !busy && state == VScreenBackend.State.READY, onClick = {
                    busy = true
                    scope.launch {
                        val closed = withContext(Dispatchers.IO) {
                            VirtualDisplayController.release(context)
                            VScreenBackend.list().mapCatching { displays ->
                                displays.forEach { VScreenBackend.release(it.displayId).getOrThrow() }
                            }.isSuccess
                        }
                        result = if (closed) "已关闭副屏" else "未能确认副屏已关闭，请检查 Shizuku 状态"
                        busy = false
                    }
                })
                result?.let { Text(it, fontSize = 13.sp) }
            }
        }
    }
}
