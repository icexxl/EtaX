package io.github.icexxl.etax.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import io.github.icexxl.etax.R
import io.github.icexxl.etax.data.model.TtsProvider
import io.github.icexxl.etax.data.repository.SpeechSettingsRepository
import io.github.icexxl.etax.ui.components.EtaArrowPreference
import io.github.icexxl.etax.ui.components.EtaPreferenceColors
import io.github.icexxl.etax.ui.components.EtaPreferenceDivider
import io.github.icexxl.etax.ui.components.EtaPreferenceGroup
import io.github.icexxl.etax.ui.components.EtaPreferenceGroupTitle
import io.github.icexxl.etax.ui.components.EtaPreferenceIcon
import io.github.icexxl.etax.ui.components.EtaSwitchPreference
import io.github.icexxl.etax.ui.components.MiuixScaffoldPage
import io.github.icexxl.etax.ui.navigation.AppRoute
import kotlinx.coroutines.launch

/**
 * 语音设置总览：识别与播报各有独立子页面（预测性返回与滑动返回由 miuix-nav 提供），
 * 这里的开关直接读写持久化配置，不涉及凭据草稿。
 */
@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsState(initial = null)
    val current = settings

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_settings_title),
        onBack = onBack,
    ) {
        if (current == null) {
            item(key = "loading") { SpeechLoadingState(failure = null) }
            return@MiuixScaffoldPage
        }
        item(key = "service_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_group_service))
        }
        item(key = "service") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.speech_recognition_title),
                    summary = current.asr.label(),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Mic, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { onNavigate(AppRoute.SpeechRecognition) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.speech_synthesis_title),
                    summary = current.tts.label(),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            tint = EtaPreferenceColors.Orange,
                        )
                    },
                    onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                )
            }
        }
        item(key = "assistant_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_group_assistant))
        }
        item(key = "assistant") {
            Column {
                EtaPreferenceGroup {
                    EtaSwitchPreference(
                        title = stringResource(R.string.speech_auto_speak),
                        summary = stringResource(R.string.speech_auto_speak_summary),
                        checked = current.autoSpeak,
                        enabled = current.tts != TtsProvider.NONE,
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.RecordVoiceOver,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SpeechSettingsRepository.save(current.copy(autoSpeak = enabled))
                            }
                        },
                    )
                }
                SpeechNote(stringResource(R.string.speech_overview_note))
            }
        }
    }
}
