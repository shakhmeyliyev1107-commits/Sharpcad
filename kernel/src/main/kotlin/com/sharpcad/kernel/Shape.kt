package com.sharpcad.kernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Axis-aligned bounds in millimetres. */
data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    /** Length of the box diagonal. */
    val diagonal: Double
        get() {
            val dx = maxX - minX
            val dy = maxY - minY
            val dz = maxZ - minZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
}

/**
 * Triangle mesh of a [Shape], in direct buffers with native byte order.
 *
 * @property positions xyz floats, one triple per vertex.
 * @property normals unit xyz floats, one triple per vertex.
 * @property indices unsigned 32-bit ints, three per triangle, counter-clockwise from outside.
 * @property edgeSegments xyz floats, two points per line segment along the shape's edges.
 * @property triangleFaces 32-bit ints, the face index (see [Shape.faceCount]) of each triangle.
 */
class Mesh internal constructor(
    val positions: ByteBuffer,
    val normals: ByteBuffer,
    val indices: ByteBuffer,
    val edgeSegments: ByteBuffer,
    val triangleFaces: ByteBuffer,
) {
    val vertexCount: Int get() = positions.capacity() / (3 * Float.SIZE_BYTES)
    val triangleCount: Int get() = indices.capacity() / (3 * Int.SIZE_BYTES)
    val edgeSegmentCount: Int get() = edgeSegments.capacity() / (6 * Float.SIZE_BYTES)
}

/**
 * A B-rep shape owned by the native kernel: a solid, a shell or a compound of them.
 *
 * Owns exactly one native handle; [close] releases it. Using a closed shape throws
 * [KernelException] with [KernelException.INVALID_HANDLE]. Not thread-safe.
 */
class Shape internal constructor(private var handle: Long) : AutoCloseable {

    val isClosed: Boolean get() = handle == 0L

    internal fun nativeHandle(): Long = handle

    /** Tight bounds of the exact geometry. */
    fun boundingBox(): BoundingBox {
        val v = KernelNative.nativeBoundingBox(handle)
        return BoundingBox(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    /**
     * Triangulates the shape. Blocking and CPU-heavy: call from a worker thread.
     *
     * @param linearDeflection maximum chord error in mm.
     * @param angularDeflection maximum angle between adjacent facets, in radians.
     * @throws KernelException with [KernelException.INVALID_ARGUMENT] for non-positive values.
     */
    fun tessellate(
        linearDeflection: Double,
        angularDeflection: Double = DEFAULT_ANGULAR_DEFLECTION,
    ): Mesh {
        val buffers = KernelNative.nativeTessellate(handle, linearDeflection, angularDeflection)
        buffers.forEach { it.order(ByteOrder.nativeOrder()) }
        return Mesh(buffers[0], buffers[1], buffers[2], buffers[3], buffers[4])
    }

    /** Number of faces; face indices used by other calls run from 0 to this minus one. */
    val faceCount: Int get() = KernelNative.nativeFaceCount(handle)

    /** Enclosed volume in cubic millimetres (0 for faces only). */
    val volume: Double get() = KernelNative.nativeVolume(handle)

    /** Index of the profile region containing plane point ([u], [v]), or -1. Profiles only. */
    fun regionAt(plane: PlaneFrame, u: Double, v: Double): Int =
        KernelNative.nativeRegionAt(handle, plane.origin, plane.xAxis, plane.yAxis, u, v)

    /** The given faces of this shape as a new shape (pick profile regions to extrude). */
    fun subset(faces: List<Int>): Shape = Shape(KernelNative.nativeSubset(handle, faces.toIntArray()))

    /** Sweeps faces along ([dx], [dy], [dz]) millimetres. */
    fun extrude(dx: Double, dy: Double, dz: Double): Shape = Shape(KernelNative.nativeExtrude(handle, dx, dy, dz))

    /** Revolves faces by [angle] radians, in (0, 2π], around an axis. */
    fun revolve(axisOrigin: DoubleArray, axisDirection: DoubleArray, angle: Double): Shape =
        Shape(KernelNative.nativeRevolve(handle, axisOrigin, axisDirection, angle))

    /** Smallest distance to [other] in millimetres; 0 when they touch or overlap. */
    fun distanceTo(other: Shape): Double = KernelNative.nativeDistance(handle, other.handle)

    fun booleanWith(tool: Shape, op: BooleanOp): Shape =
        Shape(KernelNative.nativeBoolean(handle, tool.handle, op.ordinal))

    /** Rounds the edges of [face], or all edges when [face] is -1. */
    fun fillet(radius: Double, face: Int = -1): Shape = Shape(KernelNative.nativeFillet(handle, radius, face))

    /** Bevels the edges of [face], or all edges when [face] is -1. */
    fun chamfer(distance: Double, face: Int = -1): Shape = Shape(KernelNative.nativeChamfer(handle, distance, face))

    /** Moves a flat face along its outward normal; negative [distance] cuts into the body. */
    fun pushPull(face: Int, distance: Double): Shape = Shape(KernelNative.nativePushPull(handle, face, distance))

    /** Nearest face hit by a ray, or `null` when it misses. */
    fun pick(origin: DoubleArray, direction: DoubleArray): PickHit? {
        val raw = KernelNative.nativePick(handle, origin, direction)
        val face = raw[0].toInt()
        return if (face < 0) null else PickHit(face, raw[1])
    }

    /** Writes the shape as an IGES file (millimetres). */
    fun exportIges(path: String) = KernelNative.nativeExportIges(handle, path)

    /** Writes the shape as a STEP file. */
    fun exportStep(path: String) = KernelNative.nativeExportStep(handle, path)

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            KernelNative.nativeRelease(h)
        }
    }

    companion object {
        /** 20 degrees: smooth-looking cylinders without excessive triangles. */
        const val DEFAULT_ANGULAR_DEFLECTION = 0.35
    }
}

enum class BooleanOp { UNION, SUBTRACT, INTERSECT }

/** A face hit by a ray, [distance] millimetres along it. */
data class PickHit(val face: Int, val distance: Double)

/**
 * A plane with a 2D coordinate system, in model millimetres: points are
 * `origin + u * xAxis + v * yAxis`. The axes must be unit length and perpendicular.
 */
class PlaneFrame(val origin: DoubleArray, val xAxis: DoubleArray, val yAxis: DoubleArray) {
    init {
        require(origin.size == 3 && xAxis.size == 3 && yAxis.size == 3) { "Plane vectors need three coordinates" }
    }

    /** Unit normal, xAxis × yAxis. */
    val normal: DoubleArray
        get() = doubleArrayOf(
            xAxis[1] * yAxis[2] - xAxis[2] * yAxis[1],
            xAxis[2] * yAxis[0] - xAxis[0] * yAxis[2],
            xAxis[0] * yAxis[1] - xAxis[1] * yAxis[0],
        )
}
