package com.sharpcad.viewport

import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitCameraTest {

    private fun OrbitCamera.eyeDistance(): Double {
        val dx = eyeX - targetX
        val dy = eyeY - targetY
        val dz = eyeZ - targetZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun eyeStaysAtDistanceFromTargetWhileOrbiting() {
        val camera = OrbitCamera()
        repeat(50) {
            camera.orbit(37f, -23f, 1000)
            assertEquals(camera.distance, camera.eyeDistance(), 1e-9)
        }
    }

    @Test
    fun pitchIsClampedShortOfThePoles() {
        val camera = OrbitCamera()
        camera.orbit(0f, 1e6f, 1000)
        assertEquals(OrbitCamera.MAX_PITCH, camera.pitch, 1e-12)
        camera.orbit(0f, -1e7f, 1000)
        assertEquals(-OrbitCamera.MAX_PITCH, camera.pitch, 1e-12)
    }

    @Test
    fun fullHeightDragOrbitsHalfATurn() {
        val camera = OrbitCamera()
        val before = camera.yaw
        camera.orbit(500f, 0f, 1000)
        assertEquals(Math.PI / 2, Math.abs(camera.yaw - before), 1e-9)
    }

    @Test
    fun panMovesTargetByTheWorldSizeOfThePixels() {
        val camera = OrbitCamera()
        val height = 1000
        val worldPerPixel = 2.0 * camera.distance * tan(camera.verticalFov / 2.0) / height
        val x0 = camera.targetX
        val y0 = camera.targetY
        val z0 = camera.targetZ
        camera.pan(100f, 0f, height)
        val moved = sqrt(
            (camera.targetX - x0).let { it * it } + (camera.targetY - y0).let { it * it } +
                (camera.targetZ - z0).let { it * it },
        )
        assertEquals(100 * worldPerPixel, moved, 1e-9)
        assertEquals("horizontal pan keeps height", z0, camera.targetZ, 1e-12)
    }

    @Test
    fun panIsPerpendicularToTheViewDirection() {
        val camera = OrbitCamera()
        val viewX = camera.targetX - camera.eyeX
        val viewY = camera.targetY - camera.eyeY
        val viewZ = camera.targetZ - camera.eyeZ
        val x0 = camera.targetX
        val y0 = camera.targetY
        val z0 = camera.targetZ
        camera.pan(40f, 70f, 800)
        val dot = (camera.targetX - x0) * viewX + (camera.targetY - y0) * viewY +
            (camera.targetZ - z0) * viewZ
        assertEquals(0.0, dot, 1e-6)
    }

    @Test
    fun zoomScalesDistanceAndIgnoresBadFactors() {
        val camera = OrbitCamera()
        val d = camera.distance
        camera.zoom(2f)
        assertEquals(d / 2, camera.distance, 1e-9)
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) camera.zoom(bad)
        assertEquals(d / 2, camera.distance, 1e-9)
        camera.zoom(1e30f)
        assertEquals(OrbitCamera.MIN_DISTANCE, camera.distance, 1e-12)
        camera.zoom(1e-30f)
        assertEquals(OrbitCamera.MAX_DISTANCE, camera.distance, 1e-6)
    }

    @Test
    fun fitKeepsTheSphereInsideBothFieldsOfView() {
        for (aspect in listOf(0.45, 1.0, 2.2)) {
            val camera = OrbitCamera()
            camera.fit(10.0, 20.0, 30.0, 50.0, aspect)
            assertEquals(10.0, camera.targetX, 0.0)
            val halfHeight = camera.distance * tan(camera.verticalFov / 2.0)
            assertTrue("aspect $aspect vertical", halfHeight >= 50.0)
            assertTrue("aspect $aspect horizontal", halfHeight * aspect >= 50.0)
        }
    }

    @Test
    fun clipRangeAlwaysContainsTheSceneAndStaysValid() {
        val camera = OrbitCamera().apply { sceneRadius = 250.0 }
        for (factor in listOf(1f, 100f, 0.001f, 1e9f)) {
            camera.zoom(factor)
            assertTrue(camera.near > 0.0)
            assertTrue(camera.far > camera.near)
            assertTrue(camera.far >= camera.distance + 250.0)
        }
    }
}
