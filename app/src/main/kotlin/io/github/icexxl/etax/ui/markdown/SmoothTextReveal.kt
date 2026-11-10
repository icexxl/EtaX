package io.github.icexxl.etax.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * 一条回答只使用一个显现时钟，按源码顺序在 Markdown 块之间分配同一帧的推进量。
 *
 * 解析和文本排版仅在目标文本变化时发生；帧间推进只更新普通字段并调用
 * [invalidateDraw]。只有显现跨入新行时才额外请求一次测量以增长消息高度，
 * 全程不写 Compose State，因此字符帧不会触发重组或重新排版。
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    // 列表 marker 在绘制阶段读取它；用快照状态而非 Flow，块开始显现只让 marker 图层重绘，
    // 不会让整个列表随之重组。
    private val startedState = mutableStateOf<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false

    val drained: StateFlow<Boolean> = drainedState
    /** 已经开始显现的块，用于让列表 marker 与正文保持同一生命周期。 */
    val started: State<Set<RevealBlockKey>> get() = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    /**
     * 页面不可见时帧时钟会停，但 Runtime 仍可能继续追加文本。此时直接追平当前目标，
     * 并让后续排版结果同样立即完成，避免回到页面后补播后台积压的显现动画。
     */
    fun pauseAnimationsAndCatchUp() {
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.hasPendingReveal
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.hasPendingReveal }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // 已离开组合的块不再消费帧时钟；保留完成进度，重挂载时只显现后续新增文本。
        completeRecord(record)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        if (node != null) record.node = node
        updateRecord(record, text, layoutResult)
        wakeups.trySend(Unit)
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            val active = firstPendingRecord()
            if (active == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            var previousFrameNanos = withFrameNanos { it }
            while (currentCoroutineContext().isActive) {
                if (firstPendingRecord() == null) break
                val frameNanos = withFrameNanos { it }
                val elapsedSeconds = ((frameNanos - previousFrameNanos) / NANOS_PER_SECOND)
                    .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
                previousFrameNanos = frameNanos

                val totalBacklog = records.values.sumOf { candidate ->
                    max(0.0, (candidate.targetCount - candidate.progress).toDouble())
                }.toFloat()
                var remainingAdvance = advanceSmoothReveal(
                    current = 0f,
                    target = totalBacklog,
                    elapsedSeconds = elapsedSeconds,
                    totalBacklog = totalBacklog,
                )
                // Fade age is wall-clock based, independent of adaptive head speed. Multiple
                // admitted groups therefore overlap, including across Markdown block boundaries.
                val changedRecords = mutableSetOf<RevealRecord>()
                records.values.forEach { record ->
                    if (record.fadingGroups.isNotEmpty()) changedRecords += record
                    record.fadingGroups.forEach { it.ageSeconds += elapsedSeconds }
                }
                // Keep a large catch-up burst from allocating a layer per three glyphs.
                // Normal output retains three-grapheme groups and an ~11-grapheme trail.
                val groupSize = max(
                    REVEAL_GROUP_SIZE,
                    ceil(smoothRevealSpeed(totalBacklog) * REVEAL_FADE_SECONDS / 24f).toInt(),
                )
                val newlyStarted = mutableSetOf<RevealBlockKey>()
                // 块结束后剩余的推进量继续用于下一块，避免短段落把追赶速度限制为每帧一块。
                for (record in records.values) {
                    if (remainingAdvance <= 0f) break
                    if (record.node == null || record.layoutResult == null) continue
                    val pending = record.targetCount - record.progress
                    if (pending <= 0f) continue
                    val advance = remainingAdvance.coerceAtMost(pending)
                    val previousProgress = record.progress
                    record.progress = (record.progress + advance).coerceAtMost(record.targetCount)
                    while (record.admittedCount < record.targetCount && record.admittedCount < record.progress) {
                        val start = record.admittedCount
                        val end = (start + groupSize).coerceAtMost(record.targetCount.toInt())
                        // Interpolate admission within this frame rather than flashing every new
                        // group at the same opacity after a large catch-up step.
                        val fraction = ((start - previousProgress) / advance).coerceIn(0f, 1f)
                        record.fadingGroups += RevealFadeGroup(start, end, elapsedSeconds * (1f - fraction))
                        record.admittedCount = end
                    }
                    remainingAdvance -= advance
                    changedRecords += record
                    if (record.key !in startedState.value) newlyStarted += record.key
                }
                changedRecords.forEach { record ->
                    while (record.fadingGroups.firstOrNull()?.alpha == 1f) {
                        record.opaqueCount = record.fadingGroups.removeAt(0).end
                    }
                    if (record.hasPendingReveal || record.progress > 0f) record.node?.onRevealDataChanged()
                }
                if (newlyStarted.isNotEmpty()) startedState.value = startedState.value + newlyStarted
                updateDrainedState()
            }
        }
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (text != record.text) {
            // 流式文本只追加不修改，但行内语法闭合（**粗体**、`code`、链接折叠等）会让
            // 渲染文本丢掉标记字符而变短或错位。此时进度只能保持单调前进：一旦回退，
            // 已显现的文字会消失并重新打字，表现为输出反复闪烁。
            record.boundaries = updateGraphemeBoundaries(
                previousText = record.text,
                previousBoundaries = record.boundaries,
                text = text,
            )
            record.text = text
            record.targetCount = record.boundaries.lastIndex.toFloat()
            record.progress = record.progress.coerceAtMost(record.targetCount)
            val count = record.targetCount.toInt()
            record.opaqueCount = record.opaqueCount.coerceAtMost(count)
            record.admittedCount = record.admittedCount.coerceAtMost(count)
            record.fadingGroups.removeAll { it.start >= count }
            record.fadingGroups.forEach { it.end = it.end.coerceAtMost(count) }
        }
        if (record.layoutResult !== layoutResult) {
            record.layoutResult = layoutResult
        }
        if (animationsPaused || record.node == null) completeRecord(record)
        updateDrainedState()
        record.node?.onRevealDataChanged()
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        record.opaqueCount = record.targetCount.toInt()
        record.admittedCount = record.opaqueCount
        record.fadingGroups.clear()
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        record.hasPendingReveal && record.node != null && record.layoutResult != null
    }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record -> record.hasPendingReveal }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private val alphaPaint = Paint()
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedFullCount = -1
    private var cachedFullPath: Path? = null
    private val cachedFadePaths = mutableMapOf<Pair<Int, Int>, Path>()
    private var cachedVisibleHeight = -1

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        state.detach(this)
        clearPathCache()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) state.attach(this)
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) invalidateMeasurement()
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val visibleHeight = state.visibleHeightPx().coerceAtMost(placeable.height)
        cachedVisibleHeight = visibleHeight
        val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, measuredHeight) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val snapshot = state.drawSnapshot() ?: return
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        if (targetCount <= 0 || snapshot.opaqueCount >= targetCount) {
            drawContent()
            return
        }

        ensurePaths(snapshot)
        cachedFullPath?.let { path ->
            clipPath(path) { contentScope.drawContent() }
        }
        for (group in snapshot.fadingGroups) {
            if (group.alpha <= 0f) continue
            val path = cachedFadePaths[group.start to group.end] ?: continue
            clipPath(path) {
                alphaPaint.alpha = group.alpha
                drawContext.canvas.saveLayer(path.getBounds(), alphaPaint)
                try {
                    contentScope.drawContent()
                } finally {
                    drawContext.canvas.restore()
                }
            }
        }
    }

    private fun ensurePaths(snapshot: RevealDrawSnapshot) {
        if (cachedLayoutResult !== snapshot.layoutResult) {
            clearPathCache()
            cachedLayoutResult = snapshot.layoutResult
        }
        val textLength = snapshot.layoutResult.layoutInput.text.length
        if (cachedFullCount != snapshot.opaqueCount) {
            cachedFullCount = snapshot.opaqueCount
            val end = snapshot.boundaries[cachedFullCount].coerceIn(0, textLength)
            cachedFullPath = if (end > 0) snapshot.layoutResult.getPathForRange(0, end) else null
        }
        val activeRanges = snapshot.fadingGroups.map { it.start to it.end }.toSet()
        cachedFadePaths.keys.retainAll(activeRanges)
        for ((start, end) in activeRanges) {
            cachedFadePaths.getOrPut(start to end) {
                snapshot.layoutResult.getPathForRange(
                    snapshot.boundaries[start].coerceIn(0, textLength),
                    snapshot.boundaries[end].coerceIn(0, textLength),
                )
            }
        }
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedFullCount = -1
        cachedFullPath = null
        cachedFadePaths.clear()
    }
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
    val opaqueCount: Int
        get() = record.opaqueCount
    val fadingGroups: List<RevealFadeGroup>
        get() = record.fadingGroups
    val visibleCount: Int
        get() = record.admittedCount

    fun glyphAlpha(index: Int): Float = when {
        index < 0 || index >= boundaries.lastIndex -> 0f
        index < opaqueCount -> 1f
        else -> fadingGroups.firstOrNull { index >= it.start && index < it.end }?.alpha ?: 0f
    }
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var opaqueCount: Int = 0
    var admittedCount: Int = 0
    val fadingGroups = mutableListOf<RevealFadeGroup>()
    val hasPendingReveal: Boolean
        get() = progress < targetCount || fadingGroups.isNotEmpty()
    var targetCount: Float = 0f
}

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = snapshot.visibleCount
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * 为只追加文本增量维护字素边界。
 *
 * 新内容可能把旧文本的最后一个字素继续延长，例如组合音标、ZWJ emoji、旗帜和 CRLF。
 * 因此保留倒数第二个边界之前的结果，只重算最后一个旧字素和新增后缀，避免每个流式
 * 分片都从头扫描整条回答。
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

/**
 * 小分片以每秒 50 字素连续显现，避免逐字等待；批量到达时按整条回答的积压自适应追赶。
 * 0.30 秒是追赶的时间常数，并非固定延迟：不等缓冲、不按块停顿，也不限制最高速度，
 * 因而长回答和高速输出仍能追平。相比 36 字素/秒、0.20 秒，日常增量更流畅，突发分片
 * 的首帧跳跃更小；尾部降至基础速度后自然排空。
 */
internal fun smoothRevealSpeed(totalBacklog: Float): Float =
    max(BASE_REVEAL_GRAPHEMES_PER_SECOND, totalBacklog / TARGET_CATCH_UP_SECONDS)

internal fun advanceSmoothReveal(
    current: Float,
    target: Float,
    elapsedSeconds: Float,
    totalBacklog: Float,
): Float {
    if (current >= target) return target
    // 帧间隔已在调用侧限制在 MAX_FRAME_DELTA_SECONDS 内，单帧推进量由自适应速度决定。
    // 不能再加每帧 1 字素的硬上限，否则积压时追赶速度失效，输出会稳定滞后于模型。
    val advance = (smoothRevealSpeed(totalBacklog) * elapsedSeconds).coerceAtLeast(0f)
    return (current + advance).coerceAtMost(target)
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_DELTA_SECONDS = 0.05f
private const val BASE_REVEAL_GRAPHEMES_PER_SECOND = 50f
private const val TARGET_CATCH_UP_SECONDS = 0.30f
internal const val REVEAL_GROUP_SIZE = 3

/** A short group fades for 260 ms while later groups are already being admitted. */
internal class RevealFadeGroup(val start: Int, var end: Int, var ageSeconds: Float) {
    val alpha: Float get() = revealFadeAlpha(ageSeconds)
}

internal const val REVEAL_FADE_SECONDS = 0.26f

internal fun revealFadeAlpha(ageSeconds: Float): Float {
    val fraction = (ageSeconds / REVEAL_FADE_SECONDS).coerceIn(0f, 1f)
    // Smooth endpoints avoid a visible onset or final-frame brightness step.
    return fraction * fraction * (3f - 2f * fraction)
}
