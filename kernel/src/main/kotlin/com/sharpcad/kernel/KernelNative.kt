package com.sharpcad.kernel

import java.nio.ByteBuffer

/** JNI declarations only. Implemented in `jni/jni_kernel.cpp`. */
internal object KernelNative {
    init {
        System.loadLibrary("cadcore")
    }

    @JvmStatic external fun nativeOcctVersion(): String
    @JvmStatic external fun nativeMakeBox(dx: Double, dy: Double, dz: Double): Long
    @JvmStatic external fun nativeImportStep(path: String): Long
    @JvmStatic external fun nativeExportStep(handle: Long, path: String)
    @JvmStatic external fun nativeImportIges(path: String): Long
    @JvmStatic external fun nativeExportIges(handle: Long, path: String)
    @JvmStatic external fun nativeBoundingBox(handle: Long): DoubleArray

    /** Returns positions, normals, indices, edge segments and triangle faces, in that order. */
    @JvmStatic external fun nativeTessellate(
        handle: Long,
        linearDeflection: Double,
        angularDeflection: Double,
    ): Array<ByteBuffer>

    @JvmStatic external fun nativeRelease(handle: Long)

    @JvmStatic external fun nativeProfileFromSketch(
        origin: DoubleArray, xAxis: DoubleArray, yAxis: DoubleArray, lines: DoubleArray, circles: DoubleArray,
    ): Long
    @JvmStatic external fun nativeRegionAt(
        handle: Long, origin: DoubleArray, xAxis: DoubleArray, yAxis: DoubleArray, u: Double, v: Double,
    ): Int
    @JvmStatic external fun nativeSubset(handle: Long, faces: IntArray): Long
    @JvmStatic external fun nativeExtrude(handle: Long, dx: Double, dy: Double, dz: Double): Long
    @JvmStatic external fun nativeRevolve(handle: Long, axisOrigin: DoubleArray, axisDirection: DoubleArray, angle: Double): Long
    @JvmStatic external fun nativeBoolean(handle: Long, tool: Long, op: Int): Long
    @JvmStatic external fun nativeFillet(handle: Long, radius: Double, face: Int): Long
    @JvmStatic external fun nativeChamfer(handle: Long, distance: Double, face: Int): Long
    @JvmStatic external fun nativePushPull(handle: Long, face: Int, distance: Double): Long
    @JvmStatic external fun nativeCompound(handles: LongArray): Long
    @JvmStatic external fun nativeDistance(handle: Long, other: Long): Double
    @JvmStatic external fun nativeProject(handle: Long, toViewer: DoubleArray, up: DoubleArray, deflection: Double): Array<FloatArray>
    @JvmStatic external fun nativeShell(handle: Long, face: Int, thickness: Double): Long
    @JvmStatic external fun nativeFaceArea(handle: Long, face: Int): Double
    @JvmStatic external fun nativeFaceCount(handle: Long): Int
    @JvmStatic external fun nativeVolume(handle: Long): Double
    @JvmStatic external fun nativePick(handle: Long, origin: DoubleArray, direction: DoubleArray): DoubleArray
}
