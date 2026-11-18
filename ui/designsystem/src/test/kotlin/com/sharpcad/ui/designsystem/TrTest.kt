package com.sharpcad.ui.designsystem

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TrTest {
    @After
    fun reset() {
        Tr.language = Language.EN
    }

    @Test
    fun englishIsUnchanged() {
        assertEquals("Fillet", tr("Fillet"))
    }

    @Test
    fun exactAndFragments() {
        Tr.language = Language.UZ
        assertEquals("Yumaloqlash", tr("Fillet"))
        assertEquals("Yumaloqlash 2 mm", tr("Fillet 2 mm"))
        assertEquals("3 jism · 1,200 uchburchak", tr("3 bodies · 1,200 triangles"))
        assertEquals("Jism · yuza 4", tr("Body · face 4"))
        Tr.language = Language.RU
        assertEquals("Открыть: Part", tr("Open: Part"))
        assertEquals("Something new", tr("Something new"))
    }
}
