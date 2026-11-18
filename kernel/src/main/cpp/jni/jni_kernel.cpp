// JNI marshalling for com.sharpcad.kernel.KernelNative.
// No kernel logic here: convert arguments, call cad::kernel, translate exceptions.

#include <jni.h>

#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "cad/kernel/error.h"
#include "cad/kernel/shape.h"

using cad::kernel::ErrorCode;
using cad::kernel::KernelError;
using cad::kernel::BooleanOp;
using cad::kernel::Shape;
using cad::kernel::SketchProfileInput;

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

std::vector<double> toDoubles(JNIEnv* env, jdoubleArray array) {
    if (!array) throw KernelError(ErrorCode::InvalidArgument, "Array must not be null");
    std::vector<double> out(static_cast<size_t>(env->GetArrayLength(array)));
    if (!out.empty()) env->GetDoubleArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return out;
}

void toVec3(JNIEnv* env, jdoubleArray array, double out[3]) {
    const auto v = toDoubles(env, array);
    if (v.size() != 3) throw KernelError(ErrorCode::InvalidArgument, "Expected three coordinates");
    out[0] = v[0];
    out[1] = v[1];
    out[2] = v[2];
}

SketchProfileInput planeInput(JNIEnv* env, jdoubleArray origin, jdoubleArray xAxis, jdoubleArray yAxis) {
    SketchProfileInput in{};
    toVec3(env, origin, in.origin);
    toVec3(env, xAxis, in.xAxis);
    toVec3(env, yAxis, in.yAxis);
    return in;
}

jlong release(std::unique_ptr<Shape> shape) { return reinterpret_cast<jlong>(shape.release()); }

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

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeImportIges(JNIEnv* env, jclass, jstring path) {
    return guard<jlong>(env, 0, [&] {
        return reinterpret_cast<jlong>(Shape::importIges(toStdString(env, path)).release());
    });
}

JNIEXPORT void JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeExportIges(JNIEnv* env, jclass, jlong handle, jstring path) {
    guard<int>(env, 0, [&] {
        shapeFromHandle(handle).exportIges(toStdString(env, path));
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

// Returns {positions, normals, indices, edgeSegments, triangleFaces} as direct ByteBuffers.
JNIEXPORT jobjectArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeTessellate(JNIEnv* env, jclass, jlong handle,
                                                       jdouble linearDeflection,
                                                       jdouble angularDeflection) {
    return guard<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        const auto mesh = shapeFromHandle(handle).tessellate(linearDeflection, angularDeflection);
        jclass cls = env->FindClass("java/nio/ByteBuffer");
        if (!cls) throw JavaExceptionPending{};
        jobjectArray out = env->NewObjectArray(5, cls, nullptr);
        if (!out) throw JavaExceptionPending{};
        env->SetObjectArrayElement(out, 4, toDirectBuffer(env, mesh.triangleFaces));
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

// ---- Modeling -----------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeProfileFromSketch(JNIEnv* env, jclass, jdoubleArray origin,
                                                              jdoubleArray xAxis, jdoubleArray yAxis,
                                                              jdoubleArray lines, jdoubleArray circles,
                                                              jdoubleArray arcs, jdoubleArray splines) {
    return guard<jlong>(env, 0, [&] {
        auto in = planeInput(env, origin, xAxis, yAxis);
        in.lines = toDoubles(env, lines);
        in.circles = toDoubles(env, circles);
        in.arcs = toDoubles(env, arcs);
        in.splines = toDoubles(env, splines);
        return release(Shape::profileFromSketch(in));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeSweep(JNIEnv* env, jclass, jlong handle, jdoubleArray origin,
                                                  jdoubleArray xAxis, jdoubleArray yAxis, jdoubleArray lines,
                                                  jdoubleArray arcs) {
    return guard<jlong>(env, 0, [&] {
        auto in = planeInput(env, origin, xAxis, yAxis);
        in.lines = toDoubles(env, lines);
        in.arcs = toDoubles(env, arcs);
        return release(shapeFromHandle(handle).sweep(in));
    });
}

JNIEXPORT jint JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeRegionAt(JNIEnv* env, jclass, jlong handle, jdoubleArray origin,
                                                     jdoubleArray xAxis, jdoubleArray yAxis, jdouble u,
                                                     jdouble v) {
    return guard<jint>(env, -1, [&] {
        return shapeFromHandle(handle).regionAt(planeInput(env, origin, xAxis, yAxis), u, v);
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeSubset(JNIEnv* env, jclass, jlong handle, jintArray faces) {
    return guard<jlong>(env, 0, [&] {
        if (!faces) throw KernelError(ErrorCode::InvalidArgument, "Array must not be null");
        std::vector<int> indices(static_cast<size_t>(env->GetArrayLength(faces)));
        if (!indices.empty()) {
            env->GetIntArrayRegion(faces, 0, static_cast<jsize>(indices.size()),
                                   reinterpret_cast<jint*>(indices.data()));
        }
        return release(shapeFromHandle(handle).subset(indices));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeExtrude(JNIEnv* env, jclass, jlong handle, jdouble dx, jdouble dy,
                                                    jdouble dz) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).extrude(dx, dy, dz)); });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeRevolve(JNIEnv* env, jclass, jlong handle, jdoubleArray axisOrigin,
                                                    jdoubleArray axisDirection, jdouble angle) {
    return guard<jlong>(env, 0, [&] {
        double o[3], d[3];
        toVec3(env, axisOrigin, o);
        toVec3(env, axisDirection, d);
        return release(shapeFromHandle(handle).revolve(o, d, angle));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeBoolean(JNIEnv* env, jclass, jlong handle, jlong tool, jint op) {
    return guard<jlong>(env, 0, [&] {
        if (op < 0 || op > 2) throw KernelError(ErrorCode::InvalidArgument, "Unknown boolean operation");
        return release(shapeFromHandle(handle).booleanWith(shapeFromHandle(tool), static_cast<BooleanOp>(op)));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeFillet(JNIEnv* env, jclass, jlong handle, jdouble radius,
                                                   jint face) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).fillet(radius, face)); });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeChamfer(JNIEnv* env, jclass, jlong handle, jdouble distance,
                                                    jint face) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).chamfer(distance, face)); });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativePushPull(JNIEnv* env, jclass, jlong handle, jint face,
                                                     jdouble distance) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).pushPull(face, distance)); });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeCompound(JNIEnv* env, jclass, jlongArray handles) {
    return guard<jlong>(env, 0, [&] {
        if (!handles) throw KernelError(ErrorCode::InvalidArgument, "Array must not be null");
        std::vector<jlong> raw(static_cast<size_t>(env->GetArrayLength(handles)));
        if (!raw.empty()) env->GetLongArrayRegion(handles, 0, static_cast<jsize>(raw.size()), raw.data());
        std::vector<const Shape*> shapes;
        for (jlong h : raw) shapes.push_back(&shapeFromHandle(h));
        return release(Shape::compound(shapes));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeLoft(JNIEnv* env, jclass, jlongArray handles) {
    return guard<jlong>(env, 0, [&] {
        if (!handles) throw KernelError(ErrorCode::InvalidArgument, "Array must not be null");
        std::vector<jlong> raw(static_cast<size_t>(env->GetArrayLength(handles)));
        if (!raw.empty()) env->GetLongArrayRegion(handles, 0, static_cast<jsize>(raw.size()), raw.data());
        std::vector<const Shape*> shapes;
        for (jlong h : raw) shapes.push_back(&shapeFromHandle(h));
        return release(Shape::loft(shapes));
    });
}

JNIEXPORT jdouble JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeDistance(JNIEnv* env, jclass, jlong handle, jlong other) {
    return guard<jdouble>(env, 0.0, [&] { return shapeFromHandle(handle).distanceTo(shapeFromHandle(other)); });
}

// Returns {visible, hidden} float arrays of x1, y1, x2, y2 segments.
JNIEXPORT jobjectArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeProject(JNIEnv* env, jclass, jlong handle, jdoubleArray toViewer,
                                                    jdoubleArray up, jdouble deflection) {
    return guard<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        double z[3], u[3];
        toVec3(env, toViewer, z);
        toVec3(env, up, u);
        const auto view = shapeFromHandle(handle).project(z, u, deflection);
        jclass cls = env->FindClass("[F");
        if (!cls) throw JavaExceptionPending{};
        jobjectArray out = env->NewObjectArray(2, cls, nullptr);
        if (!out) throw JavaExceptionPending{};
        const std::vector<float>* parts[2] = {&view.visible, &view.hidden};
        for (int i = 0; i < 2; ++i) {
            jfloatArray arr = env->NewFloatArray(static_cast<jsize>(parts[i]->size()));
            if (!arr) throw JavaExceptionPending{};
            if (!parts[i]->empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(parts[i]->size()), parts[i]->data());
            env->SetObjectArrayElement(out, i, arr);
        }
        return out;
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeShell(JNIEnv* env, jclass, jlong handle, jint face, jdouble thickness) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).shell(face, thickness)); });
}

JNIEXPORT jdouble JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeFaceArea(JNIEnv* env, jclass, jlong handle, jint face) {
    return guard<jdouble>(env, 0.0, [&] { return shapeFromHandle(handle).faceArea(face); });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeMoved(JNIEnv* env, jclass, jlong handle, jdoubleArray axisOrigin,
                                                  jdoubleArray axisDirection, jdouble angle, jdouble dx, jdouble dy,
                                                  jdouble dz) {
    return guard<jlong>(env, 0, [&] {
        double o[3], d[3];
        toVec3(env, axisOrigin, o);
        toVec3(env, axisDirection, d);
        return release(shapeFromHandle(handle).moved(o, d, angle, dx, dy, dz));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeDraft(JNIEnv* env, jclass, jlong handle, jint face, jdouble angle,
                                                  jdoubleArray direction, jdoubleArray neutral) {
    return guard<jlong>(env, 0, [&] {
        double d[3], p[3];
        toVec3(env, direction, d);
        toVec3(env, neutral, p);
        return release(shapeFromHandle(handle).draft(face, angle, d, p));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeScaled(JNIEnv* env, jclass, jlong handle, jdoubleArray center,
                                                   jdouble factor) {
    return guard<jlong>(env, 0, [&] {
        double c[3];
        toVec3(env, center, c);
        return release(shapeFromHandle(handle).scaled(c, factor));
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeMirrored(JNIEnv* env, jclass, jlong handle, jdoubleArray origin,
                                                     jdoubleArray normal) {
    return guard<jlong>(env, 0, [&] {
        double o[3], n[3];
        toVec3(env, origin, o);
        toVec3(env, normal, n);
        return release(shapeFromHandle(handle).mirrored(o, n));
    });
}

JNIEXPORT jint JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeFaceCount(JNIEnv* env, jclass, jlong handle) {
    return guard<jint>(env, 0, [&] { return shapeFromHandle(handle).faceCount(); });
}

JNIEXPORT jdouble JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeVolume(JNIEnv* env, jclass, jlong handle) {
    return guard<jdouble>(env, 0.0, [&] { return shapeFromHandle(handle).volume(); });
}

// Returns {face, distance}; face is -1 when the ray misses.
JNIEXPORT jdoubleArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativePick(JNIEnv* env, jclass, jlong handle, jdoubleArray origin,
                                                 jdoubleArray direction) {
    return guard<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        double o[3], d[3];
        toVec3(env, origin, o);
        toVec3(env, direction, d);
        const auto hit = shapeFromHandle(handle).pick(o, d);
        const jdouble values[2] = {static_cast<jdouble>(hit.face), hit.distance};
        jdoubleArray out = env->NewDoubleArray(2);
        if (out) env->SetDoubleArrayRegion(out, 0, 2, values);
        return out;
    });
}

JNIEXPORT jlong JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeBlendEdge(JNIEnv* env, jclass, jlong handle, jdouble size, jint edge,
                                                      jboolean chamfer) {
    return guard<jlong>(env, 0, [&] { return release(shapeFromHandle(handle).blendEdge(size, edge, chamfer)); });
}

JNIEXPORT jint JNICALL
Java_com_sharpcad_kernel_KernelNative_nativePickEdge(JNIEnv* env, jclass, jlong handle, jdoubleArray origin,
                                                     jdoubleArray direction, jdouble tolerance) {
    return guard<jint>(env, -1, [&]() -> jint {
        double o[3], d[3];
        toVec3(env, origin, o);
        toVec3(env, direction, d);
        return shapeFromHandle(handle).pickEdge(o, d, tolerance);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_sharpcad_kernel_KernelNative_nativeEdgePoints(JNIEnv* env, jclass, jlong handle, jint edge) {
    return guard<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        const auto points = shapeFromHandle(handle).edgePoints(edge);
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(points.size()));
        if (out) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(points.size()), points.data());
        return out;
    });
}

}  // extern "C"
