package com.sharpcad.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialLibraryTest {
    @Test
    fun atLeastOneHundredPresetsWithUniqueIdsAndValidValues() {
        val presets = MaterialLibrary.presets
        assertTrue("have ${presets.size}", presets.size >= 100)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        for (p in presets) {
            assertTrue(p.id, p.metallic in 0f..1f && p.roughness in 0f..1f)
            assertTrue(p.id, p.color in 0..0xFFFFFF)
        }
    }

    @Test
    fun unknownIdsFallBackToTheDefault() {
        assertEquals(MaterialLibrary.DEFAULT_ID, MaterialLibrary.find("no.such").id)
        assertEquals(MaterialLibrary.DEFAULT_ID, MaterialLibrary.find(null).id)
        assertEquals("metal.gold_polished", MaterialLibrary.find("metal.gold_polished").id)
        assertTrue(MaterialLibrary.categories.containsAll(listOf("Metal", "Plastic", "Wood", "Glass")))
    }
}
