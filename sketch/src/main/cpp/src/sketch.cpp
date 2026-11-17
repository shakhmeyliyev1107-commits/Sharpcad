#include "cad/sketch/sketch.h"

#include <cmath>
#include <deque>
#include <exception>

#include <GCS.h>

namespace cad::sketch {

namespace {

enum class ConstraintKind { Coincident, Horizontal, Vertical, Distance, Radius, Fixed, EqualLength, Parallel, Perpendicular };

struct Entity {
    EntityKind kind;
    bool alive = true;
    // Line: x1, y1, x2, y2. Circle: cx, cy, r. Pointers into Impl::values (stable).
    double* p[4] = {nullptr, nullptr, nullptr, nullptr};
};

struct Constraint {
    ConstraintKind kind;
    bool alive = true;
    PointRef a{-1, PointRole::Start};
    PointRef b{-1, PointRole::Start};
    int entity = -1;           // Horizontal, Vertical, Radius; first line of two-line kinds
    int entity2 = -1;          // second line of EqualLength, Parallel, Perpendicular
    double* value = nullptr;   // Distance, Radius; Fixed uses value (x) and value2 (y)
    double* value2 = nullptr;
};

bool finite(double v) { return std::isfinite(v); }

void require(bool condition, const char* message) {
    if (!condition) throw SketchError(ErrorCode::InvalidArgument, message);
}

}  // namespace

struct Sketch::Impl {
    // deque: growing never moves existing doubles, and PlaneGCS keeps pointers to them.
    std::deque<double> values;
    std::vector<Entity> entities;
    std::vector<Constraint> constraints;

    double* store(double v) {
        values.push_back(v);
        return &values.back();
    }

    Entity& entity(int id, EntityKind kind) {
        require(id >= 0 && id < static_cast<int>(entities.size()) && entities[id].alive,
                "Unknown sketch entity");
        require(entities[id].kind == kind,
                kind == EntityKind::Line ? "Entity is not a line" : "Entity is not a circle");
        return entities[id];
    }

    Constraint& constraint(int id) {
        require(id >= 0 && id < static_cast<int>(constraints.size()) && constraints[id].alive,
                "Unknown sketch constraint");
        return constraints[id];
    }

    GCS::Point point(PointRef ref) {
        require(ref.entity >= 0 && ref.entity < static_cast<int>(entities.size()) &&
                    entities[ref.entity].alive,
                "Unknown sketch entity");
        const Entity& e = entities[ref.entity];
        if (e.kind == EntityKind::Line) {
            require(ref.role == PointRole::Start || ref.role == PointRole::End,
                    "A line has only start and end points");
            return ref.role == PointRole::Start ? GCS::Point(e.p[0], e.p[1])
                                                : GCS::Point(e.p[2], e.p[3]);
        }
        require(ref.role == PointRole::Center, "A circle has only a centre point");
        return GCS::Point(e.p[0], e.p[1]);
    }

    int addConstraint(Constraint c) {
        constraints.push_back(c);
        return static_cast<int>(constraints.size()) - 1;
    }

    // Rebuilds the PlaneGCS system from the model and solves it. Sketches are small, so
    // rebuilding each time is cheaper than keeping two representations in sync.
    SolveResult solve(const PointRef* dragged, double dragX, double dragY) {
        GCS::System system;
        GCS::VEC_pD unknowns;
        std::vector<GCS::Line> lines(entities.size());
        std::vector<GCS::Circle> circles(entities.size());

        for (size_t i = 0; i < entities.size(); ++i) {
            const Entity& e = entities[i];
            if (!e.alive) continue;
            if (e.kind == EntityKind::Line) {
                lines[i].p1 = GCS::Point(e.p[0], e.p[1]);
                lines[i].p2 = GCS::Point(e.p[2], e.p[3]);
                unknowns.insert(unknowns.end(), {e.p[0], e.p[1], e.p[2], e.p[3]});
            } else {
                circles[i].center = GCS::Point(e.p[0], e.p[1]);
                circles[i].rad = e.p[2];
                unknowns.insert(unknowns.end(), {e.p[0], e.p[1], e.p[2]});
            }
        }

        for (size_t i = 0; i < constraints.size(); ++i) {
            const Constraint& c = constraints[i];
            if (!c.alive) continue;
            const int tag = static_cast<int>(i) + 1;  // tag 0 means "untagged" in PlaneGCS
            switch (c.kind) {
                case ConstraintKind::Coincident: {
                    GCS::Point a = point(c.a), b = point(c.b);
                    system.addConstraintP2PCoincident(a, b, tag);
                    break;
                }
                case ConstraintKind::Horizontal:
                    system.addConstraintHorizontal(lines[c.entity], tag);
                    break;
                case ConstraintKind::Vertical:
                    system.addConstraintVertical(lines[c.entity], tag);
                    break;
                case ConstraintKind::Distance: {
                    GCS::Point a = point(c.a), b = point(c.b);
                    system.addConstraintP2PDistance(a, b, c.value, tag);
                    break;
                }
                case ConstraintKind::Radius:
                    system.addConstraintCircleRadius(circles[c.entity], c.value, tag);
                    break;
                case ConstraintKind::EqualLength:
                    system.addConstraintEqualLength(lines[c.entity], lines[c.entity2], tag);
                    break;
                case ConstraintKind::Parallel:
                    system.addConstraintParallel(lines[c.entity], lines[c.entity2], tag);
                    break;
                case ConstraintKind::Perpendicular:
                    system.addConstraintPerpendicular(lines[c.entity], lines[c.entity2], tag);
                    break;
                case ConstraintKind::Fixed: {
                    GCS::Point a = point(c.a);
                    system.addConstraintCoordinateX(a, c.value, tag);
                    system.addConstraintCoordinateY(a, c.value2, tag);
                    break;
                }
            }
        }

        // Dragging: a temporary low-priority constraint (negative tag) pulls the point
        // towards the target; PlaneGCS satisfies the real constraints first.
        double targetX = dragX, targetY = dragY;
        if (dragged) {
            GCS::Point p = point(*dragged);
            GCS::Point target(&targetX, &targetY);
            system.addConstraintP2PCoincident(p, target, GCS::DefaultTemporaryConstraint);
        }

        system.declareUnknowns(unknowns);
        system.initSolution();

        SolveResult result{SolveStatus::Solved, system.dofsNumber(), {}, {}};
        GCS::VEC_I tags;
        system.getConflicting(tags);
        for (int tag : tags) if (tag > 0) result.conflicting.push_back(tag - 1);
        system.getRedundant(tags);
        for (int tag : tags) if (tag > 0) result.redundant.push_back(tag - 1);

        if (!result.conflicting.empty()) {
            result.status = SolveStatus::Conflicting;
            return result;
        }
        const int status = system.solve();
        if (status == GCS::Success || status == GCS::Converged) {
            system.applySolution();
            // A circle must keep a positive radius whatever the solver did.
            bool valid = true;
            for (const Entity& e : entities) {
                if (e.alive && e.kind == EntityKind::Circle && !(*e.p[2] > 0.0)) valid = false;
            }
            if (valid) return result;
        }
        system.undoSolution();
        result.status = SolveStatus::Failed;
        return result;
    }
};

Sketch::Sketch() : impl_(std::make_unique<Impl>()) {}
Sketch::~Sketch() = default;

int Sketch::addLine(double x1, double y1, double x2, double y2) {
    require(finite(x1) && finite(y1) && finite(x2) && finite(y2), "Coordinates must be finite");
    Entity e{EntityKind::Line};
    e.p[0] = impl_->store(x1);
    e.p[1] = impl_->store(y1);
    e.p[2] = impl_->store(x2);
    e.p[3] = impl_->store(y2);
    impl_->entities.push_back(e);
    return static_cast<int>(impl_->entities.size()) - 1;
}

int Sketch::addCircle(double cx, double cy, double radius) {
    require(finite(cx) && finite(cy), "Coordinates must be finite");
    require(finite(radius) && radius > 0.0, "Radius must be positive and finite");
    Entity e{EntityKind::Circle};
    e.p[0] = impl_->store(cx);
    e.p[1] = impl_->store(cy);
    e.p[2] = impl_->store(radius);
    impl_->entities.push_back(e);
    return static_cast<int>(impl_->entities.size()) - 1;
}

void Sketch::removeEntity(int entity) {
    require(entity >= 0 && entity < static_cast<int>(impl_->entities.size()) &&
                impl_->entities[entity].alive,
            "Unknown sketch entity");
    impl_->entities[entity].alive = false;
    for (Constraint& c : impl_->constraints) {
        if (c.a.entity == entity || c.b.entity == entity || c.entity == entity || c.entity2 == entity) {
            c.alive = false;
        }
    }
}

int Sketch::addCoincident(PointRef a, PointRef b) {
    impl_->point(a);
    impl_->point(b);
    require(a.entity != b.entity || a.role != b.role, "A point cannot coincide with itself");
    Constraint c{ConstraintKind::Coincident};
    c.a = a;
    c.b = b;
    return impl_->addConstraint(c);
}

int Sketch::addHorizontal(int line) {
    impl_->entity(line, EntityKind::Line);
    Constraint c{ConstraintKind::Horizontal};
    c.entity = line;
    return impl_->addConstraint(c);
}

int Sketch::addVertical(int line) {
    impl_->entity(line, EntityKind::Line);
    Constraint c{ConstraintKind::Vertical};
    c.entity = line;
    return impl_->addConstraint(c);
}

namespace {
int addLinePair(Sketch::Impl& impl, ConstraintKind kind, int a, int b);
}

int Sketch::addEqualLength(int lineA, int lineB) { return addLinePair(*impl_, ConstraintKind::EqualLength, lineA, lineB); }
int Sketch::addParallel(int lineA, int lineB) { return addLinePair(*impl_, ConstraintKind::Parallel, lineA, lineB); }
int Sketch::addPerpendicular(int lineA, int lineB) { return addLinePair(*impl_, ConstraintKind::Perpendicular, lineA, lineB); }

namespace {
int addLinePair(Sketch::Impl& impl, ConstraintKind kind, int a, int b) {
    impl.entity(a, EntityKind::Line);
    impl.entity(b, EntityKind::Line);
    require(a != b, "A line cannot be constrained to itself");
    Constraint c{kind};
    c.entity = a;
    c.entity2 = b;
    return impl.addConstraint(c);
}
}  // namespace

int Sketch::addDistance(PointRef a, PointRef b, double distance) {
    impl_->point(a);
    impl_->point(b);
    require(a.entity != b.entity || a.role != b.role, "Distance needs two different points");
    require(finite(distance) && distance > 0.0, "Distance must be positive and finite");
    Constraint c{ConstraintKind::Distance};
    c.a = a;
    c.b = b;
    c.value = impl_->store(distance);
    return impl_->addConstraint(c);
}

int Sketch::addRadius(int circle, double radius) {
    impl_->entity(circle, EntityKind::Circle);
    require(finite(radius) && radius > 0.0, "Radius must be positive and finite");
    Constraint c{ConstraintKind::Radius};
    c.entity = circle;
    c.value = impl_->store(radius);
    return impl_->addConstraint(c);
}

int Sketch::addFixed(PointRef point) {
    const GCS::Point p = impl_->point(point);
    Constraint c{ConstraintKind::Fixed};
    c.a = point;
    c.value = impl_->store(*p.x);
    c.value2 = impl_->store(*p.y);
    return impl_->addConstraint(c);
}

void Sketch::removeConstraint(int constraint) { impl_->constraint(constraint).alive = false; }

void Sketch::setDimension(int constraint, double value) {
    Constraint& c = impl_->constraint(constraint);
    require(c.kind == ConstraintKind::Distance || c.kind == ConstraintKind::Radius,
            "Constraint has no dimension value");
    require(finite(value) && value > 0.0, "Dimension must be positive and finite");
    *c.value = value;
}

void Sketch::setPoint(PointRef point, double x, double y) {
    require(finite(x) && finite(y), "Coordinates must be finite");
    const GCS::Point p = impl_->point(point);
    *p.x = x;
    *p.y = y;
}

void Sketch::setCircleRadius(int circle, double radius) {
    require(finite(radius) && radius > 0.0, "Radius must be positive and finite");
    *impl_->entity(circle, EntityKind::Circle).p[2] = radius;
}

SolveResult Sketch::solve() {
    try {
        return impl_->solve(nullptr, 0.0, 0.0);
    } catch (const SketchError&) {
        throw;
    } catch (const std::exception& e) {
        throw SketchError(ErrorCode::SolverFailure, std::string("solve: ") + e.what());
    }
}

SolveResult Sketch::solveDragging(PointRef point, double x, double y) {
    require(finite(x) && finite(y), "Coordinates must be finite");
    impl_->point(point);
    try {
        return impl_->solve(&point, x, y);
    } catch (const SketchError&) {
        throw;
    } catch (const std::exception& e) {
        throw SketchError(ErrorCode::SolverFailure, std::string("solveDragging: ") + e.what());
    }
}

std::vector<EntityGeometry> Sketch::geometry() const {
    std::vector<EntityGeometry> out;
    for (size_t i = 0; i < impl_->entities.size(); ++i) {
        const Entity& e = impl_->entities[i];
        if (!e.alive) continue;
        EntityGeometry g{static_cast<int>(i), e.kind, *e.p[0], *e.p[1], *e.p[2], 0.0};
        if (e.kind == EntityKind::Line) g.d = *e.p[3];
        out.push_back(g);
    }
    return out;
}

}  // namespace cad::sketch
