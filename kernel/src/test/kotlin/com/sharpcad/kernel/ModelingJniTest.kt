package com.sharpcad.kernel

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modeling calls across the JNI boundary, on the host JVM. */
class ModelingJniTest {
    private val xy = PlaneFrame(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0))

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        doubleArrayOf(x0, y0, x1, y0, x1, y0, x1, y1, x1, y1, x0, y1, x0, y1, x0, y0)

    @Test
    fun ringRegionExtrudesWithAHole() {
        Kernel.profileFromSketch(xy, rect(0.0, 0.0, 40.0, 40.0), doubleArrayOf(20.0, 20.0, 5.0)).use { profile ->
            assertEquals(2, profile.faceCount)
            val ring = profile.regionAt(xy, 1.0, 1.0)
            val disc = profile.regionAt(xy, 20.0, 20.0)
            assertNotEquals(ring, disc)
            assertEquals(-1, profile.regionAt(xy, 99.0, 99.0))
            profile.subset(listOf(ring)).use { region ->
                region.extrude(0.0, 0.0, 10.0).use { assertEquals((1600 - PI * 25) * 10, it.volume, 1e-3) }
            }
        }
    }

    @Test
    fun revolveBooleanFilletChamferAndPushPull() {
        val profile = Kernel.profileFromSketch(xy, rect(10.0, 0.0, 20.0, 5.0), DoubleArray(0))
        val tube = profile.revolve(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), 2 * PI)
        assertEquals(PI * 300 * 5, tube.volume, 1e-3)

        val a = Kernel.makeBox(10.0, 10.0, 10.0)
        val b = Kernel.makeBox(5.0, 5.0, 20.0)
        assertEquals(1000.0 + 250.0, a.booleanWith(b, BooleanOp.UNION).volume, 1e-6)
        assertEquals(750.0, a.booleanWith(b, BooleanOp.SUBTRACT).volume, 1e-6)
        assertEquals(250.0, a.booleanWith(b, BooleanOp.INTERSECT).volume, 1e-6)

        assertTrue(a.fillet(1.0).volume < 1000.0)
        assertEquals(10, a.chamfer(1.0, 0).faceCount)

        val top = a.pick(doubleArrayOf(5.0, 5.0, 50.0), doubleArrayOf(0.0, 0.0, -1.0))!!
        assertEquals(40.0, top.distance, 1e-6)
        assertEquals(1200.0, a.pushPull(top.face, 2.0).volume, 1e-6)
        assertNull(a.pick(doubleArrayOf(50.0, 50.0, 50.0), doubleArrayOf(0.0, 0.0, 1.0)))

        assertEquals(1000.0 + 500.0, Kernel.compound(listOf(a, b)).volume, 1e-6)
        listOf(profile, tube, a, b).forEach { it.close() }
    }

    @Test
    fun meshCarriesAFacePerTriangle() {
        Kernel.makeBox(1.0, 1.0, 1.0).use { box ->
            val mesh = box.tessellate(0.1)
            val faces = mesh.triangleFaces.asIntBuffer()
            assertEquals(mesh.triangleCount, faces.capacity())
            for (i in 0 until faces.capacity()) assertTrue(faces.get(i) in 0 until box.faceCount)
        }
    }

    @Test
    fun modelingErrorsAreKernelExceptions() {
        val open = assertThrows(KernelException::class.java) {
            Kernel.profileFromSketch(xy, doubleArrayOf(0.0, 0.0, 1.0, 0.0), DoubleArray(0))
        }
        assertEquals(KernelException.INVALID_ARGUMENT, open.code)
        Kernel.makeBox(1.0, 1.0, 1.0).use { box ->
            assertEquals(KernelException.INVALID_ARGUMENT, assertThrows(KernelException::class.java) { box.pushPull(99, 1.0) }.code)
            assertEquals(KernelException.OCCT_FAILURE, assertThrows(KernelException::class.java) { box.fillet(5.0) }.code)
            assertEquals(KernelException.INVALID_ARGUMENT, assertThrows(KernelException::class.java) { box.extrude(0.0, 0.0, 0.0) }.code)
        }
        assertThrows(IllegalArgumentException::class.java) { PlaneFrame(DoubleArray(2), DoubleArray(3), DoubleArray(3)) }
    }

    @Test
    fun projectionCrossesJniAsSegments() {
        Kernel.makeBox(10.0, 20.0, 30.0).use { box ->
            val view = box.project(doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            assertTrue(view.visible.isNotEmpty())
            assertEquals(0, view.visible.size % 4)
            val xs = view.visible.filterIndexed { i, _ -> i % 2 == 0 }
            assertEquals(10f, xs.max() - xs.min(), 1e-3f)
        }
    }
}
