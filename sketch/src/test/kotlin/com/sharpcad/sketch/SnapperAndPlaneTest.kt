package com.sharpcad.sketch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapperTest {
    private val line = SketchLine(id = 0, x1 = 0.0, y1 = 0.0, x2 = 20.0, y2 = 0.0)
    private val circle = SketchCircle(id = 1, cx = 50.0, cy = 50.0, radius = 5.0)
    private val entities = listOf(line, circle)

    @Test
    fun snapsToEndpointAndReportsThePoint() {
        val snap = Snapper.snap(19.2, 0.7, entities, tolerance = 2.0)
        assertEquals(Snap(20.0, 0.0, SnapKind.ENDPOINT, PointRef(0, PointRole.END)), snap)
    }

    @Test
    fun snapsToCircleCentre() {
        val snap = Snapper.snap(50.5, 49.0, entities, tolerance = 2.0)
        assertEquals(Snap(50.0, 50.0, SnapKind.CENTER, PointRef(1, PointRole.CENTER)), snap)
    }

    @Test
    fun snapsToMidpointWithoutAPointRef() {
        val snap = Snapper.snap(10.4, -0.5, entities, tolerance = 2.0)
        assertEquals(Snap(10.0, 0.0, SnapKind.MIDPOINT, null), snap)
    }

    @Test
    fun entityPointBeatsACloserGridNode() {
        val shifted = listOf(SketchLine(0, 0.0, 0.0, 19.0, 0.0))
        // The touch is 0.2 from grid node (20, 0) and 0.8 from the endpoint (19, 0).
        val snap = Snapper.snap(19.8, 0.0, shifted, tolerance = 2.0, gridSpacing = 10.0)
        assertEquals(SnapKind.ENDPOINT, snap.kind)
        assertEquals(19.0, snap.x, 0.0)
    }

    @Test
    fun nearestOfTheSameKindWins() {
        val two = listOf(SketchLine(0, 0.0, 0.0, 10.0, 0.0), SketchLine(1, 11.0, 0.0, 30.0, 0.0))
        val snap = Snapper.snap(10.8, 0.0, two, tolerance = 2.0)
        assertEquals(PointRef(1, PointRole.START), snap.point)
    }

    @Test
    fun fallsBackToGridThenToTheRawPosition() {
        assertEquals(
            Snap(30.0, -10.0, SnapKind.GRID),
            Snapper.snap(30.6, -9.3, entities, tolerance = 2.0, gridSpacing = 10.0),
        )
        assertEquals(
            Snap(34.0, -6.0, SnapKind.NONE),
            Snapper.snap(34.0, -6.0, entities, tolerance = 2.0, gridSpacing = 10.0),
        )
        assertEquals(SnapKind.NONE, Snapper.snap(30.6, -9.3, entities, tolerance = 2.0).kind)
    }

    @Test
    fun excludedEntitiesDoNotAttract() {
        val snap = Snapper.snap(19.5, 0.0, entities, tolerance = 2.0, exclude = setOf(0))
        assertEquals(SnapKind.NONE, snap.kind)
    }

    @Test
    fun nothingBeyondToleranceAttracts() {
        assertEquals(SnapKind.NONE, Snapper.snap(23.0, 0.0, entities, tolerance = 2.0).kind)
    }
}

class SketchPlaneTest {
    private fun assertPoint(expected: DoubleArray, actual: DoubleArray?) =
        assertArrayEquals(expected, actual, 1e-9)

    @Test
    fun toWorldUsesThePlaneAxes() {
        assertPoint(doubleArrayOf(3.0, 4.0, 0.0), SketchPlane.XY.toWorld(3.0, 4.0))
        assertPoint(doubleArrayOf(3.0, 0.0, 4.0), SketchPlane.XZ.toWorld(3.0, 4.0))
        assertPoint(doubleArrayOf(0.0, 3.0, 4.0), SketchPlane.YZ.toWorld(3.0, 4.0))
    }

    @Test
    fun rayFromAboveHitsTheGroundPlane() {
        val hit = SketchPlane.XY.intersect(doubleArrayOf(5.0, 7.0, 100.0), doubleArrayOf(0.0, 0.0, -1.0))
        assertPoint(doubleArrayOf(5.0, 7.0), hit)
    }

    @Test
    fun obliqueRayIsProjectedAlongItsDirection() {
        // Starts 10 above the origin, descends at 45° towards +X.
        val hit = SketchPlane.XY.intersect(doubleArrayOf(0.0, 0.0, 10.0), doubleArrayOf(2.0, 0.0, -2.0))
        assertPoint(doubleArrayOf(10.0, 0.0), hit)
    }

    @Test
    fun intersectAndToWorldRoundTripOnEveryPlane() {
        for (plane in listOf(SketchPlane.XY, SketchPlane.XZ, SketchPlane.YZ)) {
            val world = plane.toWorld(12.5, -8.0)
            val n = plane.normal
            val origin = doubleArrayOf(world[0] + 30 * n[0], world[1] + 30 * n[1], world[2] + 30 * n[2])
            val direction = doubleArrayOf(-n[0], -n[1], -n[2])
            assertPoint(doubleArrayOf(12.5, -8.0), plane.intersect(origin, direction))
        }
    }

    @Test
    fun parallelOrBackwardRaysMiss() {
        assertNull(SketchPlane.XY.intersect(doubleArrayOf(0.0, 0.0, 5.0), doubleArrayOf(1.0, 0.0, 0.0)))
        assertNull(SketchPlane.XY.intersect(doubleArrayOf(0.0, 0.0, 5.0), doubleArrayOf(0.0, 0.0, 1.0)))
    }
}
