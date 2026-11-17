// JNI marshalling for com.sharpcad.sketch.SketchNative.
// No sketch logic here: convert arguments, call cad::sketch, translate exceptions.

#include <jni.h>

#include <exception>
#include <string>
#include <vector>

#include "cad/sketch/sketch.h"

using cad::sketch::ErrorCode;
using cad::sketch::PointRef;
using cad::sketch::PointRole;
using cad::sketch::Sketch;
using cad::sketch::SketchError;
using cad::sketch::SolveResult;

namespace {

void throwSketchException(JNIEnv* env, ErrorCode code, const std::string& message) {
    if (env->ExceptionCheck()) return;  // keep the first pending exception
    jclass cls = env->FindClass("com/sharpcad/sketch/SketchException");
    if (!cls) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return;
    jstring jmsg = env->NewStringUTF(message.c_str());
    if (!jmsg) return;
    auto ex = static_cast<jthrowable>(env->NewObject(cls, ctor, static_cast<jint>(code), jmsg));
    if (ex) env->Throw(ex);
}

// Runs fn; on any C++ exception raises SketchException and returns fallback.
// Nothing may unwind into the JVM.
template <typename T, typename Fn>
T guard(JNIEnv* env, T fallback, Fn&& fn) noexcept {
    try {
        return fn();
    } catch (const SketchError& e) {
        throwSketchException(env, e.code(), e.what());
    } catch (const std::exception& e) {
        throwSketchException(env, ErrorCode::Unknown, e.what());
    } catch (...) {
        throwSketchException(env, ErrorCode::Unknown, "Unknown native error");
    }
    return fallback;
}

template <typename Fn>
void guardVoid(JNIEnv* env, Fn&& fn) noexcept {
    guard<int>(env, 0, [&] {
        fn();
        return 0;
    });
}

Sketch& sketchFromHandle(jlong handle) {
    if (handle == 0) throw SketchError(ErrorCode::InvalidHandle, "Sketch has been closed");
    return *reinterpret_cast<Sketch*>(handle);
}

PointRef pointRef(jint entity, jint role) {
    if (role < 0 || role > 2) throw SketchError(ErrorCode::InvalidArgument, "Unknown point role");
    return PointRef{entity, static_cast<PointRole>(role)};
}

// Layout: status, degreesOfFreedom, nConflicting, conflicting..., nRedundant, redundant...
jintArray toJava(JNIEnv* env, const SolveResult& result) {
    std::vector<jint> out;
    out.push_back(static_cast<jint>(result.status));
    out.push_back(result.degreesOfFreedom);
    out.push_back(static_cast<jint>(result.conflicting.size()));
    out.insert(out.end(), result.conflicting.begin(), result.conflicting.end());
    out.push_back(static_cast<jint>(result.redundant.size()));
    out.insert(out.end(), result.redundant.begin(), result.redundant.end());
    jintArray array = env->NewIntArray(static_cast<jsize>(out.size()));
    if (array) env->SetIntArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

}  // namespace

#define SKETCH_JNI(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_com_sharpcad_sketch_SketchNative_##name

SKETCH_JNI(jlong, nativeCreate)(JNIEnv* env, jclass) {
    return guard<jlong>(env, 0, [] { return reinterpret_cast<jlong>(new Sketch()); });
}

SKETCH_JNI(void, nativeRelease)(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Sketch*>(handle);
}

SKETCH_JNI(jint, nativeAddLine)(JNIEnv* env, jclass, jlong handle, jdouble x1, jdouble y1,
                                jdouble x2, jdouble y2) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addLine(x1, y1, x2, y2); });
}

SKETCH_JNI(jint, nativeAddCircle)(JNIEnv* env, jclass, jlong handle, jdouble cx, jdouble cy,
                                  jdouble radius) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addCircle(cx, cy, radius); });
}

SKETCH_JNI(jint, nativeAddArc)(JNIEnv* env, jclass, jlong handle, jdouble cx, jdouble cy, jdouble r,
                               jdouble a0, jdouble a1) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addArc(cx, cy, r, a0, a1); });
}

SKETCH_JNI(void, nativeSetArc)(JNIEnv* env, jclass, jlong handle, jint arc, jdouble cx, jdouble cy, jdouble r,
                               jdouble a0, jdouble a1) {
    guardVoid(env, [&] { sketchFromHandle(handle).setArc(arc, cx, cy, r, a0, a1); });
}

SKETCH_JNI(void, nativeRemoveEntity)(JNIEnv* env, jclass, jlong handle, jint entity) {
    guardVoid(env, [&] { sketchFromHandle(handle).removeEntity(entity); });
}

SKETCH_JNI(jint, nativeAddCoincident)(JNIEnv* env, jclass, jlong handle, jint entityA, jint roleA,
                                      jint entityB, jint roleB) {
    return guard<jint>(env, -1, [&] {
        return sketchFromHandle(handle).addCoincident(pointRef(entityA, roleA),
                                                      pointRef(entityB, roleB));
    });
}

SKETCH_JNI(jint, nativeAddHorizontal)(JNIEnv* env, jclass, jlong handle, jint line) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addHorizontal(line); });
}

SKETCH_JNI(jint, nativeAddVertical)(JNIEnv* env, jclass, jlong handle, jint line) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addVertical(line); });
}

SKETCH_JNI(jint, nativeAddLinePair)(JNIEnv* env, jclass, jlong handle, jint kind, jint a, jint b) {
    return guard<jint>(env, -1, [&] {
        Sketch& s = sketchFromHandle(handle);
        switch (kind) {
            case 0: return s.addEqualLength(a, b);
            case 1: return s.addParallel(a, b);
            case 2: return s.addPerpendicular(a, b);
            default: throw SketchError(ErrorCode::InvalidArgument, "Unknown line constraint");
        }
    });
}

SKETCH_JNI(jint, nativeAddDistance)(JNIEnv* env, jclass, jlong handle, jint entityA, jint roleA,
                                    jint entityB, jint roleB, jdouble distance) {
    return guard<jint>(env, -1, [&] {
        return sketchFromHandle(handle).addDistance(pointRef(entityA, roleA),
                                                    pointRef(entityB, roleB), distance);
    });
}

SKETCH_JNI(jint, nativeAddRadius)(JNIEnv* env, jclass, jlong handle, jint circle, jdouble radius) {
    return guard<jint>(env, -1, [&] { return sketchFromHandle(handle).addRadius(circle, radius); });
}

SKETCH_JNI(jint, nativeAddFixed)(JNIEnv* env, jclass, jlong handle, jint entity, jint role) {
    return guard<jint>(env, -1, [&] {
        return sketchFromHandle(handle).addFixed(pointRef(entity, role));
    });
}

SKETCH_JNI(void, nativeRemoveConstraint)(JNIEnv* env, jclass, jlong handle, jint constraint) {
    guardVoid(env, [&] { sketchFromHandle(handle).removeConstraint(constraint); });
}

SKETCH_JNI(void, nativeSetDimension)(JNIEnv* env, jclass, jlong handle, jint constraint,
                                     jdouble value) {
    guardVoid(env, [&] { sketchFromHandle(handle).setDimension(constraint, value); });
}

SKETCH_JNI(void, nativeSetPoint)(JNIEnv* env, jclass, jlong handle, jint entity, jint role,
                                 jdouble x, jdouble y) {
    guardVoid(env, [&] { sketchFromHandle(handle).setPoint(pointRef(entity, role), x, y); });
}

SKETCH_JNI(void, nativeSetCircleRadius)(JNIEnv* env, jclass, jlong handle, jint circle,
                                        jdouble radius) {
    guardVoid(env, [&] { sketchFromHandle(handle).setCircleRadius(circle, radius); });
}

SKETCH_JNI(jintArray, nativeSolve)(JNIEnv* env, jclass, jlong handle) {
    return guard<jintArray>(env, nullptr, [&] {
        return toJava(env, sketchFromHandle(handle).solve());
    });
}

SKETCH_JNI(jintArray, nativeSolveDragging)(JNIEnv* env, jclass, jlong handle, jint entity,
                                           jint role, jdouble x, jdouble y) {
    return guard<jintArray>(env, nullptr, [&] {
        return toJava(env, sketchFromHandle(handle).solveDragging(pointRef(entity, role), x, y));
    });
}

// Seven doubles per live entity: id, kind, a, b, c, d, e (see EntityGeometry).
SKETCH_JNI(jdoubleArray, nativeGeometry)(JNIEnv* env, jclass, jlong handle) {
    return guard<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        const auto geometry = sketchFromHandle(handle).geometry();
        std::vector<jdouble> out;
        out.reserve(geometry.size() * 7);
        for (const auto& g : geometry) {
            out.insert(out.end(), {static_cast<jdouble>(g.id), static_cast<jdouble>(g.kind),
                                   g.a, g.b, g.c, g.d, g.e});
        }
        jdoubleArray array = env->NewDoubleArray(static_cast<jsize>(out.size()));
        if (array) env->SetDoubleArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    });
}
