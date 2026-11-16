package com.sharpcad.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the JNI boundary on the host JVM against a host build of libcadcore. */
class KernelJniTest {

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
    fun closedSolidRejectsCalls() {
        val box = Kernel.makeBox(1.0, 1.0, 1.0)
        box.close()
        assertTrue(box.isClosed)
        val e = assertThrows(KernelException::class.java) { box.boundingBox() }
        assertEquals(KernelException.INVALID_HANDLE, e.code)
        box.close() // double close is a no-op
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
