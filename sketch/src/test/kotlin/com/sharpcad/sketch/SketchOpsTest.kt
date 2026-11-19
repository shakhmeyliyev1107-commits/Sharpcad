package com.sharpcad.sketch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SketchOpsTest {
    private val yAxis = SketchLine(0, 0.0, 0.0, 0.0, 10.0)

    @Test
    fun mirrorLineAcrossYAxis() {
        val m = SketchOps.mirror(SketchLine(1, 2.0, 1.0, 5.0, 3.0), yAxis)
        assertArrayEquals(doubleArrayOf(-2.0, 1.0, -5.0, 3.0), m.lines.single(), 1e-9)
    }

    @Test
    fun mirroredArcKeepsItsEndPoints() {
        val arc = SketchArc(2, 5.0, 0.0, 2.0, 0.0, PI / 2)
        val out = SketchOps.mirror(arc, yAxis).arcs.single()
        assertEquals(-5.0, out[0], 1e-9)
        // Original ends (7,0) and (5,2) mirror to (-7,0) and (-5,2): both must lie on the new arc ends.
        val ends = listOf(out[3], out[4]).map { a -> (out[0] + out[2] * cos(a)) to (out[1] + out[2] * sin(a)) }
        val expected = setOf(-7.0 to 0.0, -5.0 to 2.0)
        ends.forEach { (x, y) -> assert(expected.any { kotlin.math.hypot(it.first - x, it.second - y) < 1e-9 }) }
    }

    @Test
    fun filletRightAngleCorner() {
        val (pts, arc) = SketchOps.fillet(0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 2.0)!!
        assertArrayEquals(doubleArrayOf(2.0, 0.0, 0.0, 2.0), pts, 1e-9)
        assertArrayEquals(doubleArrayOf(2.0, 2.0, 2.0), arc.copyOf(3), 1e-9)
        assertEquals(PI / 2, ((arc[4] - arc[3]) % (2 * PI) + 2 * PI) % (2 * PI), 1e-9)
        assertNull(SketchOps.fillet(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 5.0)) // too short
    }
}
