package com.sharpcad.ui.designsystem

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @After
    fun reset() {
        Units.current = LengthUnit.MM
    }

    @Test
    fun formatsInTheChosenUnit() {
        assertEquals("12.5 mm", Units.format(12.5))
        Units.current = LengthUnit.CM
        assertEquals("1.25 cm", Units.format(12.5))
        Units.current = LengthUnit.IN
        assertEquals("1", Units.number(25.4))
    }
}
