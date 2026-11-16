package com.sharpcad.kernel

/** Failure reported by the native kernel. [code] is stable across releases. */
class KernelException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val INVALID_ARGUMENT = 1
        const val OCCT_FAILURE = 2
        const val INVALID_HANDLE = 3
        const val UNKNOWN = 99
    }
}
