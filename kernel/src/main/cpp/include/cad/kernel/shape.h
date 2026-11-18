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
    std::vector<std::int32_t> triangleFaces;  ///< face index (see faceCount) per triangle
};

/// Sketch geometry on a plane, in plane coordinates (millimetres).
struct SketchProfileInput {
    double origin[3];
    double xAxis[3];  ///< unit
    double yAxis[3];  ///< unit, perpendicular to xAxis
    std::vector<double> lines;    ///< x1, y1, x2, y2 per line
    std::vector<double> circles;  ///< cx, cy, radius per circle
    std::vector<double> arcs;     ///< cx, cy, radius, start angle, end angle per arc (ccw, radians)
    std::vector<double> splines;  ///< per spline: point count n, then n (x, y) points it passes through
};

enum class BooleanOp : int { Union = 0, Subtract = 1, Intersect = 2 };

/// A 2D projection of a shape: line segments (x1, y1, x2, y2 …) in the view plane, mm.
struct ViewProjection {
    std::vector<float> visible;  ///< sharp edges and silhouettes facing the viewer
    std::vector<float> hidden;   ///< edges behind faces (drawn dashed in drawings)
};

/// Result of a ray pick: the hit face and the distance along the ray, or face -1.
struct PickHit {
    int face;
    double distance;
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

    /// Reads all roots of an IGES file into one shape, in millimetres.
    static std::unique_ptr<Shape> importIges(const std::string& path);

    /// Writes the shape as IGES (BRep mode). Throws KernelError(IoError) on failure.
    void exportIges(const std::string& path) const;

    /// Closed regions bounded by the sketch, as planar faces (one compound). Crossing
    /// lines split regions; a circle inside a rectangle gives a ring and a disc.
    /// Throws KernelError(InvalidArgument) if the sketch encloses no region.
    static std::unique_ptr<Shape> profileFromSketch(const SketchProfileInput& input);

    /// Index of the profile region containing the plane point (u, v), or -1.
    /// Only meaningful on a shape made by profileFromSketch.
    int regionAt(const SketchProfileInput& plane, double u, double v) const;

    /// The given faces (by index) of this shape, as a new compound.
    std::unique_ptr<Shape> subset(const std::vector<int>& faces) const;

    /// Sweeps this shape (faces) along a vector. Distance must be non-zero.
    std::unique_ptr<Shape> extrude(double dx, double dy, double dz) const;

    /// Revolves this shape (faces) around an axis by angle radians, in (0, 2π].
    std::unique_ptr<Shape> revolve(const double axisOrigin[3], const double axisDirection[3],
                                   double angle) const;

    /// Boolean of this shape with `tool`. Throws OcctFailure if OCCT reports an error.
    std::unique_ptr<Shape> booleanWith(const Shape& tool, BooleanOp op) const;

    /// Rounds edges: those of face `face`, or all edges when face is -1.
    std::unique_ptr<Shape> fillet(double radius, int face = -1) const;

    /// Bevels edges: those of face `face`, or all edges when face is -1.
    std::unique_ptr<Shape> chamfer(double distance, int face = -1) const;

    /// Rounds (or bevels, when `chamfer`) the single edge `edge` (index into the shape's edges).
    std::unique_ptr<Shape> blendEdge(double size, int edge, bool chamfer) const;

    /// Edge nearest the ray within `tolerance` (closest along the ray on ties), or -1.
    int pickEdge(const double origin[3], const double direction[3], double tolerance) const;

    /// Edge `edge` sampled as consecutive points x,y,z (deflection 0.05 mm).
    std::vector<double> edgePoints(int edge) const;

    /// Moves a planar face along its outward normal: positive adds material, negative cuts.
    std::unique_ptr<Shape> pushPull(int face, double distance) const;

    /// Compound of several shapes (used to export many bodies to one file).
    static std::unique_ptr<Shape> compound(const std::vector<const Shape*>& shapes);

    /// Sweeps this profile's first face along the connected lines/arcs of `path`.
    std::unique_ptr<Shape> sweep(const SketchProfileInput& path) const;

    /// Solid through the outer boundary of the first face of each profile, in order.
    static std::unique_ptr<Shape> loft(const std::vector<const Shape*>& profiles);

    /// Smallest distance between this shape and `other` (0 when they touch or overlap).
    double distanceTo(const Shape& other) const;

    /// Orthographic hidden-line projection. `toViewer` points from the model to the eye,
    /// `up` is the view's vertical; x grows to the right of the viewer.
    ViewProjection project(const double toViewer[3], const double up[3], double deflection) const;

    /// Hollows the solid to a wall of `thickness` mm, opening face `face` (-1 = closed shell).
    std::unique_ptr<Shape> shell(int face, double thickness) const;

    /// Area of face `face` in mm².
    double faceArea(int face) const;

    /// Copy moved by a rigid motion: rotation `angle` radians about the axis through
    /// `axisOrigin` along `axisDirection`, then translation (dx, dy, dz).
    std::unique_ptr<Shape> moved(const double axisOrigin[3], const double axisDirection[3], double angle,
                                 double dx, double dy, double dz) const;

    /// Mirror image across the plane through `origin` with normal `normal`.
    std::unique_ptr<Shape> mirrored(const double origin[3], const double normal[3]) const;

    /// Tilts face `face` by `angle` radians about its line on the neutral plane through
    /// `neutralPoint` with normal `direction` (the pull direction). Throws OcctFailure
    /// when the face cannot be drafted (e.g. it is perpendicular to the direction).
    std::unique_ptr<Shape> draft(int face, double angle, const double direction[3], const double neutralPoint[3]) const;

    /// Copy scaled uniformly by `factor` (> 0) about `center`.
    std::unique_ptr<Shape> scaled(const double center[3], double factor) const;

    int faceCount() const;
    double volume() const;

    /// Nearest face hit by the ray, or {-1, 0}.
    PickHit pick(const double origin[3], const double direction[3]) const;

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
