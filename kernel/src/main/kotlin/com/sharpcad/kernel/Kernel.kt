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

    /** Reads an IGES file into a single shape. Same errors as [importStep]. */
    fun importIges(path: String): Shape = Shape(KernelNative.nativeImportIges(path))

    /**
     * Closed regions enclosed by sketch geometry on [plane], as planar faces.
     *
     * @param lines x1, y1, x2, y2 per line, in plane coordinates.
     * @param circles cx, cy, radius per circle.
     * @param arcs cx, cy, radius, start angle, end angle per arc (counter-clockwise, radians).
     * @throws KernelException with [KernelException.INVALID_ARGUMENT] if nothing is enclosed.
     */
    fun profileFromSketch(plane: PlaneFrame, lines: DoubleArray, circles: DoubleArray, arcs: DoubleArray = DoubleArray(0)): Shape =
        Shape(KernelNative.nativeProfileFromSketch(plane.origin, plane.xAxis, plane.yAxis, lines, circles, arcs))

    /** One shape holding all of [shapes] (for export). */
    fun compound(shapes: List<Shape>): Shape {
        val handles = shapes.map { it.nativeHandle() }.toLongArray()
        return Shape(KernelNative.nativeCompound(handles))
    }
}
