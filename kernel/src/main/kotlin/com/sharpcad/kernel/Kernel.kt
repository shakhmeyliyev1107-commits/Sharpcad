package com.sharpcad.kernel

/**
 * Entry point to the geometry kernel.
 *
 * All calls block and may take a long time; call them from a worker dispatcher,
 * never from the main thread. Lengths are millimetres.
 */
object Kernel {
    /** Version of the underlying OpenCASCADE library, e.g. "7.9.3". */
    fun occtVersion(): String = KernelNative.nativeOcctVersion()

    /**
     * Creates an axis-aligned box with one corner at the origin.
     *
     * @throws KernelException if any dimension is not a positive finite number.
     */
    fun makeBox(dx: Double, dy: Double, dz: Double): Solid =
        Solid(KernelNative.nativeMakeBox(dx, dy, dz))
}
