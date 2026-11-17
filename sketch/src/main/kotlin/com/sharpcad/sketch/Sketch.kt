package com.sharpcad.sketch

/** Failure reported by the native sketch engine. [code] is stable across releases. */
class SketchException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val INVALID_ARGUMENT = 1
        const val SOLVER_FAILURE = 2
        const val INVALID_HANDLE = 3
        const val UNKNOWN = 99
    }
}

/** Which point of an entity is meant. Lines have START and END, circles have CENTER. */
enum class PointRole(internal val native: Int) { START(0), END(1), CENTER(2) }

/** A point of a sketch entity, used to attach constraints. */
data class PointRef(val entity: Int, val role: PointRole)

/** Geometry of one sketch entity, in sketch-plane millimetres. */
sealed interface SketchEntity {
    val id: Int
}

data class SketchLine(
    override val id: Int,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
) : SketchEntity

/** Counter-clockwise arc from [startAngle] to [endAngle] (radians) around ([cx], [cy]). */
data class SketchArc(
    override val id: Int,
    val cx: Double,
    val cy: Double,
    val radius: Double,
    val startAngle: Double,
    val endAngle: Double,
) : SketchEntity {
    val startX: Double get() = cx + radius * kotlin.math.cos(startAngle)
    val startY: Double get() = cy + radius * kotlin.math.sin(startAngle)
    val endX: Double get() = cx + radius * kotlin.math.cos(endAngle)
    val endY: Double get() = cy + radius * kotlin.math.sin(endAngle)
}

data class SketchCircle(
    override val id: Int,
    val cx: Double,
    val cy: Double,
    val radius: Double,
) : SketchEntity

enum class SolveStatus {
    /** All constraints satisfied; geometry updated. */
    SOLVED,

    /** Constraints contradict each other; geometry unchanged. */
    CONFLICTING,

    /** The solver did not converge; geometry unchanged. */
    FAILED,
}

/**
 * Outcome of solving a sketch.
 *
 * @property degreesOfFreedom how many independent ways the sketch can still move; 0 means
 *   fully constrained.
 * @property conflicting ids of constraints that contradict others.
 * @property redundant ids of constraints that add nothing.
 */
data class SolveResult(
    val status: SolveStatus,
    val degreesOfFreedom: Int,
    val conflicting: List<Int>,
    val redundant: List<Int>,
)

/**
 * A 2D sketch: lines and circles tied together by constraints.
 *
 * Owns one native handle; [close] releases it. Entity and constraint ids are never reused.
 * Not thread-safe. Solving a sketch of a few hundred entities takes milliseconds, but
 * larger ones should be solved off the main thread.
 *
 * @throws SketchException from any method for invalid ids, non-finite numbers or
 *   non-positive sizes ([SketchException.INVALID_ARGUMENT]), or use after [close].
 */
class Sketch : AutoCloseable {
    private var handle: Long = SketchNative.nativeCreate()

    val isClosed: Boolean get() = handle == 0L

    fun addLine(x1: Double, y1: Double, x2: Double, y2: Double): Int =
        SketchNative.nativeAddLine(handle, x1, y1, x2, y2)

    fun addCircle(cx: Double, cy: Double, radius: Double): Int =
        SketchNative.nativeAddCircle(handle, cx, cy, radius)

    fun addArc(cx: Double, cy: Double, radius: Double, startAngle: Double, endAngle: Double): Int =
        SketchNative.nativeAddArc(handle, cx, cy, radius, startAngle, endAngle)

    /** Moves an arc without solving (used while rubber-banding a new arc). */
    fun setArc(arc: Int, cx: Double, cy: Double, radius: Double, startAngle: Double, endAngle: Double) =
        SketchNative.nativeSetArc(handle, arc, cx, cy, radius, startAngle, endAngle)

    /** Removes an entity and every constraint that refers to it. */
    fun removeEntity(entity: Int) = SketchNative.nativeRemoveEntity(handle, entity)

    fun addCoincident(a: PointRef, b: PointRef): Int =
        SketchNative.nativeAddCoincident(handle, a.entity, a.role.native, b.entity, b.role.native)

    fun addHorizontal(line: Int): Int = SketchNative.nativeAddHorizontal(handle, line)

    fun addVertical(line: Int): Int = SketchNative.nativeAddVertical(handle, line)

    fun addEqualLength(lineA: Int, lineB: Int): Int = SketchNative.nativeAddLinePair(handle, 0, lineA, lineB)

    fun addParallel(lineA: Int, lineB: Int): Int = SketchNative.nativeAddLinePair(handle, 1, lineA, lineB)

    fun addPerpendicular(lineA: Int, lineB: Int): Int = SketchNative.nativeAddLinePair(handle, 2, lineA, lineB)

    /** Distance between two points. On the two ends of a line this is its length. */
    fun addDistance(a: PointRef, b: PointRef, distance: Double): Int =
        SketchNative.nativeAddDistance(handle, a.entity, a.role.native, b.entity, b.role.native, distance)

    fun addRadius(circle: Int, radius: Double): Int = SketchNative.nativeAddRadius(handle, circle, radius)

    /** Pins a point at its current position. */
    fun addFixed(point: PointRef): Int =
        SketchNative.nativeAddFixed(handle, point.entity, point.role.native)

    fun removeConstraint(constraint: Int) = SketchNative.nativeRemoveConstraint(handle, constraint)

    /** Changes the value of a distance or radius constraint. */
    fun setDimension(constraint: Int, value: Double) =
        SketchNative.nativeSetDimension(handle, constraint, value)

    /** Moves a point without solving (used while rubber-banding a new entity). */
    fun setPoint(point: PointRef, x: Double, y: Double) =
        SketchNative.nativeSetPoint(handle, point.entity, point.role.native, x, y)

    fun setCircleRadius(circle: Int, radius: Double) =
        SketchNative.nativeSetCircleRadius(handle, circle, radius)

    /** Solves all constraints starting from the current geometry. */
    fun solve(): SolveResult = decode(SketchNative.nativeSolve(handle))

    /** Solves while pulling [point] towards ([x], [y]) as far as the constraints allow. */
    fun solveDragging(point: PointRef, x: Double, y: Double): SolveResult =
        decode(SketchNative.nativeSolveDragging(handle, point.entity, point.role.native, x, y))

    /** Geometry of all live entities, in creation order. */
    fun entities(): List<SketchEntity> {
        val raw = SketchNative.nativeGeometry(handle)
        return List(raw.size / FIELDS) { i ->
            val o = i * FIELDS
            val id = raw[o].toInt()
            when (raw[o + 1].toInt()) {
                KIND_LINE -> SketchLine(id, raw[o + 2], raw[o + 3], raw[o + 4], raw[o + 5])
                KIND_ARC -> SketchArc(id, raw[o + 2], raw[o + 3], raw[o + 4], raw[o + 5], raw[o + 6])
                else -> SketchCircle(id, raw[o + 2], raw[o + 3], raw[o + 4])
            }
        }
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            SketchNative.nativeRelease(h)
        }
    }

    private fun decode(raw: IntArray): SolveResult {
        val conflictingCount = raw[2]
        val conflicting = raw.slice(3 until 3 + conflictingCount)
        val redundantStart = 3 + conflictingCount + 1
        val redundant = raw.slice(redundantStart until redundantStart + raw[redundantStart - 1])
        return SolveResult(SolveStatus.entries[raw[0]], raw[1], conflicting, redundant)
    }

    private companion object {
        const val FIELDS = 7
        const val KIND_LINE = 0
        const val KIND_ARC = 2
    }
}

/** JNI declarations only. Implemented in `jni/jni_sketch.cpp`. */
internal object SketchNative {
    init {
        System.loadLibrary("cadsketch")
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeRelease(handle: Long)
    @JvmStatic external fun nativeAddLine(handle: Long, x1: Double, y1: Double, x2: Double, y2: Double): Int
    @JvmStatic external fun nativeAddCircle(handle: Long, cx: Double, cy: Double, radius: Double): Int
    @JvmStatic external fun nativeAddArc(handle: Long, cx: Double, cy: Double, r: Double, a0: Double, a1: Double): Int
    @JvmStatic external fun nativeSetArc(handle: Long, arc: Int, cx: Double, cy: Double, r: Double, a0: Double, a1: Double)
    @JvmStatic external fun nativeRemoveEntity(handle: Long, entity: Int)
    @JvmStatic external fun nativeAddCoincident(handle: Long, entityA: Int, roleA: Int, entityB: Int, roleB: Int): Int
    @JvmStatic external fun nativeAddHorizontal(handle: Long, line: Int): Int
    @JvmStatic external fun nativeAddVertical(handle: Long, line: Int): Int
    @JvmStatic external fun nativeAddLinePair(handle: Long, kind: Int, a: Int, b: Int): Int
    @JvmStatic external fun nativeAddDistance(handle: Long, entityA: Int, roleA: Int, entityB: Int, roleB: Int, distance: Double): Int
    @JvmStatic external fun nativeAddRadius(handle: Long, circle: Int, radius: Double): Int
    @JvmStatic external fun nativeAddFixed(handle: Long, entity: Int, role: Int): Int
    @JvmStatic external fun nativeRemoveConstraint(handle: Long, constraint: Int)
    @JvmStatic external fun nativeSetDimension(handle: Long, constraint: Int, value: Double)
    @JvmStatic external fun nativeSetPoint(handle: Long, entity: Int, role: Int, x: Double, y: Double)
    @JvmStatic external fun nativeSetCircleRadius(handle: Long, circle: Int, radius: Double)
    @JvmStatic external fun nativeSolve(handle: Long): IntArray
    @JvmStatic external fun nativeSolveDragging(handle: Long, entity: Int, role: Int, x: Double, y: Double): IntArray
    @JvmStatic external fun nativeGeometry(handle: Long): DoubleArray
}
