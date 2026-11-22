package io.github.icexxl.etax.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.icexxl.etax.data.model.AppearanceSettings
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import io.github.icexxl.etax.ui.components.EtaTopLayerState
import top.yukonga.miuix.kmp.blur.Backdrop

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalUiStyle = staticCompositionLocalOf { AppearanceUiStyle.Default }
internal val LocalGlassSceneBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalGlassPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalEtaTopLayer = staticCompositionLocalOf<EtaTopLayerState?> { null }
