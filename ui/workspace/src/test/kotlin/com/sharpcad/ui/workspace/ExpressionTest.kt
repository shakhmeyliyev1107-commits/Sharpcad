package com.sharpcad.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpressionTest {
    @Test
    fun arithmeticAndUnits() {
        assertEquals(30.0, Expression.evaluate("20+5*2")!!, 1e-12)
        assertEquals(12.0, Expression.evaluate("(40-4)/3")!!, 1e-12)
        assertEquals(-2.5, Expression.evaluate("-2,5")!!, 1e-12)
        assertEquals(20.0, Expression.evaluate("2 cm")!!, 1e-12)
        assertEquals(35.4, Expression.evaluate("1in+10mm")!!, 1e-9)
    }

    @Test
    fun variables() {
        val vars = mapOf("w" to 40.0, "t_2" to 3.0)
        assertEquals(17.0, Expression.evaluate("w/2-t_2", vars)!!, 1e-12)
        assertEquals(80.0, Expression.evaluate("h = w*2", vars)!!, 1e-12)
        assertEquals("h", Expression.assignedName("h = w*2"))
        assertNull(Expression.assignedName("20+5"))
        assertNull(Expression.evaluate("q+1", vars))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(Expression.evaluate("2+"))
        assertNull(Expression.evaluate("abc"))
        assertNull(Expression.evaluate("1/0"))
    }
}
