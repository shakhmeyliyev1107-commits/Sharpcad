// JNI marshalling for com.sharpcad.kernel.KernelNative.
// No kernel logic here: convert arguments, call cad::kernel, translate exceptions.

#include <jni.h>

#include <exception>
#include <string>

#include "cad/kernel/error.h"
#include "cad/kernel/solid.h"

using cad::kernel::ErrorCode;
using cad::kernel::KernelError;
using cad::kernel::Solid;

namespace {

void throwKernelException(JNIEnv* env, ErrorCode code, const std::string& message) {
    if (env->ExceptionCheck()) return;  // keep the first pending exception
    jclass cls = env->FindClass("com/sharpcad/kernel/KernelException");
    if (!cls) return;  // NoClassDefFoundError is already pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return;
    jstring jmsg = env->NewStringUTF(message.c_str());
    if (!jmsg) return;
    auto ex = static_cast<jthrowable>(env->NewObject(cls, ctor, static_cast<jint>(code), jmsg));
    if (ex) env->Throw(ex);
}

// Runs fn; on any C++ exception raises KernelException and returns fallback.
// Nothing may unwind into the JVM.
template <typename T, typename Fn>
T guard(JNIEnv* env, T fallback, Fn&& fn) noexcept {
    try {
        return fn();
    } catch (const KernelError& e) {
        throwKernelException(env, e.code(), e.what());
    } catch (const std::exception& e) {
        throwKernelException(env, ErrorCode::Unknown, e.what());
    } catch (...) {
        throwKernelException(env, ErrorCode::Unknown, "Unknown native error");
    }
    return fallback;
}

Solid& solidFromHandle(jlong handle) {
    if (handle == 0) {
        throw KernelError(ErrorCode::InvalidHandle, "Solid has been closed");
    }
    return *reinterpret_cast<Solid*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeOcctVersion(JNIEnv* env, jclass) {
    return guard<jstring>(env, nullptr, [&] {
        return env->NewStringUTF(cad::kernel::occtVersion().c_str());
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeMakeBox(JNIEnv* env, jclass, jdouble dx, jdouble dy,
                                                    jdouble dz) {
    return guard<jlong>(env, 0, [&] {
        return reinterpret_cast<jlong>(Solid::makeBox(dx, dy, dz).release());
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeBoundingBox(JNIEnv* env, jclass, jlong handle) {
    return guard<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        const auto box = solidFromHandle(handle).boundingBox();
        const jdouble values[6] = {box.min[0], box.min[1], box.min[2],
                                   box.max[0], box.max[1], box.max[2]};
        jdoubleArray out = env->NewDoubleArray(6);
        if (out) env->SetDoubleArrayRegion(out, 0, 6, values);
        return out;
    });
}

JNIEXPORT void JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Solid*>(handle);
}

}  // extern "C"
