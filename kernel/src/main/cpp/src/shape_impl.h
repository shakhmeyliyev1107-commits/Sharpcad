// Internal to the kernel: shared by the .cpp files that work on OCCT shapes.
#pragma once

#include <string>

#include <Standard_Failure.hxx>
#include <TopoDS_Shape.hxx>

#include "cad/kernel/error.h"
#include "cad/kernel/shape.h"

namespace cad::kernel {

struct Shape::Impl {
    TopoDS_Shape shape;
};

// Runs an OCCT call and converts its exceptions into KernelError.
template <typename Fn>
inline auto guarded(const char* what, Fn&& fn) {
    try {
        return fn();
    } catch (const Standard_Failure& e) {
        const char* msg = e.GetMessageString();
        throw KernelError(ErrorCode::OcctFailure,
                          std::string(what) + ": " + (msg && *msg ? msg : e.DynamicType()->Name()));
    }
}


}  // namespace cad::kernel
