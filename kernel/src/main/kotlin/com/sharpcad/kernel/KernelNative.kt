package com.sharpcad.kernel

/** JNI declarations only. Implemented in `jni/jni_kernel.cpp`. */
internal object KernelNative {
    init {
        System.loadLibrary("cadcore")
    }

    @JvmStatic external fun nativeOcctVersion(): String
    @JvmStatic external fun nativeMakeBox(dx: Double, dy: Double, dz: Double): Long
    @JvmStatic external fun nativeBoundingBox(handle: Long): DoubleArray
    @JvmStatic external fun nativeRelease(handle: Long)
}
