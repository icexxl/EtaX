package io.github.icexxl.etax.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.StatusError
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 输入框表面：与模型对话 `AgentChatInputBar` 保持一致的 Miuix 玻璃胶囊材质、
 * 描边、阴影、圆角以及长按语音时的边框高亮动画。
 *
 * [glassBackdrop] 必须来自与输入栏平级的消息列表图层，严禁传入祖先 backdrop。
 */
@Composable
internal fun Modifier.openClawInputSurface(
    glassBackdrop: Backdrop? = null,
    isHoldingSpeech: Boolean = false,
    holdCancelling: Boolean = false,
): Modifier {
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val miuixInputShape = remember { GlassShape(24.dp) }
    val miuixInputBg = if (!isDark && MiuixTheme.colorScheme.surfaceContainer == MiuixTheme.colorScheme.surface) {
        Color(0xFFF5F6F8)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val defaultBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    val inputBorderColor by animateColorAsState(
        targetValue = when {
            isHoldingSpeech && holdCancelling -> StatusError.copy(alpha = 0.45f)
            isHoldingSpeech -> MiuixTheme.colorScheme.primary.copy(alpha = 0.38f)
            else -> defaultBorderColor
        },
        animationSpec = tween(durationMillis = 180),
        label = "openclaw_input_border",
    )
    val inputBorderWidth by animateDpAsState(
        targetValue = if (isHoldingSpeech) 1.dp else 0.5.dp,
        animationSpec = tween(durationMillis = 160),
        label = "openclaw_input_border_width",
    )

    return this.then(
        Modifier
            .glassPanel(
                backdrop = glassBackdrop,
                shape = miuixInputShape,
                style = GlassDefaults.style(isDark),
                material = GlassMaterials.puredThinGlass(isDark),
                stroke = GlassEdgeStrokes.forTheme(
                    isDark = isDark,
                    light = GlassEdgeStrokes.SmallLight,
                    dark = GlassEdgeStrokes.SmallDark,
                ),
                shadow = GlassShadows.Regular,
                shading = false,
                fallback = Modifier
                    .squircleSurface(
                        color = miuixInputBg,
                        cornerRadius = 24.dp,
                    )
                    .squircleBorder(
                        width = inputBorderWidth,
                        color = inputBorderColor,
                        cornerRadius = 24.dp,
                    ),
            )
            .then(
                if (isHoldingSpeech && glassBackdrop != null && isRuntimeShaderSupported()) {
                    Modifier.border(inputBorderWidth, inputBorderColor, miuixInputShape)
                } else {
                    Modifier
                },
            ),
    )
}
