#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace cad::kernel {

/// Axis-aligned bounds in millimetres.
struct BoundingBox {
    double min[3];
    double max[3];
};

/// Triangle mesh plus feature edges, ready for upload to the GPU.
struct MeshData {
    std::vector<float> positions;        ///< xyz per vertex
    std::vector<float> normals;          ///< unit xyz per vertex, same count as positions
    std::vector<std::uint32_t> indices;  ///< three per triangle, counter-clockwise outside
    std::vector<float> edgeSegments;     ///< xyz pairs, one line segment per two points
};

/// Version of the linked OpenCASCADE library, e.g. "7.9.3".
std::string occtVersion();

/// A B-rep shape (solid, shell or compound). OCCT types stay behind the implementation.
class Shape {
public:
    ~Shape();
    Shape(const Shape&) = delete;
    Shape& operator=(const Shape&) = delete;

    /// Box with one corner at the origin. Throws KernelError(InvalidArgument)
    /// unless all dimensions are finite and at least 1e-6 mm.
    static std::unique_ptr<Shape> makeBox(double dx, double dy, double dz);

    /// Reads all roots of a STEP file into one shape, converted to millimetres.
    /// Throws KernelError(IoError) if the file cannot be read or holds no geometry.
    static std::unique_ptr<Shape> importStep(const std::string& path);

    /// Writes the shape as STEP. Throws KernelError(IoError) on failure.
    void exportStep(const std::string& path) const;

    /// Tight bounds computed from the exact geometry (no tolerance padding).
    BoundingBox boundingBox() const;

    /// Triangulates the shape. `linearDeflection` is the maximum chord error in mm,
    /// `angularDeflection` the maximum angle between adjacent facets in radians.
    /// Faces that cannot be meshed are skipped. Blocking and CPU-heavy.
    MeshData tessellate(double linearDeflection, double angularDeflection) const;

private:
    struct Impl;
    explicit Shape(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace cad::kernel
