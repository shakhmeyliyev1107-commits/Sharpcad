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
    @JvmStatic external fun nativeBoundingBox(handle: Long): DoubleArray

    /** Returns positions, normals, indices and edge segments, in that order. */
    @JvmStatic external fun nativeTessellate(
        handle: Long,
        linearDeflection: Double,
        angularDeflection: Double,
    ): Array<ByteBuffer>

    @JvmStatic external fun nativeRelease(handle: Long)
}
