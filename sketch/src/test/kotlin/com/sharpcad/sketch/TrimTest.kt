package com.sharpcad.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class TrimTest {
    private val tol = 1.0

    @Test
    fun crossedLineKeepsBothOuterPieces() {
        val a = SketchLine(1, 0.0, 0.0, 10.0, 0.0)
        val b = SketchLine(2, 3.0, -5.0, 3.0, 5.0)
        val c = SketchLine(3, 7.0, -5.0, 7.0, 5.0)
        val r = Trim.trim(listOf(a, b, c), 5.0, 0.2, tol)!!
        assertEquals(1, r.removed)
        assertEquals(2, r.lines.size)
        assertEquals(3.0, r.lines[0][2], 1e-9)
        assertEquals(7.0, r.lines[1][0], 1e-9)
    }

    @Test
    fun lineCrossingNothingIsRemoved() {
        val r = Trim.trim(listOf(SketchLine(1, 0.0, 0.0, 10.0, 0.0)), 5.0, 0.0, tol)!!
        assertEquals(0, r.lines.size + r.arcs.size)
    }

    @Test
    fun circleCutByLineBecomesArc() {
        val circle = SketchCircle(1, 0.0, 0.0, 5.0)
        val line = SketchLine(2, -10.0, 0.0, 10.0, 0.0)
        val r = Trim.trim(listOf(circle, line), 0.0, 5.0, tol)!! // tap the top half
        assertEquals(1, r.arcs.size)
        assertEquals(PI, r.arcs[0][3], 1e-9)       // keeps the bottom: pi..2pi
        assertEquals(2 * PI, r.arcs[0][4], 1e-9)
    }

    @Test
    fun farTapTrimsNothing() {
        assertNull(Trim.trim(listOf(SketchLine(1, 0.0, 0.0, 10.0, 0.0)), 5.0, 9.0, tol))
    }
}
