package io.github.icexxl.etax.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraLapPhaseTest {
    private fun rate(at: Float, dt: Float = 0.02f): Float = (lapEase(at + dt) - lapEase(at)) / dt

    @Test
    fun oneLapRunsFromZeroToOneAndRestartsAtTheSameColor() {
        assertEquals(0f, lapEase(0f), 1e-4f)
        assertEquals(1f, lapEase(7.99f), 2e-2f)
        assertEquals(0f, lapEase(8f), 1e-4f)
        assertTrue(lapEase(8.05f) < 0.01f)
    }

    @Test
    fun easeNeverGoesBackwardsInsideALap() {
        var previous = lapEase(0f)
        var t = 0f
        while (t < 7.9f) {
            t += 0.02f
            val next = lapEase(t)
            assertTrue("ease regressed at $t", next >= previous)
            previous = next
        }
    }

    @Test
    fun easeIsSlowAtBothEndsAndFastInTheMiddle() {
        val start = rate(0.1f)
        val middle = rate(4f)
        val end = rate(7.5f)
        assertTrue("start must be nearly still, was $start", start < 0.02f)
        assertTrue("end must decelerate, was $end", end < 0.06f)
        assertTrue("end must be well below mid speed", end < middle / 4f)
        assertTrue("middle must dominate, was $middle", middle > 0.15f)
    }

    @Test
    fun middleOfTheCurveMovesAboutOneAndAHalfTimesAverage() {
        // smoothstep has peak slope 1.5x the average slope.
        assertEquals(1.5f, rate(4f) * 8f, 0.05f)
    }

    @Test
    fun columnsShareTheCurveWithAStaggeredStart() {
        // Same instant, left edge ahead of right edge; gap shrinks while the curve is flat.
        val moving = columnLapEase(4f, 0f) - columnLapEase(4f, 1f)
        val flat = columnLapEase(0.4f, 0f) - columnLapEase(0.4f, 1f)
        assertTrue("mid-lap gap $moving must be visible", moving > 0.02f)
        assertTrue("plateau gap $flat must nearly vanish", flat < moving / 4f)
    }
}
