package com.sharpcad.document

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbExportTest {
    private val square = GlbPart(
        "plate \"A\"",
        floatArrayOf(0f, 0f, 0f, 1000f, 0f, 0f, 1000f, 2000f, 0f, 0f, 2000f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        intArrayOf(0, 1, 2, 0, 2, 3),
        MaterialLibrary.find("metal.gold_polished"),
    )

    @Test
    fun headerChunksAndJsonAreValid() {
        val bytes = ByteArrayOutputStream().also { GlbExport.write(listOf(square), it) }.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0)) // glTF
        assertEquals(2, b.getInt(4))
        assertEquals(bytes.size, b.getInt(8))
        val jsonLength = b.getInt(12)
        assertEquals(0x4E4F534A, b.getInt(16))
        assertEquals(0, jsonLength % 4)
        val json = String(bytes, 20, jsonLength).trimEnd()
        assertTrue(json.contains("\"metallicFactor\":1.000000"))
        assertTrue(json.contains("plate \\\"A\\\"")) // name escaped
        // Y-up metres: the 2000 mm Y extent becomes -2 m on Z, the X extent 1 m.
        assertTrue(json, json.contains("\"min\":[0.000000,0.000000,-2.000000]"))
        assertTrue(json, json.contains("\"max\":[1.000000,0.000000,0.000000]"))
        val binLength = b.getInt(20 + jsonLength)
        assertEquals(0x004E4942, b.getInt(24 + jsonLength))
        assertEquals(4 * 3 * 4 * 2 + 6 * 4, binLength) // positions + normals + indices
    }

    @Test
    fun rejectsEmptyOrMismatchedInput() {
        assertThrows(IllegalArgumentException::class.java) { GlbExport.write(emptyList(), ByteArrayOutputStream()) }
        val bad = GlbPart("x", square.positions, floatArrayOf(0f, 0f, 1f), square.indices, MaterialLibrary.default)
        assertThrows(IllegalArgumentException::class.java) { GlbExport.write(listOf(bad), ByteArrayOutputStream()) }
    }
}
