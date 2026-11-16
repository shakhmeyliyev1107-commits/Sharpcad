package com.sharpcad.kernel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the JNI boundary on the host JVM against a host build of libcadcore. */
class KernelJniTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun occtVersionIsReported() {
        assertTrue(Kernel.occtVersion().matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun boxBoundingBoxMatchesDimensions() {
        Kernel.makeBox(10.0, 20.0, 30.0).use { box ->
            val b = box.boundingBox()
            assertEquals(0.0, b.minX, TOLERANCE)
            assertEquals(0.0, b.minY, TOLERANCE)
            assertEquals(0.0, b.minZ, TOLERANCE)
            assertEquals(10.0, b.maxX, TOLERANCE)
            assertEquals(20.0, b.maxY, TOLERANCE)
            assertEquals(30.0, b.maxZ, TOLERANCE)
        }
    }

    @Test
    fun invalidDimensionsBecomeKernelException() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val e = assertThrows(KernelException::class.java) { Kernel.makeBox(bad, 1.0, 1.0) }
            assertEquals(KernelException.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun closedShapeRejectsCalls() {
        val box = Kernel.makeBox(1.0, 1.0, 1.0)
        box.close()
        assertTrue(box.isClosed)
        val e = assertThrows(KernelException::class.java) { box.boundingBox() }
        assertEquals(KernelException.INVALID_HANDLE, e.code)
        assertEquals(
            KernelException.INVALID_HANDLE,
            assertThrows(KernelException::class.java) { box.tessellate(0.1) }.code,
        )
        box.close() // double close is a no-op
    }

    @Test
    fun tessellatedBoxArrivesInNativeOrderBuffers() {
        val mesh = Kernel.makeBox(10.0, 20.0, 30.0).use { it.tessellate(0.1) }
        assertEquals(24, mesh.vertexCount)
        assertEquals(12, mesh.triangleCount)
        assertEquals(12, mesh.edgeSegmentCount)
        assertTrue(mesh.positions.isDirect)

        val positions = mesh.positions.asFloatBuffer()
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in 0 until mesh.vertexCount) maxZ = maxOf(maxZ, positions.get(i * 3 + 2))
        assertEquals(30.0f, maxZ, 1e-4f)

        val indices = mesh.indices.asIntBuffer()
        for (i in 0 until mesh.triangleCount * 3) assertTrue(indices.get(i) in 0 until 24)
    }

    @Test
    fun meshOutlivesItsShape() {
        val mesh = Kernel.makeBox(1.0, 1.0, 1.0).use { it.tessellate(0.1) }
        System.gc()
        assertEquals(12, mesh.triangleCount)
        assertEquals(1.0f, mesh.normals.asFloatBuffer().let { n ->
            (0 until 3).map { n.get(it) }.maxOf { kotlin.math.abs(it) }
        }, 1e-5f)
    }

    @Test
    fun invalidDeflectionBecomesKernelException() {
        Kernel.makeBox(1.0, 1.0, 1.0).use { box ->
            val e = assertThrows(KernelException::class.java) { box.tessellate(0.0) }
            assertEquals(KernelException.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun stepRoundTripPreservesBounds() {
        val file = File(temp.root, "qutı ö.step") // non-ASCII path must survive JNI
        Kernel.makeBox(10.0, 20.0, 30.0).use { it.exportStep(file.path) }
        assertTrue(file.length() > 0)
        Kernel.importStep(file.path).use { imported ->
            val b = imported.boundingBox()
            assertEquals(10.0, b.maxX, TOLERANCE)
            assertEquals(20.0, b.maxY, TOLERANCE)
            assertEquals(30.0, b.maxZ, TOLERANCE)
            assertEquals(12, imported.tessellate(0.1).triangleCount)
        }
    }

    @Test
    fun missingOrInvalidStepIsIoError() {
        val missing = assertThrows(KernelException::class.java) {
            Kernel.importStep(File(temp.root, "none.step").path)
        }
        assertEquals(KernelException.IO_ERROR, missing.code)

        val garbage = temp.newFile("garbage.step").apply { writeText("not a step file") }
        val invalid = assertThrows(KernelException::class.java) { Kernel.importStep(garbage.path) }
        assertEquals(KernelException.IO_ERROR, invalid.code)
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
