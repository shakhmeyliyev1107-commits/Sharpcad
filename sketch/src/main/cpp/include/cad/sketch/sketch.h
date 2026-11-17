#pragma once

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace cad::sketch {

/// Stable error codes. Values are mirrored in Kotlin `SketchException`.
enum class ErrorCode : int {
    InvalidArgument = 1,
    SolverFailure = 2,
    InvalidHandle = 3,
    Unknown = 99,
};

class SketchError : public std::runtime_error {
public:
    SketchError(ErrorCode code, const std::string& message)
        : std::runtime_error(message), code_(code) {}
    ErrorCode code() const noexcept { return code_; }

private:
    ErrorCode code_;
};

enum class EntityKind : int { Line = 0, Circle = 1 };

/// Which point of an entity is meant. Lines have Start and End, circles have Center.
enum class PointRole : int { Start = 0, End = 1, Center = 2 };

struct PointRef {
    int entity;
    PointRole role;
};

/// Geometry of one entity in sketch-plane millimetres.
/// Line: (a, b) is the start, (c, d) the end. Circle: (a, b) is the centre, c the radius.
struct EntityGeometry {
    int id;
    EntityKind kind;
    double a, b, c, d;
};

enum class SolveStatus : int {
    Solved = 0,       ///< all constraints satisfied; geometry updated
    Conflicting = 1,  ///< constraints contradict each other; geometry unchanged
    Failed = 2,       ///< solver did not converge; geometry unchanged
};

struct SolveResult {
    SolveStatus status;
    int degreesOfFreedom;          ///< 0 means fully constrained
    std::vector<int> conflicting;  ///< constraint ids that contradict others
    std::vector<int> redundant;    ///< constraint ids that add nothing
};

/// A 2D sketch: lines and circles tied together by constraints, solved by PlaneGCS.
/// Entity and constraint ids are never reused. Not thread-safe.
class Sketch {
public:
    Sketch();
    ~Sketch();
    Sketch(const Sketch&) = delete;
    Sketch& operator=(const Sketch&) = delete;

    // Entities. Coordinates must be finite; a circle's radius must be positive.
    int addLine(double x1, double y1, double x2, double y2);
    int addCircle(double cx, double cy, double radius);
    /// Removes an entity and every constraint that refers to it.
    void removeEntity(int entity);

    // Constraints; each returns the new constraint id.
    int addCoincident(PointRef a, PointRef b);
    int addHorizontal(int line);
    int addVertical(int line);
    /// Two-line constraints; the lines must be different.
    int addEqualLength(int lineA, int lineB);
    int addParallel(int lineA, int lineB);
    int addPerpendicular(int lineA, int lineB);
    /// Distance between two points; must be positive. Use it on a line's ends for length.
    int addDistance(PointRef a, PointRef b, double distance);
    int addRadius(int circle, double radius);
    /// Pins a point at its current position.
    int addFixed(PointRef point);
    void removeConstraint(int constraint);
    /// Changes the value of a Distance or Radius constraint.
    void setDimension(int constraint, double value);

    /// Moves a point without solving (used while rubber-banding a new entity).
    void setPoint(PointRef point, double x, double y);
    void setCircleRadius(int circle, double radius);

    /// Solves all constraints starting from the current geometry.
    SolveResult solve();
    /// Solves while pulling `point` towards (x, y) as far as the constraints allow.
    SolveResult solveDragging(PointRef point, double x, double y);

    /// Geometry of all live entities, in creation order.
    std::vector<EntityGeometry> geometry() const;

    struct Impl;  // public only so internal helpers in sketch.cpp can reach it

private:
    std::unique_ptr<Impl> impl_;
};

}  // namespace cad::sketch
