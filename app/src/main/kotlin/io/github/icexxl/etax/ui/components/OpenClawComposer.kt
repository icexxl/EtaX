package io.github.icexxl.etax.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.input.TextFieldState
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import io.github.icexxl.etax.ui.app.LocalUiStyle
import io.github.suqi8.coui.kmp.squircle.SquircleShape as CouiShape
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OpenClaw 输入框表面：与主对话输入框同一套 COUI / Miuix 背景、描边、阴影与圆角。
 *
 * 主对话的 composer 在上游重构为玻璃材质 + 模糊背景，依赖 dialog 级 backdrop；
 * OpenClaw 输入区是常驻浮动条，没有可复用的 backdrop，因此这里保留简化的实底方案，
 * 但颜色、圆角、描边值与主对话保持一致，视觉上仍属同一套设计。
 */
@Composable
internal fun Modifier.openClawInputSurface(): Modifier {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = MiuixTheme.colorScheme.background.luminanceBelowHalf()
    val couiInputShape = remember { CouiShape(24.dp, 2.3f) }
    return this.then(
        if (isCoui) {
            Modifier
                .dropShadow(
                    shape = couiInputShape,
                    shadow = Shadow(
                        radius = 16.dp,
                        color = Color.Black,
                        alpha = if (couiDark) 0.22f else 0.045f,
                    ),
                )
                .clip(couiInputShape)
                .background(MiuixTheme.colorScheme.surface, couiInputShape)
                .border(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = couiInputShape,
                )
        } else {
            Modifier
                .dropShadow(
                    shape = GlassShapeCompat,
                    shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    cornerRadius = 24.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                    cornerRadius = 24.dp,
                )
        },
    )
}

private val GlassShapeCompat = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)

private fun Color.luminanceBelowHalf(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * 输入框单行/多行展开状态：文字换行后展开，清空或收回到半宽以内时收回。
 * 与主对话输入框同一套阈值，OpenClaw 通过 [rememberChatInputExpansion] 复用。
 */
internal class ChatInputExpansionState internal constructor(
    private val state: TextFieldState,
) {
    private var textLineCount by mutableIntStateOf(1)

    val expanded: Boolean
        get() = textLineCount > 1

    var textLayout: TextLayoutResult? = null
        set(value) {
            field = value
            val layout = value ?: return
            val lines = layout.lineCount.coerceAtLeast(1)
            if (!expanded) {
                if (lines > 1) textLineCount = lines
            } else {
                val shortEnough = lines == 1 &&
                    layout.getLineRight(0) < layout.size.width * 0.55f
                if (state.text.isEmpty() || shortEnough) {
                    textLineCount = 1
                } else if (lines > 1 && lines != textLineCount) {
                    textLineCount = lines
                }
            }
        }
}

@Composable
internal fun rememberChatInputExpansion(state: TextFieldState): ChatInputExpansionState =
    remember(state) { ChatInputExpansionState(state) }

/**
 * 同一棵布局树里摆输入框与两侧操作：
 * 单行 [左按钮][输入框][右按钮]；展开后输入框独占整行，按钮落到下面一排。
 * 切换时不重建输入框，避免丢失焦点。
 */
@Composable
internal fun ChatInputLayout(
    expanded: Boolean,
    expansion: ChatInputExpansionState,
    leading: @Composable () -> Unit,
    editor: @Composable (Modifier) -> Unit,
    trailing: @Composable () -> Unit,
) {
    // expansion 由调用方共享展开判定；布局本身只需最终 expanded。
    @Suppress("UNUSED_PARAMETER")
    val unusedExpansion = expansion
    androidx.compose.ui.layout.Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) { leading() }
            editor(Modifier)
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables[0].measure(loose)
        val trail = measurables[2].measure(loose)
        val full = constraints.maxWidth
        if (!expanded) {
            val textWidth = (full - lead.width - trail.width).coerceAtLeast(0)
            val text = measurables[1].measure(
                loose.copy(minWidth = textWidth, maxWidth = textWidth),
            )
            val height = maxOf(lead.height, text.height, trail.height)
            layout(full, height) {
                lead.placeRelative(0, (height - lead.height) / 2)
                text.placeRelative(lead.width, (height - text.height) / 2)
                trail.placeRelative(full - trail.width, (height - trail.height) / 2)
            }
        } else {
            val text = measurables[1].measure(loose.copy(minWidth = full, maxWidth = full))
            val rowHeight = maxOf(lead.height, trail.height)
            val height = text.height + rowHeight
            layout(full, height) {
                text.placeRelative(0, 0)
                lead.placeRelative(0, text.height + (rowHeight - lead.height) / 2)
                trail.placeRelative(full - trail.width, text.height + (rowHeight - trail.height) / 2)
            }
        }
    }
}
