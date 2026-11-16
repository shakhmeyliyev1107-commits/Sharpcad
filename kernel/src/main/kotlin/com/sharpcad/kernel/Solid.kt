package com.sharpcad.kernel

/** Axis-aligned bounds in millimetres. */
data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
)

/**
 * A B-rep solid owned by the native kernel.
 *
 * Owns exactly one native handle; [close] releases it. Using a closed solid throws
 * [KernelException] with [KernelException.INVALID_HANDLE]. Not thread-safe.
 */
class Solid internal constructor(private var handle: Long) : AutoCloseable {

    val isClosed: Boolean get() = handle == 0L

    fun boundingBox(): BoundingBox {
        val v = KernelNative.nativeBoundingBox(handle)
        return BoundingBox(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            KernelNative.nativeRelease(h)
        }
    }
}
