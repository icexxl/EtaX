package io.github.icexxl.etax.ui.components

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.icexxl.etax.config.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话页背景：几团极低饱和度的色斑在缓慢漂移，模仿 Gemini 的「aurora」观感。
 *
 * 设计约束：
 * - 只做**装饰**，不参与可读性：暗色主题 30%、亮色 16% 的不透明度上限；
 * - 动画由单个帧时钟驱动，只让 Canvas 重绘，不触发 Recomposition；
 * - 页面不可见（Lifecycle < STARTED）或系统关闭动画时停在静止帧，不占 CPU。
 */
@Composable
internal fun AuroraBackground(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    intensity: Float = 1f,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val primary = scheme.primary
    // 从主题主色派生，换主题时自动跟随；两个固定锚点色负责冷暖对比。
    val warm = lerp(primary, Color(0xFFD96570), 0.55f)
    val cool = lerp(primary, Color(0xFF4285F4), 0.55f)
    val deep = lerp(primary, Color(0xFF9B72CB), 0.6f)
    val baseAlpha = (if (dark) 0.40f else 0.28f) * intensity.coerceIn(0f, 2f)

    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val animate = enabled && started
    // 0..1 的相位，约 24 秒一轮；只在帧时钟里推进。
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNs == 0L) lastNs = now
                val dt = (now - lastNs) / 1_000_000_000f
                if (dt >= 1f / 30f) {
                    phase = (phase + dt / 24f) % 1f
                    lastNs = now
                }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val radius = size.minDimension * 0.85f
        fun angle(offset: Float) = phase * 2f * PI.toFloat() + offset
        // 三团色斑各走不同相位的椭圆轨迹，避免同步漂移带来的机械感。
        val spots = listOf(
            Triple(
                Offset(
                    w * (0.26f + 0.16f * sin(angle(0f))),
                    h * (0.22f + 0.13f * cos(angle(0f))),
                ),
                cool,
                baseAlpha,
            ),
            Triple(
                Offset(
                    w * (0.76f + 0.14f * cos(angle(1.9f))),
                    h * (0.56f + 0.16f * sin(angle(1.9f))),
                ),
                deep,
                baseAlpha * 0.9f,
            ),
            Triple(
                Offset(
                    w * (0.50f + 0.18f * sin(angle(3.7f) * 0.8f)),
                    h * (0.86f + 0.10f * cos(angle(3.7f))),
                ),
                warm,
                baseAlpha * 0.65f,
            ),
        )
        spots.forEachIndexed { index, (center, color, alpha) ->
            // 半径也随时间轻微呼吸，避免只看到"整片色块平移"而察觉不到变化
            val breathe = 0.9f + 0.12f * sin(angle(index * 2.1f) * 1.3f + index)
            val spotRadius = radius * breathe
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = spotRadius,
                ),
                radius = spotRadius,
                center = center,
            )
        }
    }
}

/** 跟随设置里的「动态背景」开关，切换后立即生效（不需要重启页面）。 */
@Composable
internal fun rememberAuroraEnabled(): Boolean {
    val prefs = remember { Prefs.localAgentPreferences() }
    var enabled by remember(prefs) {
        mutableStateOf(
            prefs?.getBoolean(Prefs.Keys.CHAT_AURORA_BACKGROUND, true) ?: true,
        )
    }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.CHAT_AURORA_BACKGROUND) {
                enabled = changed.getBoolean(key, true)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
