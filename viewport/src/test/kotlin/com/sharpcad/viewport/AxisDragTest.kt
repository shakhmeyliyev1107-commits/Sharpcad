package com.sharpcad.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AxisDragTest {
    @Test
    fun rayCrossingAxisGivesHeightOfCrossing() {
        // Axis: Z through origin. Ray along -Y passing x=0, z=7.
        val s = AxisDrag.along(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 50.0, 7.0), doubleArrayOf(0.0, -1.0, 0.0))
        assertEquals(7.0, s!!, 1e-9)
    }

    @Test
    fun skewRayUsesClosestPoint() {
        val s = AxisDrag.along(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.0, 0.0, 2.0), doubleArrayOf(10.0, 2.0, 8.0), doubleArrayOf(0.0, 1.0, 0.0))
        assertEquals(2.5, s!!, 1e-9) // z = 3 + 2.5·2 = 8
    }

    @Test
    fun parallelRayHasNoAnswer() {
        assertNull(AxisDrag.along(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(5.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0)))
    }

    @Test
    fun snapRoundsToStep() {
        assertEquals(12.5, AxisDrag.snap(12.4, 0.5), 1e-9)
        assertEquals(3.3, AxisDrag.snap(3.3, 0.0), 1e-9)
    }
}
