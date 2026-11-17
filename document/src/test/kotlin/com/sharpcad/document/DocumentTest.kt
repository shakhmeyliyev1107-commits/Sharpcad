package com.sharpcad.document

import com.sharpcad.kernel.BooleanOp
import kotlin.math.PI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        listOf(x0, y0, x1, y0, x1, y0, x1, y1, x1, y1, x0, y1, x0, y1, x0, y0)

    private fun box(id: Long, w: Double, d: Double, h: Double, operation: Operation = Operation.NEW_BODY, target: Long? = null) =
        ExtrudeFeature(id, Profile(PlaneSpec.XY, rect(0.0, 0.0, w, d), emptyList()), h, operation, target)

    private fun volumes(features: List<Feature>): Map<Long, Double> =
        ModelBuilder.build(features).use { r -> r.bodies.mapValues { it.value.volume } }

    @Test
    fun extrudeCreatesABodyAlongThePlaneNormal() {
        ModelBuilder.build(listOf(box(1, 10.0, 20.0, 5.0))).use { r ->
            assertEquals(1000.0, r.bodies.getValue(1).volume, 1e-6)
            assertEquals(5.0, r.bodies.getValue(1).boundingBox().maxZ, 1e-6)
        }
        // On XZ the normal is -Y: a positive distance extrudes towards -Y.
        val xz = ExtrudeFeature(2, Profile(PlaneSpec.XZ, rect(0.0, 0.0, 10.0, 10.0), emptyList()), 4.0)
        ModelBuilder.build(listOf(xz)).use { r -> assertEquals(-4.0, r.bodies.getValue(2).boundingBox().minY, 1e-6) }
    }

    @Test
    fun joinCutAndIntersectTargetTheNewestBodyByDefault() {
        val base = box(1, 10.0, 10.0, 10.0)
        val tool = { op: Operation -> box(2, 5.0, 5.0, 20.0, op) }
        assertEquals(mapOf(1L to 1250.0), volumes(listOf(base, tool(Operation.JOIN))).mapValues { Math.round(it.value).toDouble() })
        assertEquals(750.0, volumes(listOf(base, tool(Operation.CUT))).getValue(1), 1e-6)
        assertEquals(250.0, volumes(listOf(base, tool(Operation.INTERSECT))).getValue(1), 1e-6)
        assertEquals(2, volumes(listOf(base, tool(Operation.NEW_BODY))).size)
    }

    @Test
    fun autoOperationCutsJoinsOrCreates() {
        val base = box(1, 10.0, 10.0, 10.0)
        fun extrude(x0: Double, x1: Double, from: PlaneSpec, d: Double, symmetric: Boolean = false) =
            ExtrudeFeature(2, Profile(from, rect(x0, 2.0, x1, 8.0), emptyList()), d, Operation.AUTO, symmetric = symmetric)
        val top = PlaneSpec("top", listOf(0.0, 0.0, 10.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0))
        // Down from the top face, inside the box: a pocket.
        assertEquals(1000.0 - 6.0 * 6.0 * 4.0, volumes(listOf(base, extrude(2.0, 8.0, top, -4.0))).getValue(1), 1e-6)
        // Up from the top face, touching: joined.
        assertEquals(1000.0 + 6.0 * 6.0 * 4.0, volumes(listOf(base, extrude(2.0, 8.0, top, 4.0))).getValue(1), 1e-6)
        // Far away: a new body.
        assertEquals(2, volumes(listOf(base, extrude(30.0, 40.0, top, 4.0))).size)
        // Symmetric doubles the height around the profile.
        val free = ExtrudeFeature(1, Profile(PlaneSpec.XY, rect(0.0, 0.0, 10.0, 10.0), emptyList()), 3.0, symmetric = true)
        ModelBuilder.build(listOf(free)).use { r ->
            val b = r.bodies.getValue(1).boundingBox()
            assertEquals(-3.0, b.minZ, 1e-6)
            assertEquals(3.0, b.maxZ, 1e-6)
        }
    }

    @Test
    fun regionsSelectWhichPartsAreExtruded() {
        val profile = Profile(PlaneSpec.XY, rect(0.0, 0.0, 40.0, 40.0), listOf(20.0, 20.0, 5.0))
        val whole = volumes(listOf(ExtrudeFeature(1, profile, 10.0))).getValue(1)
        assertEquals(16000.0, whole, 1e-3)
        val ring = ModelBuilder.build(emptyList()).use { _ ->
            com.sharpcad.kernel.Kernel.profileFromSketch(PlaneSpec.XY.frame(), profile.lines.toDoubleArray(), profile.circles.toDoubleArray())
                .use { it.regionAt(PlaneSpec.XY.frame(), 1.0, 1.0) }
        }
        val withHole = volumes(listOf(ExtrudeFeature(1, profile.copy(regions = listOf(ring)), 10.0))).getValue(1)
        assertEquals((1600 - PI * 25) * 10, withHole, 1e-3)
    }

    @Test
    fun loftBetweenSquareAndOffsetSquare() {
        val top = PlaneSpec("XY+10", listOf(0.0, 0.0, 10.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0))
        val loft = LoftFeature(1, listOf(
            Profile(PlaneSpec.XY, rect(0.0, 0.0, 10.0, 10.0), emptyList()),
            Profile(top, rect(0.0, 0.0, 10.0, 10.0), emptyList()),
        ))
        assertEquals(1000.0, volumes(listOf(loft)).getValue(1), 1e-3)
    }

    @Test
    fun revolveFilletChamferPushPullAndBoolean() {
        val revolve = RevolveFeature(1, Profile(PlaneSpec.XY, rect(10.0, 0.0, 20.0, 5.0), emptyList()), RevolveAxis.V, 360.0)
        assertEquals(PI * 300 * 5, volumes(listOf(revolve)).getValue(1), 1e-3)

        val base = box(1, 20.0, 20.0, 20.0)
        assertTrue(volumes(listOf(base, FilletFeature(2, body = 1, radius = 2.0))).getValue(1) < 8000.0)
        assertTrue(volumes(listOf(base, ChamferFeature(2, body = 1, distance = 2.0))).getValue(1) < 8000.0)

        val top = ModelBuilder.build(listOf(base)).use { r ->
            r.bodies.getValue(1).pick(doubleArrayOf(5.0, 5.0, 99.0), doubleArrayOf(0.0, 0.0, -1.0))!!.face
        }
        assertEquals(10000.0, volumes(listOf(base, PushPullFeature(2, 1, top, 5.0))).getValue(1), 1e-6)

        val other = box(2, 10.0, 10.0, 40.0)
        val subtracted = volumes(listOf(base, other, BooleanFeature(3, target = 1, tool = 2, op = BooleanOp.SUBTRACT)))
        assertEquals(setOf(1L), subtracted.keys) // the tool body is consumed
        assertEquals(8000.0 - 2000.0, subtracted.getValue(1), 1e-6)
    }

    @Test
    fun failingFeatureIsReportedAndSkipped() {
        val features = listOf(box(1, 10.0, 10.0, 10.0), FilletFeature(2, 1, radius = 50.0), box(3, 1.0, 1.0, 1.0))
        ModelBuilder.build(features).use { r ->
            assertTrue(r.errors.containsKey(2))
            assertEquals(1000.0, r.bodies.getValue(1).volume, 1e-6) // unchanged by the failed fillet
            assertEquals(2, r.bodies.size)
        }
        val missingBody = listOf(FilletFeature(1, body = 42, radius = 1.0))
        ModelBuilder.build(missingBody).use { assertTrue(it.errors.containsKey(1)) }
        val joinWithoutBody = listOf(box(1, 1.0, 1.0, 1.0, Operation.JOIN))
        ModelBuilder.build(joinWithoutBody).use { assertTrue(it.errors.containsKey(1)) }
    }

    @Test
    fun documentUndoRedoAndParameterEdits() = runBlocking<Unit> {
        Document().use { doc ->
            doc.add(box(doc.newId(), 10.0, 10.0, 10.0))
            val first = doc.state.value
            assertEquals(1, first.bodies.size)
            assertTrue(first.canUndo)
            assertFalse(first.canRedo)
            val id = first.features.single().id

            doc.update(box(id, 10.0, 10.0, 30.0))
            assertEquals(30.0, doc.state.value.bodies.single().bounds[5], 1e-6)

            doc.undo()
            assertEquals(10.0, doc.state.value.bodies.single().bounds[5], 1e-6)
            assertTrue(doc.state.value.canRedo)
            doc.redo()
            assertEquals(30.0, doc.state.value.bodies.single().bounds[5], 1e-6)

            doc.remove(id)
            assertTrue(doc.state.value.bodies.isEmpty())
            doc.undo()
            assertEquals(1, doc.state.value.bodies.size)

            // A new edit after undo discards the redo branch.
            doc.undo()
            doc.add(box(doc.newId(), 1.0, 1.0, 1.0))
            assertFalse(doc.state.value.canRedo)
        }
    }

    @Test
    fun documentPicksAcrossBodiesAndIdsStayUnique() = runBlocking<Unit> {
        Document().use { doc ->
            doc.reset(listOf(box(7, 10.0, 10.0, 10.0)))
            assertEquals(8L, doc.newId()) // ids continue after those in the history
            doc.add(ExtrudeFeature(9, Profile(PlaneSpec.XY, rect(20.0, 0.0, 30.0, 10.0), emptyList()), 50.0))
            val hit = doc.pick(doubleArrayOf(25.0, 5.0, 100.0), doubleArrayOf(0.0, 0.0, -1.0))
            assertNotNull(hit)
            assertEquals(9L, hit!!.bodyId)
            assertEquals(50.0, hit.distance, 1e-6)
            assertNull(doc.pick(doubleArrayOf(100.0, 100.0, 100.0), doubleArrayOf(0.0, 0.0, -1.0)))
        }
    }

    @Test
    fun profileRegionsAndRegionLookup() = runBlocking<Unit> {
        Document().use { doc ->
            val regions = doc.profileRegions(PlaneSpec.XY, rect(0.0, 0.0, 40.0, 40.0), listOf(20.0, 20.0, 5.0))
            assertNotNull(regions)
            assertEquals(2, regions!!.count)
            assertTrue(regions.mesh.triangleCount > 0)
            assertTrue(doc.regionAt(PlaneSpec.XY, rect(0.0, 0.0, 40.0, 40.0), listOf(20.0, 20.0, 5.0), 20.0, 20.0) >= 0)
            assertNull(doc.profileRegions(PlaneSpec.XY, listOf(0.0, 0.0, 1.0, 0.0), emptyList()))
        }
    }

    @Test
    fun exportWritesAllBodies() = runBlocking<Unit> {
        Document().use { doc ->
            val file = java.io.File.createTempFile("sharpcad", ".step")
            assertFalse(doc.exportStep(file.path))
            doc.reset(listOf(box(1, 1.0, 1.0, 1.0), ExtrudeFeature(2, Profile(PlaneSpec.XY, rect(5.0, 0.0, 6.0, 1.0), emptyList()), 1.0)))
            assertTrue(doc.exportStep(file.path))
            doc.reset(listOf(ImportFeature(3, file.path, "export")))
            assertEquals(2.0, ModelBuilder.build(doc.state.value.features).use { it.bodies.values.single().volume }, 1e-6)
            file.delete()
        }
    }

    @Test
    fun moveMirrorAndPatternCreateOrMoveBodies() {
        val base = box(1, 10.0, 10.0, 10.0)
        ModelBuilder.build(listOf(base, MoveFeature(2, 1, dx = 50.0))).use { r ->
            assertEquals(setOf(1L), r.bodies.keys)
            assertEquals(50.0, r.bodies.getValue(1).boundingBox().minX, 1e-6)
        }
        ModelBuilder.build(listOf(base, MoveFeature(2, 1, dz = 5.0, copy = true))).use { r -> assertEquals(setOf(1L, 2L), r.bodies.keys) }
        ModelBuilder.build(listOf(base, MirrorFeature(2, 1, WorldAxis.X))).use { r ->
            assertEquals(-10.0, r.bodies.getValue(2).boundingBox().minX, 1e-6)
            assertEquals(2, r.bodies.size)
        }
        ModelBuilder.build(listOf(base, PatternFeature(2, 1, WorldAxis.Y, count = 4, spacing = 15.0))).use { r ->
            assertEquals(4, r.bodies.size)
            assertEquals(45.0, r.bodies.getValue(2003).boundingBox().minY, 1e-6)
        }
        val ring = PatternFeature(2, 1, WorldAxis.Z, count = 4, circular = true)
        ModelBuilder.build(listOf(MoveFeature(9, 1, dx = 20.0).let { listOf(base, it) }, listOf(ring)).flatten()).use { r ->
            assertEquals(4, r.bodies.size)
            assertEquals(-30.0, r.bodies.getValue(2002).boundingBox().minX, 1e-6) // half a turn around Z
        }
        ModelBuilder.build(listOf(base, PatternFeature(2, 1, count = 1))).use { assertTrue(it.errors.containsKey(2)) }
    }
}
