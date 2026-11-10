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

// 保留原有深色配色；首尾同色，单向循环没有接缝。
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

// 录屏实测：色相每 8 秒走完一圈，每圈按 S 曲线缓动（首尾慢、中段快）。
private const val LAP_SECONDS = 8f
private const val FADE_IN_SECONDS = 0.25f
// 录屏实测：同一时刻左右色相差在缓动中段约 18–26°，到底部平台段收敛到 1–7°。
// 这说明各列走的是同一条 S 曲线、只是起点依次延迟，而不是固定的空间坡道。
private const val COLUMN_LAG_SECONDS = 0.4f

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

/**
 * 单圈缓动：把 0→1 的线性进度压成 S 曲线（smoothstep），首尾速度为 0、中点最快。
 * 每一列都用这条曲线，只是起始时间不同，于是形成一道向前推进的色带。
 */
internal fun lapEase(seconds: Float): Float {
    val f = ((seconds.coerceAtLeast(0f) / LAP_SECONDS) % 1f)
    return f * f * (3f - 2f * f)
}

/** 某一列在屏幕横坐标 x∈[0,1] 上的缓动相位；右侧比左侧晚 [COLUMN_LAG_SECONDS] 起步。 */
internal fun columnLapEase(t: Float, x: Float): Float =
    lapEase((t - COLUMN_LAG_SECONDS * x.coerceIn(0f, 1f)).coerceAtLeast(0f))

/** 归一化相位对应的配色。 */
private fun paletteAt(phase: Float): Color = sample(LapColors, ((phase % 1f) + 1f) % 1f)

/**
 * 光幕着色器（AGSL）：顶部一整片弥散渐变（mesh/diffuse gradient）。
 * - 横向：9 个色标平滑插值，颜色由左向右依次过渡
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
        + 0.035 * sin(2.0 * PI * (X * 1.25 - time / 8.0))
        + 0.020 * sin(2.0 * PI * (X * 2.1 + time / 6.0) + 1.3);

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
 * 对话页背景：原有深色光幕配色，颜色由左向右单向推进。
 *
 * - 开始思考：黑底上 0.8s 内从顶部渐显深蓝
 * - 思考中：先停在蓝，然后沿色相环单向循环（蓝→青→绿→琥珀→洋红→紫→蓝，一圈 4.3s）
 * - 开始输出：立即停止顶部光幕，恢复闲置背景，不播放收尾色彩
 *
 * 颜色变化从左往右推进（左边缘领先右边缘 1.2s），所以看起来像一道色彩横扫过去；
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
    // 生成开始时间（纳秒）；0 表示没有
    var startNs by remember { mutableLongStateOf(0L) }
    var frameNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(thinking, enabled) {
        if (!enabled) {
            startNs = 0L
            return@LaunchedEffect
        }
        if (thinking) {
            startNs = System.nanoTime()
        } else {
            // Stop all columns together; color phase never controls visibility.
            startNs = 0L
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

        // 全宽一起显隐；颜色按各列自己的 S 曲线进度取样，形成横扫的色带。
        val envelope = (t / FADE_IN_SECONDS).coerceIn(0f, 1f)
        fun columnColor(x: Float): Color = paletteAt(columnLapEase(t, x))

        // 空闲：输入框附近一层淡蓝（随键盘上移）；有动画时让位
        val idleWeight = if (active) 1f - envelope else 1f
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

        // 横向 9 个色标取自同一条坡道；缓动旋转时等色线自然横扫。
        val stops = 9
        val topColors = (0 until stops).map { i ->
            val x = i / (stops - 1f)
            columnColor(x).copy(alpha = (envelope * strength).coerceIn(0f, 1f))
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
