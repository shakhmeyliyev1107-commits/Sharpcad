package com.sharpcad.sketch

import org.junit.Assert.assertEquals
import org.junit.Test

class SplineTest {
    @Test
    fun curvePassesThroughControlPoints() {
        val pts = listOf(0.0, 0.0, 10.0, 5.0, 20.0, 0.0)
        val s = Spline.sample(pts)
        assertEquals(2 * (2 * Spline.SAMPLES + 1), s.size)
        assertEquals(10.0, s[2 * Spline.SAMPLES], 1e-12)
        assertEquals(5.0, s[2 * Spline.SAMPLES + 1], 1e-12)
        assertEquals(20.0, s[s.size - 2], 1e-12)
        assertEquals(4 * 2 * Spline.SAMPLES, Spline.segments(pts).size)
    }

    @Test
    fun thinKeepsEndsAndSpacing() {
        val raw = (0..10).flatMap { listOf(it.toDouble(), 0.0) }
        assertEquals(listOf(0.0, 0.0, 4.0, 0.0, 8.0, 0.0, 10.0, 0.0), Spline.thin(raw, 4.0))
    }
}
