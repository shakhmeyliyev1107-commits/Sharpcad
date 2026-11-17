package com.sharpcad.document

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshExportTest {
    // One square of two triangles in the XY plane, facing +Z.
    private val square = MeshPart("square", floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2, 0, 2, 3))

    private fun bytes(format: MeshFormat, parts: List<MeshPart>) =
        ByteArrayOutputStream().also { MeshExport.write(format, parts, it) }.toByteArray()

    @Test
    fun binaryStlHasHeaderCountAndNormals() {
        val data = bytes(MeshFormat.STL, listOf(square, square))
        assertEquals(84 + 4 * 50, data.size)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, buf.getInt(80))
        assertEquals(1f, buf.getFloat(84 + 8), 1e-6f) // normal z of the first triangle
        assertEquals(1f, buf.getFloat(84 + 12 + 12), 0f) // second vertex x
    }

    @Test
    fun objOffsetsIndicesPerObject() {
        val text = String(bytes(MeshFormat.OBJ, listOf(square, MeshPart("two words", square.positions, square.indices))))
        assertEquals(8, text.lines().count { it.startsWith("v ") })
        assertTrue(text.contains("o two_words"))
        assertTrue(text.contains("f 1 2 3"))
        assertTrue(text.contains("f 5 6 7")) // second object starts after the first four vertices
    }

    @Test
    fun threeMfIsAZipWithTheModelInMillimetres() {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes(MeshFormat.THREE_MF, listOf(square)).inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = String(zip.readBytes())
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"), entries.keys)
        val model = entries.getValue("3D/3dmodel.model")
        assertTrue(model.contains("unit=\"millimeter\""))
        assertEquals(4, Regex("<vertex ").findAll(model).count())
        assertEquals(2, Regex("<triangle ").findAll(model).count())
        assertTrue(model.contains("<item objectid=\"1\"/>"))
    }

    @Test
    fun invalidMeshesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MeshPart("bad", floatArrayOf(0f, 0f), intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { MeshPart("bad", floatArrayOf(0f, 0f, 0f), intArrayOf(0, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { bytes(MeshFormat.STL, emptyList()) }
    }

    @Test
    fun documentExportsMeshAndIges() = runBlocking<Unit> {
        Document().use { doc ->
            val file = File.createTempFile("sharpcad", ".stl")
            assertFalse(doc.exportMesh(MeshFormat.STL, file.path))
            val rect = listOf(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0, 10.0, 0.0, 10.0, 0.0, 0.0)
            doc.reset(listOf(ExtrudeFeature(1, Profile(PlaneSpec.XY, rect, emptyList()), 5.0)))
            assertTrue(doc.exportMesh(MeshFormat.STL, file.path))
            assertEquals(84 + 12 * 50L, file.length()) // a box is twelve triangles
            val iges = File.createTempFile("sharpcad", ".igs")
            assertTrue(doc.exportIges(iges.path))
            doc.reset(listOf(ImportFeature(2, iges.path, "iges")))
            assertEquals(500.0, ModelBuilder.build(doc.state.value.features).use { it.bodies.values.single().volume }, 1e-3)
            file.delete(); iges.delete()
        }
    }
}
