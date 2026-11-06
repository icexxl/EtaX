package io.github.icexxl.etax.ui.components

import android.content.SharedPreferences
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.icexxl.etax.config.Prefs
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/** 生成状态：聊天页在流式输出时置 true。 */
internal object AuroraMood {
    val thinking = mutableStateOf(false)
}

/** 空闲时输入框附近那层淡蓝。 */
private val IdleGlow = Color(0xFF15286A)

// 色相环关键帧，取自 Gemini 实拍（60fps 逐帧，顶部 12~20% 中位色）。
// 一圈约 5.2s，单向前进，经紫色回到蓝色，所以循环没有接缝。
private val LapColors = listOf(
    0.00f to Color(0xFF1D3674), // 深蓝
    0.12f to Color(0xFF163E60), // 蓝→青
    0.23f to Color(0xFF0D4B44), // 青
    0.31f to Color(0xFF0F5527), // 绿
    0.42f to Color(0xFF385B15), // 黄绿
    0.50f to Color(0xFF685A10), // 琥珀
    0.58f to Color(0xFF6A4924), // 暖橙
    0.65f to Color(0xFF5A3545), // 洋红
    0.73f to Color(0xFF492E5A), // 紫
    0.83f to Color(0xFF342F6A), // 靛
    0.92f to Color(0xFF203471), // 回到蓝
    1.00f to Color(0xFF1D3674),
)

// 完成时的收尾：蓝 → 青 → 绿 → 黄绿，约 1.4s
private val FinishColors = listOf(
    0.00f to Color(0xFF1D3674),
    0.20f to Color(0xFF143D5A),
    0.36f to Color(0xFF104F41),
    0.55f to Color(0xFF146624),
    0.80f to Color(0xFF2D7014),
    1.00f to Color(0xFF426413),
)

private const val LAP_SECONDS = 4.3f
private const val FADE_IN_SECONDS = 0.25f
private const val FINISH_SECONDS = 1.4f
private const val FADE_OUT_SECONDS = 0.6f
// 左边缘领先右边缘的时间（实测 0.7s）
private const val SWEEP_LAG = 0f

private fun ease(x: Float): Float {
    val f = x.coerceIn(0f, 1f)
    return f * f * (3f - 2f * f)
}

private fun sample(stops: List<Pair<Float, Color>>, x: Float): Color {
    val f = x.coerceIn(0f, 1f)
    for (i in 1 until stops.size) {
        val (p1, c1) = stops[i]
        if (f <= p1) {
            val (p0, c0) = stops[i - 1]
            val span = p1 - p0
            return if (span <= 0f) c1 else lerp(c0, c1, (f - p0) / span)
        }
    }
    return stops.last().second
}

/** 生成中第 t 秒的颜色：先停在蓝，然后沿色相环循环。 */
private fun streamingColor(t: Float): Color {
    if (t < 1.0f) return LapColors.first().second
    val lap = ((t - 1.0f) / LAP_SECONDS) % 1f
    return sample(LapColors, lap)
}

/**
 * 光幕着色器（AGSL）：顶部一整片弥散渐变（mesh/diffuse gradient）。
 * - 横向：9 个色标平滑插值，整片同时出现、同时变色（不再从左往右错峰淡入）
 * - 弥散：叠 3 团大而柔的光，各自缓慢漂移，相邻颜色互相晕开
 * - 纵向：顶部满亮，往下柔和淡到黑；下沿是一条轻微起伏、缓慢漂移的曲线
 * 每个像素单独计算，没有分层叠加，所以不会出现台阶或竖缝。
 */
private const val AURORA_AGSL = """
uniform float2 size;
uniform float time;
uniform half4 c0; uniform half4 c1; uniform half4 c2; uniform half4 c3;
uniform half4 c4; uniform half4 c5; uniform half4 c6; uniform half4 c7; uniform half4 c8;

half4 colorAt(float x) {
    float f = clamp(x, 0.0, 1.0) * 8.0;
    if (f < 1.0) return mix(c0, c1, f);
    if (f < 2.0) return mix(c1, c2, f - 1.0);
    if (f < 3.0) return mix(c2, c3, f - 2.0);
    if (f < 4.0) return mix(c3, c4, f - 3.0);
    if (f < 5.0) return mix(c4, c5, f - 4.0);
    if (f < 6.0) return mix(c5, c6, f - 5.0);
    if (f < 7.0) return mix(c6, c7, f - 6.0);
    return mix(c7, c8, f - 7.0);
}

half4 main(float2 fc) {
    const float PI = 3.14159265;
    float aspect = size.y / size.x;
    float X = fc.x / size.x;
    float Y = fc.y / size.x;                 // 以屏宽为单位
    float yn = fc.y / size.y;                // 占屏高

    // 下沿（占屏高）：中间略垂、两侧略收，叠两道慢速横向漂移的长波。高低差比实拍收小一些
    float edge = 0.47 + 0.07 * sin(PI * X)
        + 0.035 * sin(2.0 * PI * (X * 1.25 - time / 6.5))
        + 0.020 * sin(2.0 * PI * (X * 2.1 + time / 4.7) + 1.3);

    // 下沿附近的柔边 + 自上而下的整体衰减（按实拍剖面）
    float shape = 1.0 - smoothstep(-0.05, 0.28, Y - edge * aspect);
    float fade = 1.0 - smoothstep(0.30, 1.05, clamp(yn / max(edge, 0.08), 0.0, 1.4));

    // 弥散渐变：在横向色带上再叠 3 团很大的柔光，各自缓慢漂移。
    // 每团的颜色取自它所在位置稍偏一点的色标，所以画面里同时有相邻的几种颜色互相晕开，
    // 而不是一条从左到右的线性色带。
    half4 base = colorAt(X);
    half3 rgb = base.rgb;
    float wsum = 1.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float bx = 0.18 + 0.32 * fi + 0.10 * sin(time / (5.5 + fi * 1.3) + fi * 2.1);
        float by = (0.10 + 0.08 * sin(time / (4.2 + fi) + fi * 1.7)) * aspect;
        float2 dlt = float2(X - bx, (Y - by) / 1.4);
        float wgt = 0.85 * exp(-dot(dlt, dlt) / 0.09);
        half4 bc = colorAt(clamp(bx + (fi - 1.0) * 0.18, 0.0, 1.0));
        rgb += bc.rgb * wgt;
        wsum += wgt;
    }
    rgb /= wsum;

    float a = clamp(shape * fade * base.a, 0.0, 1.0);
    return half4(rgb * a, a);
}
"""

/**
 * 对话页背景：Gemini 式的环境色状态指示（按 60fps 实拍逐帧还原）。
 *
 * - 开始思考：黑底上 0.8s 内从顶部渐显深蓝
 * - 思考中：先停在蓝，然后沿色相环单向循环（蓝→青→绿→琥珀→洋红→紫→蓝，一圈 5.2s）
 * - 开始输出：回到蓝后快速扫过 青→绿→黄绿（1.4s），随后 0.6s 淡回黑
 *
 * 颜色变化从左往右推进（左边缘领先右边缘 0.7s），所以看起来像一道色彩横扫过去；
 * 纵向顶部最亮，到屏高约 60% 淡到黑。
 */
@Composable
internal fun AuroraBackground(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    intensity: Float = 1f,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val strength = intensity.coerceIn(0f, 2f) * if (dark) 1f else 0.4f
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

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

    val thinking by AuroraMood.thinking
    // 生成开始 / 结束的时间点（纳秒）；0 表示没有
    var startNs by remember { mutableLongStateOf(0L) }
    var finishNs by remember { mutableLongStateOf(0L) }
    var frameNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(thinking, enabled) {
        if (!enabled) {
            startNs = 0L
            finishNs = 0L
            return@LaunchedEffect
        }
        if (thinking) {
            startNs = System.nanoTime()
            finishNs = 0L
        } else if (startNs != 0L && finishNs == 0L) {
            finishNs = System.nanoTime()
            delay(((FINISH_SECONDS + FADE_OUT_SECONDS + SWEEP_LAG) * 1000).toLong() + 100)
            startNs = 0L
            finishNs = 0L
        }
    }

    // 只在动画进行中逐帧刷新；空闲时不跑帧循环
    val ticking = enabled && started && startNs != 0L
    LaunchedEffect(ticking) {
        if (!ticking) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNs = it }
        }
    }

    // AGSL 着色器只创建一次；编译失败时退回不画光幕（不会崩）
    val auroraShader = remember {
        runCatching { android.graphics.RuntimeShader(AURORA_AGSL) }
            .onFailure { android.util.Log.e("Aurora", "shader compile failed", it) }
            .getOrNull()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val background = if (dark) Color.Black else scheme.surface
        drawRect(color = background)

        @Suppress("UNUSED_VARIABLE")
        val tick = frameNs // 订阅帧刷新

        val now = System.nanoTime()
        val active = startNs != 0L
        val t = if (active) (now - startNs) / 1_000_000_000f else 0f
        val sinceFinish = if (finishNs != 0L) (now - finishNs) / 1_000_000_000f else -1f

        /**
         * 某一列在"列时间" tt 下的颜色与强度。
         * tt = 全局时间 - 该列的滞后（左边 0、右边 SWEEP_LAG）。
         */
        fun columnState(lag: Float): Pair<Color, Float> {
            val tt = t - lag
            if (tt <= 0f) return Color(0xFF1D3674) to 0f
            val fadeIn = (tt / FADE_IN_SECONDS).coerceIn(0f, 1f)
            if (sinceFinish < 0f) {
                return streamingColor(tt) to fadeIn
            }
            val f = sinceFinish - lag
            // 结束前的颜色（停在被打断的位置）
            val frozenT = ((finishNs - startNs) / 1_000_000_000f - lag).coerceAtLeast(0f)
            val frozen = streamingColor(frozenT)
            return when {
                f <= 0f -> frozen to fadeIn
                f < 0.3f -> lerp(frozen, FinishColors.first().second, f / 0.3f) to fadeIn
                f < FINISH_SECONDS -> sample(FinishColors, (f - 0.3f) / (FINISH_SECONDS - 0.3f)) to fadeIn
                f < FINISH_SECONDS + FADE_OUT_SECONDS -> {
                    val k = 1f - (f - FINISH_SECONDS) / FADE_OUT_SECONDS
                    FinishColors.last().second to fadeIn * k * k
                }
                else -> FinishColors.last().second to 0f
            }
        }

        // 空闲：输入框附近一层淡蓝（随键盘上移）；有动画时让位
        val idleWeight = if (active) {
            val (_, env) = columnState(SWEEP_LAG * 0.5f)
            1f - env
        } else {
            1f
        }
        val idleAlpha = idleWeight * strength
        if (idleAlpha > 0.01f) {
            val anchorY = h * 1.04f - imeBottomPx
            val radius = h * 0.62f
            listOf(
                Offset(w * 0.5f, anchorY) to 1f,
                Offset(w * 0.24f, anchorY + h * 0.02f) to 0.55f,
                Offset(w * 0.78f, anchorY + h * 0.03f) to 0.55f,
            ).forEach { (center, weight) ->
                val a = 0.62f * idleAlpha * weight
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to IdleGlow.copy(alpha = a),
                            0.4f to IdleGlow.copy(alpha = a * 0.4f),
                            0.75f to IdleGlow.copy(alpha = a * 0.08f),
                            1f to Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
        if (!active) return@Canvas

        // 1) 横向：9 个色标，从左到右依次滞后，形成"一道颜色横扫过去"
        val stops = 9
        val topColors = (0 until stops).map { i ->
            val x = i / (stops - 1f)
            val (c, env) = columnState(x * SWEEP_LAG)
            c.copy(alpha = (env * strength).coerceIn(0f, 1f))
        }
        if (topColors.all { it.alpha <= 0.002f }) return@Canvas

        // 2) 交给着色器：颜色（含每个色标自己的淡入淡出）+ 时间（驱动下沿缓慢漂移）
        val shader = auroraShader ?: return@Canvas
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("time", t)
        topColors.forEachIndexed { i, c ->
            shader.setFloatUniform("c$i", c.red, c.green, c.blue, c.alpha)
        }
        drawRect(brush = ShaderBrush(shader), size = Size(w, h))
    }
}

/**
 * 输入框边框动画的进度（0→1 一圈）。null 表示不画。
 * [marquee] 为 true 时 3.2s 一圈（跑马灯）；false 时 4.2s 一圈（幻彩，与屏幕边缘光同速）。
 */
@Composable
internal fun rememberInputPulseProgress(active: Boolean, marquee: Boolean = true): State<Float>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "input_border")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (marquee) 3200 else 4200, easing = LinearEasing),
        ),
        label = "input_border_t",
    )
}

// 输入框幻彩边框：与"操作手机时屏幕边缘光"同一套柔和冷暖色，首尾同色
private val EdgeLightColors = listOf(
    Color(0xFF7AB8FF),
    Color(0xFFB59CFF),
    Color(0xFFFF9EC7),
    Color(0xFFFFC98A),
    Color(0xFF7AB8FF),
)

// 跑马灯配色：和背景色相环同一套颜色，首尾同色，旋转一圈没有接缝
private val MarqueeColors = listOf(
    Color(0xFF4F7DF0), // 蓝
    Color(0xFF2FC8C0), // 青
    Color(0xFF5FD068), // 绿
    Color(0xFFE6B832), // 琥珀
    Color(0xFFC86AD8), // 紫
    Color(0xFF4F7DF0),
)

private fun marqueeColorAt(pos: Float): Color {
    val f = ((pos % 1f) + 1f) % 1f
    val x = f * (MarqueeColors.size - 1)
    val i = x.toInt().coerceIn(0, MarqueeColors.size - 2)
    return lerp(MarqueeColors[i], MarqueeColors[i + 1], x - i)
}

/**
 * 输入框边框。
 * - [iridescent] = false：跑马灯，一道彩色亮段沿边框绕行，头亮尾淡
 * - [iridescent] = true：整圈都有颜色，色带沿边框缓慢流动（幻彩），亮度恒定、不闪烁
 * progress 为 null 时什么都不画。
 */
internal fun Modifier.inputPulseBorder(
    progress: State<Float>?,
    cornerRadius: Dp = 20.dp,
    iridescent: Boolean = false,
): Modifier = drawWithContent {
    drawContent()
    val value = progress?.value ?: return@drawWithContent
    val stroke = 1.8.dp.toPx()
    val inset = stroke / 2f
    val rect = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
    val radius = cornerRadius.toPx().coerceAtMost(rect.height / 2f)
    val path = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(radius)))
    }
    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    if (length <= 0f) return@drawWithContent

    if (iridescent) {
        // 和"操作手机时屏幕一圈的光带"同一种画法：
        // 一个绕卡片中心旋转的扫描渐变，画两遍——外层宽、模糊的柔光 + 内层细亮线。
        // 颜色取那套偏柔和的冷暖色（蓝→紫→粉→杏→蓝），首尾同色，旋转一圈没有接缝。
        val cx = size.width / 2f
        val cy = size.height / 2f
        val sweep = android.graphics.SweepGradient(
            cx, cy, EdgeLightColors.map { it.toArgb() }.toIntArray(), null,
        ).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(value * 360f, cx, cy) })
        }
        val outline = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(inset, inset, size.width - inset, size.height - inset),
                radius, radius, android.graphics.Path.Direction.CW,
            )
        }
        drawIntoCanvas { canvas ->
            val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 10.dp.toPx()
                maskFilter = android.graphics.BlurMaskFilter(8.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                shader = sweep
                alpha = (0.60f * 255).toInt()
            }
            val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.2.dp.toPx()
                shader = sweep
                alpha = 255
            }
            canvas.nativeCanvas.drawPath(outline, halo)
            canvas.nativeCanvas.drawPath(outline, line)
        }
        return@drawWithContent
    }

    // 跑马灯：底线 + 一道亮段
    drawPath(path, color = Color.White.copy(alpha = 0.06f), style = Stroke(width = stroke))
    val segment = length * 0.34f
    val head = value * length
    val pieces = 48
    val step = segment / pieces
    for (i in 0 until pieces) {
        val fromRaw = head - segment + i * step
        val from = ((fromRaw % length) + length) % length
        val to = from + step + 0.6f
        val piece = androidx.compose.ui.graphics.Path()
        if (to <= length) {
            measure.getSegment(from, to, piece, true)
        } else {
            measure.getSegment(from, length, piece, true)
            measure.getSegment(0f, to - length, piece, true)
        }
        val k = (i + 1f) / pieces            // 0 → 1：尾 → 头
        val color = marqueeColorAt(from / length)
        drawPath(
            piece,
            color = color.copy(alpha = k * k * 0.95f),
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** 跟随设置里的「动态背景」开关，切换后立即生效。 */
@Composable
internal fun rememberAuroraEnabled(): Boolean {
    val prefs = remember { Prefs.localAgentPreferences() }
    var enabled by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(Prefs.Keys.CHAT_AURORA_BACKGROUND, true) ?: true)
    }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.CHAT_AURORA_BACKGROUND) enabled = changed.getBoolean(key, true)
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
