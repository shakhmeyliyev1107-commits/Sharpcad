package com.sharpcad.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainOffsetTest {
    private val square = listOf(
        SketchLine(1, 0.0, 0.0, 10.0, 0.0),
        SketchLine(2, 10.0, 0.0, 10.0, 10.0),
        SketchLine(3, 10.0, 10.0, 0.0, 10.0),
        SketchLine(4, 0.0, 10.0, 0.0, 0.0),
    )

    @Test
    fun squareOffsetsToABiggerSquare() {
        val c = ChainOffset.chain(square, square[0])
        assertTrue(c.closed)
        assertEquals(4, c.lines.size)
        val d = ChainOffset.side(c, 5.0, -2.0) // below the bottom edge: outside, right side
        assertEquals(-2.0, d, 1e-9)
        val p = ChainOffset.offset(c, d)
        assertEquals(10, p.size)
        val xs = p.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(-2.0, xs.min(), 1e-9)
        assertEquals(12.0, xs.max(), 1e-9)
    }

    @Test
    fun openChainKeepsItsEnds() {
        val open = square.take(2)
        val c = ChainOffset.chain(open, open[1])
        assertEquals(false, c.closed)
        val p = ChainOffset.offset(c, 1.0) // left of 0,0 -> 10,0 -> 10,10 is inside
        assertEquals(listOf(0.0, 1.0, 9.0, 1.0, 9.0, 10.0), p.map { Math.round(it * 1e9) / 1e9 })
    }
}
