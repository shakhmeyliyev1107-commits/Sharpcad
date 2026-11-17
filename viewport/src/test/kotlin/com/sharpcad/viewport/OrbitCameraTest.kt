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

    @Test
    fun centreRayPointsAtTheTarget() {
        val camera = OrbitCamera()
        camera.fit(10.0, -5.0, 3.0, 40.0, 1.5)
        val ray = camera.ray(600f, 400f, 1200, 800)!!
        // Walk `distance` along the ray: we must land on the target.
        assertEquals(camera.targetX, ray[0] + camera.distance * ray[3], 1e-9)
        assertEquals(camera.targetY, ray[1] + camera.distance * ray[4], 1e-9)
        assertEquals(camera.targetZ, ray[2] + camera.distance * ray[5], 1e-9)
    }

    @Test
    fun topViewRaysMapScreenAxesToModelXAndY() {
        val camera = OrbitCamera()
        camera.fit(0.0, 0.0, 0.0, 100.0, 1.0)
        camera.setView(Math.toRadians(-90.0), Math.toRadians(90.0)) // clamped to 89 degrees
        fun groundHit(x: Float, y: Float): Pair<Double, Double> {
            val r = camera.ray(x, y, 1000, 1000)!!
            val t = -r[2] / r[5]
            return Pair(r[0] + t * r[3], r[1] + t * r[4])
        }
        val centre = groundHit(500f, 500f)
        val right = groundHit(600f, 500f)
        val up = groundHit(500f, 400f)
        assertTrue("screen right is +X", right.first - centre.first > 0)
        assertEquals(centre.second, right.second, 1e-6)
        assertTrue("screen up is +Y", up.second - centre.second > 0)
        assertEquals(centre.first, up.first, 1e-6)
    }

    @Test
    fun pixelSizeMatchesPanDistance() {
        val camera = OrbitCamera()
        val x0 = camera.targetX
        val y0 = camera.targetY
        camera.pan(1f, 0f, 900)
        val moved = sqrt((camera.targetX - x0).let { it * it } + (camera.targetY - y0).let { it * it })
        assertEquals(camera.millimetresPerPixel(900), moved, 1e-9)
        assertEquals(0.0, camera.millimetresPerPixel(0), 0.0)
    }

    @Test
    fun rayIsNullForEmptyViewport() {
        assertEquals(null, OrbitCamera().ray(0f, 0f, 0, 100))
    }
}
