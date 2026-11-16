// JNI marshalling for com.sharpcad.kernel.KernelNative.
// No kernel logic here: convert arguments, call cad::kernel, translate exceptions.

#include <jni.h>

#include <cstring>
#include <exception>
#include <string>

#include "cad/kernel/error.h"
#include "cad/kernel/shape.h"

using cad::kernel::ErrorCode;
using cad::kernel::KernelError;
using cad::kernel::Shape;

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

// Thrown when a JNI call left a Java exception pending; guard() lets it propagate.
struct JavaExceptionPending {};

// Runs fn; on any C++ exception raises KernelException and returns fallback.
// Nothing may unwind into the JVM.
template <typename T, typename Fn>
T guard(JNIEnv* env, T fallback, Fn&& fn) noexcept {
    try {
        return fn();
    } catch (const JavaExceptionPending&) {
        // already pending in the JVM
    } catch (const KernelError& e) {
        throwKernelException(env, e.code(), e.what());
    } catch (const std::exception& e) {
        throwKernelException(env, ErrorCode::Unknown, e.what());
    } catch (...) {
        throwKernelException(env, ErrorCode::Unknown, "Unknown native error");
    }
    return fallback;
}

Shape& shapeFromHandle(jlong handle) {
    if (handle == 0) {
        throw KernelError(ErrorCode::InvalidHandle, "Shape has been closed");
    }
    return *reinterpret_cast<Shape*>(handle);
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (!value) throw KernelError(ErrorCode::InvalidArgument, "Path must not be null");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw JavaExceptionPending{};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Copies `bytes` bytes into a new JVM-owned direct ByteBuffer, so the mesh outlives
// the native vectors and is freed by the garbage collector.
jobject toDirectBuffer(JNIEnv* env, const void* data, size_t bytes) {
    jclass cls = env->FindClass("java/nio/ByteBuffer");
    if (!cls) throw JavaExceptionPending{};
    jmethodID allocate = env->GetStaticMethodID(cls, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    if (!allocate) throw JavaExceptionPending{};
    if (bytes > static_cast<size_t>(INT32_MAX)) {
        throw KernelError(ErrorCode::OcctFailure, "Mesh is too large for a single buffer");
    }
    jobject buffer = env->CallStaticObjectMethod(cls, allocate, static_cast<jint>(bytes));
    if (env->ExceptionCheck() || !buffer) throw JavaExceptionPending{};
    if (bytes > 0) std::memcpy(env->GetDirectBufferAddress(buffer), data, bytes);
    return buffer;
}

template <typename T>
jobject toDirectBuffer(JNIEnv* env, const std::vector<T>& values) {
    return toDirectBuffer(env, values.data(), values.size() * sizeof(T));
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
        return reinterpret_cast<jlong>(Shape::makeBox(dx, dy, dz).release());
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeImportStep(JNIEnv* env, jclass, jstring path) {
    return guard<jlong>(env, 0, [&] {
        return reinterpret_cast<jlong>(Shape::importStep(toStdString(env, path)).release());
    });
}

JNIEXPORT void JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeExportStep(JNIEnv* env, jclass, jlong handle,
                                                       jstring path) {
    guard<int>(env, 0, [&] {
        shapeFromHandle(handle).exportStep(toStdString(env, path));
        return 0;
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeBoundingBox(JNIEnv* env, jclass, jlong handle) {
    return guard<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        const auto box = shapeFromHandle(handle).boundingBox();
        const jdouble values[6] = {box.min[0], box.min[1], box.min[2],
                                   box.max[0], box.max[1], box.max[2]};
        jdoubleArray out = env->NewDoubleArray(6);
        if (out) env->SetDoubleArrayRegion(out, 0, 6, values);
        return out;
    });
}

// Returns {positions, normals, indices, edgeSegments} as direct ByteBuffers.
JNIEXPORT jobjectArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeTessellate(JNIEnv* env, jclass, jlong handle,
                                                       jdouble linearDeflection,
                                                       jdouble angularDeflection) {
    return guard<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        const auto mesh = shapeFromHandle(handle).tessellate(linearDeflection, angularDeflection);
        jclass cls = env->FindClass("java/nio/ByteBuffer");
        if (!cls) throw JavaExceptionPending{};
        jobjectArray out = env->NewObjectArray(4, cls, nullptr);
        if (!out) throw JavaExceptionPending{};
        env->SetObjectArrayElement(out, 0, toDirectBuffer(env, mesh.positions));
        env->SetObjectArrayElement(out, 1, toDirectBuffer(env, mesh.normals));
        env->SetObjectArrayElement(out, 2, toDirectBuffer(env, mesh.indices));
        env->SetObjectArrayElement(out, 3, toDirectBuffer(env, mesh.edgeSegments));
        return out;
    });
}

JNIEXPORT void JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Shape*>(handle);
}

}  // extern "C"
