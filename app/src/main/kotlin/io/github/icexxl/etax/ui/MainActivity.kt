package io.github.icexxl.etax.ui

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
import io.github.icexxl.etax.agent.runtime.AgentRuntimeService
import io.github.icexxl.etax.agent.voice.EtaAssistantOverlayService
import io.github.icexxl.etax.core.AndroidAgentLogger
import io.github.icexxl.etax.data.model.AppearanceThemeMode
import io.github.icexxl.etax.data.repository.AppearanceSettingsRepository
import io.github.icexxl.etax.ui.app.AgentAppRoot
import io.github.icexxl.etax.ui.app.AgentAppTheme
import io.github.icexxl.etax.ui.app.PredictiveBackController
import io.github.icexxl.etax.ui.app.installStartupSplash
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchRequests by mutableStateOf(MainActivityLaunchRequests())
    // 通知栏点进来的 OpenClaw 会话（一次性消费）
    private var requestedOpenClawSession by mutableStateOf<String?>(null)
    private var appliedPredictiveBackEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAgentLogger.info("Agent UI created: task=$taskId restored=${savedInstanceState != null}")
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        launchRequests = MainActivityLaunchRequests.restore(savedInstanceState, intent)
        requestedOpenClawSession = intent
            ?.getStringExtra(EXTRA_OPENCLAW_SESSION)
            ?.takeIf(String::isNotBlank)
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
                        assistantConversationKey = launchRequests.assistantConversationKey,
                        requestedConversationId = launchRequests.conversationId,
                        onRequestedConversationOpened = {
                            launchRequests = launchRequests.copy(conversationId = null)
                            intent?.action = null
                        },
                        requestedArchivedConversation = launchRequests.archivedConversation,
                        onArchivedConversationOpened = {
                            launchRequests = launchRequests.copy(archivedConversation = null)
                            intent?.action = null
                        },
                        requestedOpenClawSession = requestedOpenClawSession,
                        onRequestedOpenClawSessionOpened = {
                            requestedOpenClawSession = null
                            intent?.removeExtra(EXTRA_OPENCLAW_SESSION)
                        },
                        sharedContent = launchRequests.sharedContent,
                        onSharedContentAccepted = {
                            launchRequests = launchRequests.copy(sharedContent = null)
                            intent?.action = null
                        },
                        openSpeechSettings = launchRequests.speechSettings,
                        openTerminal = launchRequests.terminal,
                        onTerminalOpened = {
                            launchRequests = launchRequests.copy(terminal = false)
                            intent?.action = null
                        },
                        onSpeechSettingsOpened = {
                            launchRequests = launchRequests.copy(speechSettings = false)
                            intent?.action = null
                        },
                        onAssistantConversationOpened = { opened ->
                            launchRequests = launchRequests.copy(assistantConversationKey = null)
                            intent?.action = null
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

    override fun onStart() {
        super.onStart()
        startedInstances.add(this)
        isInForeground = true
        io.github.icexxl.etax.agent.runtime.AgentReplyNotification.clear(this)
        AgentRuntimeService.onEtaUiVisibilityChanged()
    }

    override fun onStop() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AgentRuntimeService.onEtaUiVisibilityChanged()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        AndroidAgentLogger.info("Agent UI new intent: task=$taskId launcher=$fromLauncher")
        setIntent(intent)
        launchRequests = launchRequests.accept(intent)
        requestedOpenClawSession = intent
            .getStringExtra(EXTRA_OPENCLAW_SESSION)
            ?.takeIf(String::isNotBlank)
    }

    override fun onDestroy() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AndroidAgentLogger.info(
            "Agent UI destroyed: task=$taskId changing_configuration=$isChangingConfigurations finishing=$isFinishing",
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchRequests.saveTo(outState)
        super.onSaveInstanceState(outState)
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
        private val startedInstances = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())

        @Volatile
        internal var isInForeground: Boolean = false
            private set

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
