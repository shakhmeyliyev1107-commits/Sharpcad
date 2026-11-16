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
    fun makeBox(dx: Double, dy: Double, dz: Double): Shape =
        Shape(KernelNative.nativeMakeBox(dx, dy, dz))

    /**
     * Reads a STEP file from the local file system into a single shape.
     *
     * @throws KernelException with [KernelException.IO_ERROR] if the file is missing,
     *   is not valid STEP or contains no geometry.
     */
    fun importStep(path: String): Shape = Shape(KernelNative.nativeImportStep(path))
}
