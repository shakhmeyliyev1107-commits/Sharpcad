package com.sharpcad.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStatsRecorderTest {
    private val ms = 1_000_000L

    @Test
    fun reportsAverageAndWorstAfterEnoughFrames() {
        val recorder = FrameStatsRecorder(reportEvery = 4)
        var t = 1_000 * ms
        assertNull(recorder.onFrame(t)) // first frame has no interval
        val intervals = longArrayOf(8, 8, 24, 8)
        var result: FrameStats? = null
        for (i in intervals) {
            t += i * ms
            result = recorder.onFrame(t)
        }
        assertNotNull(result)
        assertEquals(12f, result!!.averageMillis, 1e-3f)
        assertEquals(24f, result.worstMillis, 1e-3f)
        assertEquals(4, result.frames)
    }

    @Test
    fun pauseBetweenBurstsIsNotCountedAsASlowFrame() {
        val recorder = FrameStatsRecorder(reportEvery = 100)
        var t = 0L
        repeat(12) { t += 8 * ms; recorder.onFrame(t) }
        val first = recorder.endBurst()
        assertEquals(8f, first!!.worstMillis, 1e-3f)

        t += 5_000 * ms // idle
        repeat(12) { t += 8 * ms; recorder.onFrame(t) }
        assertEquals(8f, recorder.endBurst()!!.worstMillis, 1e-3f)
    }

    @Test
    fun shortBurstsAreDiscarded() {
        val recorder = FrameStatsRecorder()
        var t = 0L
        repeat(3) { t += 8 * ms; recorder.onFrame(t) }
        assertNull(recorder.endBurst())
        assertNull(recorder.endBurst())
    }

    @Test
    fun skippedFramesAreReportedAndReset() {
        val recorder = FrameStatsRecorder(reportEvery = 100)
        var t = 0L
        repeat(6) { t += 8 * ms; recorder.onFrame(t) }
        recorder.onSkipped()
        recorder.onSkipped()
        repeat(6) { t += 8 * ms; recorder.onFrame(t) }
        assertEquals(2, recorder.endBurst()!!.skipped)
        repeat(12) { t += 8 * ms; recorder.onFrame(t) }
        assertEquals(0, recorder.endBurst()!!.skipped)
    }
}
