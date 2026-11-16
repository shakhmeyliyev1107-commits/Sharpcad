#include "cad/kernel/solid.h"

#include <cmath>

#include <BRepBndLib.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <Bnd_Box.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopoDS_Shape.hxx>

#include "cad/kernel/error.h"

namespace cad::kernel {

namespace {

// Smallest dimension we accept; below this OCCT produces degenerate topology.
constexpr double kMinDimension = 1e-6;

bool isValidDimension(double v) { return std::isfinite(v) && v >= kMinDimension; }

// Runs an OCCT call and converts its exceptions into KernelError.
template <typename Fn>
auto guarded(const char* what, Fn&& fn) {
    try {
        return fn();
    } catch (const Standard_Failure& e) {
        const char* msg = e.GetMessageString();
        throw KernelError(ErrorCode::OcctFailure,
                          std::string(what) + ": " + (msg && *msg ? msg : e.DynamicType()->Name()));
    }
}

}  // namespace

struct Solid::Impl {
    TopoDS_Shape shape;
};

Solid::Solid(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Solid::~Solid() = default;

std::string occtVersion() { return OCC_VERSION_COMPLETE; }

std::unique_ptr<Solid> Solid::makeBox(double dx, double dy, double dz) {
    if (!isValidDimension(dx) || !isValidDimension(dy) || !isValidDimension(dz)) {
        throw KernelError(ErrorCode::InvalidArgument,
                          "Box dimensions must be finite and at least 1e-6 mm");
    }
    return guarded("makeBox", [&] {
        auto impl = std::make_unique<Impl>();
        impl->shape = BRepPrimAPI_MakeBox(dx, dy, dz).Shape();
        return std::unique_ptr<Solid>(new Solid(std::move(impl)));
    });
}

BoundingBox Solid::boundingBox() const {
    return guarded("boundingBox", [&] {
        Bnd_Box box;
        // Exact geometry, no triangulation, no tolerance enlargement.
        BRepBndLib::AddOptimal(impl_->shape, box, /*useTriangulation=*/false,
                               /*useShapeTolerance=*/false);
        if (box.IsVoid()) {
            throw KernelError(ErrorCode::OcctFailure, "boundingBox: shape has no geometry");
        }
        BoundingBox out{};
        box.Get(out.min[0], out.min[1], out.min[2], out.max[0], out.max[1], out.max[2]);
        return out;
    });
}

}  // namespace cad::kernel
