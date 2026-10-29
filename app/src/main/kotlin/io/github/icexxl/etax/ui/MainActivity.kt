package io.github.icexxl.etax.ui

import io.github.icexxl.etax.ui.voice.ACTION_SPEECH_SETTINGS
import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.icexxl.etax.agent.voice.EtaAssistantOverlayService
import io.github.icexxl.etax.data.model.AppearanceThemeMode
import io.github.icexxl.etax.data.repository.AppearanceSettingsRepository
import io.github.icexxl.etax.ui.app.AgentAppRoot
import io.github.icexxl.etax.ui.app.ArchivedConversationRequest
import io.github.icexxl.etax.ui.app.AgentAppTheme
import io.github.icexxl.etax.ui.app.PredictiveBackController
import io.github.icexxl.etax.ui.app.installStartupSplash
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var requestedConversationId by mutableStateOf<String?>(null)
    private var requestedArchivedConversation by mutableStateOf<ArchivedConversationRequest?>(null)
    private var requestedOpenClawSession by mutableStateOf<String?>(null)
    private var appliedPredictiveBackEnabled = true
    private var speechSettingsRequested by mutableStateOf(false)
    private var terminalRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = assistantConversationKey,
                        requestedConversationId = requestedConversationId,
                        onRequestedConversationOpened = { requestedConversationId = null; intent?.action = null },
                        requestedOpenClawSession = requestedOpenClawSession,
                        onRequestedOpenClawSessionOpened = {
                            requestedOpenClawSession = null
                            intent?.removeExtra(EXTRA_OPENCLAW_SESSION)
                        },
                        requestedArchivedConversation = requestedArchivedConversation,
                        onArchivedConversationOpened = { requestedArchivedConversation = null; intent?.action = null },
                        openSpeechSettings = speechSettingsRequested,
                        openTerminal = terminalRequested,
                        onTerminalOpened = { terminalRequested = false; intent?.action = null },
                        onSpeechSettingsOpened = { speechSettingsRequested = false; intent?.action = null },
                        onAssistantConversationOpened = { opened ->
                            assistantConversationKey = null
                            if (opened) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                    )
                }
            }
            contentReady = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        val openClawSession = intent?.getStringExtra(EXTRA_OPENCLAW_SESSION)?.takeIf(String::isNotBlank)
        if (openClawSession != null) {
            requestedOpenClawSession = openClawSession
            return
        }
        if (intent?.action == ACTION_OPEN_TERMINAL) {
            terminalRequested = true
            return
        }
        if (intent?.action == ACTION_SPEECH_SETTINGS) {
            speechSettingsRequested = true
            return
        }
        if (intent?.action == ACTION_OPEN_CONVERSATION_ID) {
            requestedConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            return
        }
        if (intent?.action == ACTION_OPEN_ARCHIVED_CONVERSATION) {
            val source = intent.getStringExtra(EXTRA_HANDOFF_SOURCE)?.takeIf(String::isNotBlank)
            val key = intent.getStringExtra(EXTRA_CONVERSATION_KEY)?.takeIf(String::isNotBlank)
            requestedArchivedConversation = if (source != null && key != null) {
                ArchivedConversationRequest(source, key)
            } else {
                null
            }
            return
        }
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_OPEN_TERMINAL = "io.github.icexxl.etax.action.OPEN_TERMINAL"
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.icexxl.etax.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.icexxl.etax.extra.CONVERSATION_ID"
        /** 系统助手等外部入口发起的 run 按来源与会话键归档，回到本体时打开对应的归档会话。 */
        const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.icexxl.etax.action.OPEN_ARCHIVED_CONVERSATION"
        const val EXTRA_HANDOFF_SOURCE = "io.github.icexxl.etax.extra.HANDOFF_SOURCE"
        const val EXTRA_CONVERSATION_KEY = "io.github.icexxl.etax.extra.CONVERSATION_KEY"
        const val EXTRA_OPENCLAW_SESSION = "io.github.icexxl.etax.extra.OPENCLAW_SESSION"
        const val EXTRA_OPENCLAW_AGENT = "io.github.icexxl.etax.extra.OPENCLAW_AGENT"
    }
}
