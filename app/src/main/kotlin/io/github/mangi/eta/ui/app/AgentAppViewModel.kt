package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.icexxl.etax.host.EtaXHost

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val skills = AgentSkillsStore(application, viewModelScope)
    val memory = AgentMemoryStore(application, viewModelScope)
    val permissionHealth = PermissionHealthStore(application, viewModelScope)
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
    val openClawStore = EtaXHost.createOpenClawStore(application, viewModelScope)
    private val openClawNetworkMonitor = EtaXHost.createNetworkMonitor(application, openClawStore)

    override fun onCleared() {
        openClawNetworkMonitor.stop()
        openClawStore.stop()
        super.onCleared()
    }
}
