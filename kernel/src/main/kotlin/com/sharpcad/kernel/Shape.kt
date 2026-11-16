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
 */
class Mesh internal constructor(
    val positions: ByteBuffer,
    val normals: ByteBuffer,
    val indices: ByteBuffer,
    val edgeSegments: ByteBuffer,
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
        return Mesh(buffers[0], buffers[1], buffers[2], buffers[3])
    }

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
