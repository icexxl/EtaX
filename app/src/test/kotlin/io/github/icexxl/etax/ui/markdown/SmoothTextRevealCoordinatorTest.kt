package io.github.icexxl.etax.ui.markdown

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmoothTextRevealCoordinatorTest {
    private val textMeasurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    @Test
    fun detachedEarlierBlockCompletesAndLaterBlockKeepsRevealing() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val earlierKey = RevealBlockKey(0)
        val laterKey = RevealBlockKey(100)
        val earlierNode = attach(coordinator, earlierKey, "早先的思考正文")
        attach(coordinator, laterKey, "随后到达的工具输出内容")

        coordinator.detach(earlierKey, earlierNode)

        assertEquals(7f, coordinator.drawSnapshot(earlierKey)!!.progress, 0f)
        assertTrue(earlierKey in coordinator.started.value)
        assertFalse(coordinator.drained.value)
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()

            assertTrue(coordinator.drawSnapshot(laterKey)!!.progress > 0f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun oneFrameCarriesAdvanceAcrossBlocksInSourceOrder() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val keys = (0 until 10).map { RevealBlockKey(it * 20) }
        keys.reversed().forEach { attach(coordinator, it, "abcdefghij") }
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()

            assertEquals(10f, coordinator.drawSnapshot(keys[0])!!.progress, 0f)
            assertEquals(100f / 0.30f * 0.05f - 10f, coordinator.drawSnapshot(keys[1])!!.progress, 0.0001f)
            keys.drop(2).forEach { assertEquals(0f, coordinator.drawSnapshot(it)!!.progress, 0f) }
            assertEquals(keys.take(2).toSet(), coordinator.started.value)
            assertFalse(coordinator.drained.value)

            repeat(60) { clock.send((it + 2) * 50_000_000L) }
            yield()
            assertTrue(coordinator.drained.value)
            assertEquals(keys.toSet(), coordinator.started.value)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun stalledFrameDoesNotDumpAccumulatedWallClockTimeIntoReveal() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        attach(coordinator, key, "a".repeat(100))
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(2_000_000_000L)
            yield()

            // A delayed frame still advances by at most 50 ms of pacing.
            assertEquals(100f / 0.30f * 0.05f, coordinator.drawSnapshot(key)!!.progress, 0.0001f)
            assertFalse(coordinator.drained.value)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun backgroundUpdatesAndRestoreAreReadableBeforeNewSuffixAnimates() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val state = SmoothTextRevealState(key, coordinator)
        state.attach(SmoothTextRevealNode(state))
        state.onTextLayout("prefix", layout("prefix"))

        coordinator.pauseAnimationsAndCatchUp()
        state.onTextLayout("prefix background", layout("prefix background"))
        coordinator.resumeAnimationsAfterCatchUp()
        assertTrue(coordinator.drained.value)
        assertEquals(17f, coordinator.drawSnapshot(key)!!.progress, 0f)

        state.onTextLayout("prefix background live", layout("prefix background live"))
        assertEquals(17f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertEquals(22, coordinator.drawSnapshot(key)!!.boundaries.lastIndex)
        repeat(17) { assertEquals(1f, coordinator.drawSnapshot(key)!!.glyphAlpha(it), 0f) }
        assertEquals(0f, coordinator.drawSnapshot(key)!!.glyphAlpha(17), 0f)
        assertFalse(coordinator.drained.value)
    }

    @Test
    fun reattachedBlockKeepsCompletedPrefixAndAnimatesOnlyNewText() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "已有文字")

        coordinator.detach(key, oldNode)
        assertTrue(coordinator.drained.value)
        attach(coordinator, key, "已有文字和新增文字")

        val snapshot = coordinator.drawSnapshot(key)!!
        assertEquals(4f, snapshot.progress, 0f)
        assertEquals(9, snapshot.boundaries.lastIndex)
        repeat(4) { assertEquals(1f, snapshot.glyphAlpha(it), 0f) }
        assertEquals(0f, snapshot.glyphAlpha(4), 0f)
        assertFalse(coordinator.drained.value)
    }

    @Test
    fun layoutWithoutMountedNodeIsImmediatelyReadableOnLaterAttach() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val text = "尚未挂载时收到的历史内容"
        val state = SmoothTextRevealState(key, coordinator)
        state.onTextLayout(text, layout(text))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        state.attach(SmoothTextRevealNode(state))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
    }

    @Test
    fun staleDetachDoesNotCompleteTheReplacementNode() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "原始内容")
        val replacementNode = attach(coordinator, key, "替换后的完整内容")

        coordinator.detach(key, oldNode)

        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        coordinator.detach(key, replacementNode)
        assertTrue(coordinator.drained.value)
    }

    @Test
    fun rollingTrailOverlapsGroupsAndGlyphOpacityNeverRegressesOnAppend() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val state = SmoothTextRevealState(key, coordinator)
        state.attach(SmoothTextRevealNode(state))
        state.onTextLayout("abcdefghijklmnopqr", layout("abcdefghijklmnopqr"))
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) { coordinator.runFrameClock() }
        try {
            clock.send(0L)
            var previous = FloatArray(18)
            repeat(65) { frame ->
                clock.send((frame + 1) * 16_666_667L)
                yield()
                val snapshot = coordinator.drawSnapshot(key)!!
                val alphas = FloatArray(18) { snapshot.glyphAlpha(it) }
                alphas.forEachIndexed { index, alpha ->
                    assertTrue("glyph $index regressed at frame $frame", alpha >= previous[index])
                }
                previous = alphas
                if (frame == 7) {
                    val partialGroups = snapshot.fadingGroups.filter { it.alpha > 0f && it.alpha < 1f }
                    assertTrue("multiple groups must fade concurrently", partialGroups.size >= 3)
                    assertTrue(partialGroups.first().alpha > partialGroups.last().alpha)
                    val appended = "abcdefghijklmnopqrstuvwxyz"
                    state.onTextLayout(appended, layout(appended))
                    alphas.forEachIndexed { index, alpha ->
                        assertEquals(alpha, snapshot.glyphAlpha(index), 0f)
                    }
                }
            }
            assertTrue(coordinator.drained.value)
            val snapshot = coordinator.drawSnapshot(key)!!
            repeat(26) { assertEquals(1f, snapshot.glyphAlpha(it), 0f) }
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun finalShortTailKeepsClockAliveAfterHeadReachesTarget() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        attach(coordinator, key, "ab")
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) { coordinator.runFrameClock() }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()
            val snapshot = coordinator.drawSnapshot(key)!!
            assertEquals(2f, snapshot.progress, 0f)
            assertTrue(snapshot.glyphAlpha(1) in 0.01f..0.3f)
            assertFalse("head completion must not skip the trailing fade", coordinator.drained.value)
            var previous = snapshot.glyphAlpha(1)
            for (frame in 2..7) {
                clock.send(frame * 50_000_000L)
                yield()
                assertTrue(snapshot.glyphAlpha(1) >= previous)
                previous = snapshot.glyphAlpha(1)
            }
            assertTrue(coordinator.drained.value)
            assertEquals(1f, snapshot.glyphAlpha(1), 0f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    private fun attach(
        coordinator: SmoothTextRevealCoordinator,
        key: RevealBlockKey,
        text: String,
    ): SmoothTextRevealNode {
        val state = SmoothTextRevealState(key, coordinator)
        val node = SmoothTextRevealNode(state)
        state.attach(node)
        state.onTextLayout(text, layout(text))
        return node
    }

    private fun layout(text: String): TextLayoutResult = textMeasurer.measure(
        text = text,
        style = TextStyle(fontSize = 16.sp),
        constraints = Constraints(maxWidth = 320),
    )

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)

        fun send(timeNanos: Long) {
            check(frames.trySend(timeNanos).isSuccess)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
