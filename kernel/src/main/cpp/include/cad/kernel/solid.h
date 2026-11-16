#pragma once

#include <memory>
#include <string>

namespace cad::kernel {

/// Axis-aligned bounds in millimetres.
struct BoundingBox {
    double min[3];
    double max[3];
};

/// Version of the linked OpenCASCADE library, e.g. "7.9.3".
std::string occtVersion();

/// A B-rep solid. OCCT types are hidden behind the implementation.
class Solid {
public:
    ~Solid();
    Solid(const Solid&) = delete;
    Solid& operator=(const Solid&) = delete;

    /// Box with one corner at the origin. Throws KernelError(InvalidArgument)
    /// unless all dimensions are positive and finite.
    static std::unique_ptr<Solid> makeBox(double dx, double dy, double dz);

    /// Tight bounds computed from the exact geometry (no tolerance padding).
    BoundingBox boundingBox() const;

private:
    struct Impl;
    explicit Solid(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace cad::kernel
