package com.sharpcad.sketch

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the JNI boundary on the host JVM against a host build of libcadsketch. */
class SketchJniTest {

    private fun Sketch.line(id: Int) = entities().first { it.id == id } as SketchLine

    @Test
    fun dimensionedRectangleSolvesAcrossJni() {
        Sketch().use { s ->
            val bottom = s.addLine(0.0, 0.0, 9.0, 0.5)
            val right = s.addLine(9.2, 0.3, 9.6, 6.0)
            val top = s.addLine(9.9, 6.2, 0.2, 5.8)
            val left = s.addLine(-0.2, 6.1, 0.1, -0.3)
            s.addCoincident(PointRef(bottom, PointRole.END), PointRef(right, PointRole.START))
            s.addCoincident(PointRef(right, PointRole.END), PointRef(top, PointRole.START))
            s.addCoincident(PointRef(top, PointRole.END), PointRef(left, PointRole.START))
            s.addCoincident(PointRef(left, PointRole.END), PointRef(bottom, PointRole.START))
            s.addHorizontal(bottom); s.addHorizontal(top)
            s.addVertical(right); s.addVertical(left)
            s.addDistance(PointRef(bottom, PointRole.START), PointRef(bottom, PointRole.END), 40.0)
            s.addDistance(PointRef(right, PointRole.START), PointRef(right, PointRole.END), 25.0)
            s.setPoint(PointRef(bottom, PointRole.START), 0.0, 0.0)
            s.addFixed(PointRef(bottom, PointRole.START))

            val result = s.solve()
            assertEquals(SolveStatus.SOLVED, result.status)
            assertEquals(0, result.degreesOfFreedom)
            assertTrue(result.conflicting.isEmpty())
            val t = s.line(top)
            assertEquals(40.0, t.x1, 1e-6)
            assertEquals(25.0, t.y1, 1e-6)
            assertEquals(0.0, t.x2, 1e-6)
        }
    }

    @Test
    fun conflictAndRedundancyListsAreDecoded() {
        Sketch().use { s ->
            val line = s.addLine(0.0, 0.0, 10.0, 0.0)
            val a = s.addDistance(PointRef(line, PointRole.START), PointRef(line, PointRole.END), 10.0)
            val b = s.addDistance(PointRef(line, PointRole.START), PointRef(line, PointRole.END), 20.0)
            val conflict = s.solve()
            assertEquals(SolveStatus.CONFLICTING, conflict.status)
            assertTrue(conflict.conflicting.isNotEmpty() && conflict.conflicting.all { it == a || it == b })

            s.removeConstraint(b)
            s.addHorizontal(line)
            val duplicate = s.addHorizontal(line)
            val redundant = s.solve()
            assertEquals(SolveStatus.SOLVED, redundant.status)
            assertEquals(listOf(duplicate), redundant.redundant)
        }
    }

    @Test
    fun entitiesReportLinesAndCirclesInCreationOrder() {
        Sketch().use { s ->
            val line = s.addLine(1.0, 2.0, 3.0, 4.0)
            val circle = s.addCircle(5.0, 6.0, 7.0)
            assertEquals(
                listOf(SketchLine(line, 1.0, 2.0, 3.0, 4.0), SketchCircle(circle, 5.0, 6.0, 7.0)),
                s.entities(),
            )
            s.setCircleRadius(circle, 2.5)
            s.removeEntity(line)
            assertEquals(listOf(SketchCircle(circle, 5.0, 6.0, 2.5)), s.entities())
        }
    }

    @Test
    fun draggingRespectsLengthConstraint() {
        Sketch().use { s ->
            val line = s.addLine(0.0, 0.0, 10.0, 0.0)
            s.addFixed(PointRef(line, PointRole.START))
            s.addDistance(PointRef(line, PointRole.START), PointRef(line, PointRole.END), 10.0)
            assertEquals(SolveStatus.SOLVED, s.solveDragging(PointRef(line, PointRole.END), 30.0, 30.0).status)
            val l = s.line(line)
            assertEquals(10.0, hypot(l.x2 - l.x1, l.y2 - l.y1), 1e-6)
            assertEquals(l.x2, l.y2, 1e-3) // pulled towards the 45° target
        }
    }

    @Test
    fun invalidArgumentsBecomeSketchException() {
        Sketch().use { s ->
            val line = s.addLine(0.0, 0.0, 1.0, 1.0)
            val calls = listOf<() -> Unit>(
                { s.addLine(Double.NaN, 0.0, 1.0, 1.0) },
                { s.addCircle(0.0, 0.0, 0.0) },
                { s.addHorizontal(42) },
                { s.addRadius(line, 3.0) },
                { s.addCoincident(PointRef(line, PointRole.CENTER), PointRef(line, PointRole.END)) },
                { s.setDimension(0, 1.0) },
            )
            for (call in calls) {
                val e = assertThrows(SketchException::class.java) { call() }
                assertEquals(SketchException.INVALID_ARGUMENT, e.code)
            }
        }
    }

    @Test
    fun closedSketchRejectsCalls() {
        val s = Sketch()
        s.close()
        assertTrue(s.isClosed)
        val e = assertThrows(SketchException::class.java) { s.addLine(0.0, 0.0, 1.0, 1.0) }
        assertEquals(SketchException.INVALID_HANDLE, e.code)
        assertEquals(SketchException.INVALID_HANDLE, assertThrows(SketchException::class.java) { s.solve() }.code)
        s.close() // double close is a no-op
    }
}
