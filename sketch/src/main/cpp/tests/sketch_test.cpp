#include <cmath>
#include <limits>

#include <gtest/gtest.h>

#include "cad/sketch/sketch.h"

using namespace cad::sketch;

namespace {

constexpr double kTol = 1e-7;

EntityGeometry geometryOf(const Sketch& sketch, int id) {
    for (const auto& g : sketch.geometry()) {
        if (g.id == id) return g;
    }
    ADD_FAILURE() << "entity " << id << " not found";
    return {};
}

double length(const EntityGeometry& line) { return std::hypot(line.c - line.a, line.d - line.b); }

template <typename Fn>
void expectInvalid(Fn&& fn) {
    try {
        fn();
        ADD_FAILURE() << "expected SketchError";
    } catch (const SketchError& e) {
        EXPECT_EQ(e.code(), ErrorCode::InvalidArgument) << e.what();
    }
}

// A roughly drawn rectangle: four lines, corners joined, sides levelled.
struct Rectangle {
    Sketch sketch;
    int bottom, right, top, left;

    Rectangle() {
        bottom = sketch.addLine(0.3, -0.2, 9.1, 0.4);
        right = sketch.addLine(9.4, 0.1, 9.8, 6.3);
        top = sketch.addLine(10.2, 5.9, -0.3, 6.2);
        left = sketch.addLine(0.2, 6.4, -0.1, 0.3);
        sketch.addCoincident({bottom, PointRole::End}, {right, PointRole::Start});
        sketch.addCoincident({right, PointRole::End}, {top, PointRole::Start});
        sketch.addCoincident({top, PointRole::End}, {left, PointRole::Start});
        sketch.addCoincident({left, PointRole::End}, {bottom, PointRole::Start});
        sketch.addHorizontal(bottom);
        sketch.addHorizontal(top);
        sketch.addVertical(right);
        sketch.addVertical(left);
    }
};

}  // namespace

TEST(SketchSolve, UnconstrainedLineHasFourDegreesOfFreedom) {
    Sketch sketch;
    sketch.addLine(0, 0, 10, 5);
    const auto result = sketch.solve();
    EXPECT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 4);
}

TEST(SketchSolve, RoughRectangleBecomesExact) {
    Rectangle r;
    const auto result = r.sketch.solve();
    ASSERT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 4);  // position (2) + width + height

    const auto bottom = geometryOf(r.sketch, r.bottom);
    const auto right = geometryOf(r.sketch, r.right);
    const auto top = geometryOf(r.sketch, r.top);
    const auto left = geometryOf(r.sketch, r.left);
    EXPECT_NEAR(bottom.b, bottom.d, kTol);  // horizontal
    EXPECT_NEAR(top.b, top.d, kTol);
    EXPECT_NEAR(right.a, right.c, kTol);    // vertical
    EXPECT_NEAR(left.a, left.c, kTol);
    EXPECT_NEAR(bottom.c, right.a, kTol);   // corners closed
    EXPECT_NEAR(bottom.d, right.b, kTol);
    EXPECT_NEAR(left.c, bottom.a, kTol);
    EXPECT_NEAR(left.d, bottom.b, kTol);
}

TEST(SketchSolve, DimensionedAndPinnedRectangleIsFullyConstrained) {
    Rectangle r;
    const int width = r.sketch.addDistance({r.bottom, PointRole::Start}, {r.bottom, PointRole::End}, 40.0);
    r.sketch.addDistance({r.right, PointRole::Start}, {r.right, PointRole::End}, 25.0);
    r.sketch.setPoint({r.bottom, PointRole::Start}, 0.0, 0.0);
    r.sketch.addFixed({r.bottom, PointRole::Start});

    auto result = r.sketch.solve();
    ASSERT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 0);
    EXPECT_TRUE(result.conflicting.empty());

    const auto top = geometryOf(r.sketch, r.top);
    EXPECT_NEAR(top.a, 40.0, 1e-6);
    EXPECT_NEAR(top.b, 25.0, 1e-6);
    EXPECT_NEAR(top.c, 0.0, 1e-6);
    EXPECT_NEAR(top.d, 25.0, 1e-6);

    // Editing a dimension later re-solves to the new size.
    r.sketch.setDimension(width, 60.0);
    result = r.sketch.solve();
    ASSERT_EQ(result.status, SolveStatus::Solved);
    EXPECT_NEAR(length(geometryOf(r.sketch, r.bottom)), 60.0, 1e-6);
    EXPECT_NEAR(length(geometryOf(r.sketch, r.right)), 25.0, 1e-6);
}

TEST(SketchSolve, ContradictingDimensionsAreReportedAndGeometryIsKept) {
    Sketch sketch;
    const int line = sketch.addLine(0, 0, 10, 0);
    const int first = sketch.addDistance({line, PointRole::Start}, {line, PointRole::End}, 10.0);
    const int second = sketch.addDistance({line, PointRole::Start}, {line, PointRole::End}, 20.0);

    const auto result = sketch.solve();
    EXPECT_EQ(result.status, SolveStatus::Conflicting);
    ASSERT_FALSE(result.conflicting.empty());
    for (int id : result.conflicting) EXPECT_TRUE(id == first || id == second);
    EXPECT_NEAR(length(geometryOf(sketch, line)), 10.0, kTol);

    sketch.removeConstraint(second);
    EXPECT_EQ(sketch.solve().status, SolveStatus::Solved);
}

TEST(SketchSolve, DuplicateConstraintIsRedundantNotConflicting) {
    Sketch sketch;
    const int line = sketch.addLine(0, 0, 10, 1);
    sketch.addHorizontal(line);
    const int again = sketch.addHorizontal(line);
    const auto result = sketch.solve();
    EXPECT_EQ(result.status, SolveStatus::Solved);
    ASSERT_EQ(result.redundant.size(), 1u);
    EXPECT_EQ(result.redundant[0], again);
    EXPECT_EQ(result.degreesOfFreedom, 3);
}

TEST(SketchSolve, RadiusConstraintResizesCircleAroundItsCentre) {
    Sketch sketch;
    const int circle = sketch.addCircle(5.0, -3.0, 2.0);
    sketch.addFixed({circle, PointRole::Center});
    sketch.addRadius(circle, 12.5);
    const auto result = sketch.solve();
    ASSERT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 0);
    const auto g = geometryOf(sketch, circle);
    EXPECT_NEAR(g.a, 5.0, kTol);
    EXPECT_NEAR(g.b, -3.0, kTol);
    EXPECT_NEAR(g.c, 12.5, kTol);
}

TEST(SketchDrag, PointFollowsTargetAsFarAsConstraintsAllow) {
    Sketch sketch;
    const int line = sketch.addLine(0, 0, 10, 0);
    sketch.addFixed({line, PointRole::Start});
    sketch.addDistance({line, PointRole::Start}, {line, PointRole::End}, 10.0);

    // Target is 30 mm straight up: the end can only swing on its 10 mm circle.
    const auto result = sketch.solveDragging({line, PointRole::End}, 0.0, 30.0);
    ASSERT_EQ(result.status, SolveStatus::Solved);
    const auto g = geometryOf(sketch, line);
    EXPECT_NEAR(g.a, 0.0, 1e-6);
    EXPECT_NEAR(g.b, 0.0, 1e-6);
    EXPECT_NEAR(g.c, 0.0, 1e-4);
    EXPECT_NEAR(g.d, 10.0, 1e-4);
}

TEST(SketchDrag, FreePointLandsExactlyOnTarget) {
    Sketch sketch;
    const int line = sketch.addLine(0, 0, 10, 0);
    ASSERT_EQ(sketch.solveDragging({line, PointRole::End}, 7.0, 4.0).status, SolveStatus::Solved);
    const auto g = geometryOf(sketch, line);
    EXPECT_NEAR(g.c, 7.0, 1e-6);
    EXPECT_NEAR(g.d, 4.0, 1e-6);
    EXPECT_NEAR(g.a, 0.0, 1e-6);  // the other end stays put
}

TEST(SketchEdit, RemovingAnEntityDropsItsConstraints) {
    Sketch sketch;
    const int a = sketch.addLine(0, 0, 10, 0);
    const int b = sketch.addLine(10, 0, 10, 10);
    sketch.addCoincident({a, PointRole::End}, {b, PointRole::Start});
    EXPECT_EQ(sketch.solve().degreesOfFreedom, 6);

    sketch.removeEntity(b);
    const auto result = sketch.solve();
    EXPECT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 4);
    EXPECT_EQ(sketch.geometry().size(), 1u);
    expectInvalid([&] { sketch.addHorizontal(b); });
    EXPECT_EQ(sketch.addLine(0, 0, 1, 1), 2);  // ids are not reused
}

TEST(SketchSolve, EqualParallelPerpendicular) {
    Sketch sketch;
    const int a = sketch.addLine(0, 0, 10, 0);
    const int b = sketch.addLine(0, 5, 7, 6);
    const int c = sketch.addLine(0, 0, 1, 8);
    sketch.addFixed({a, PointRole::Start});
    sketch.addFixed({a, PointRole::End});
    sketch.addEqualLength(a, b);
    sketch.addParallel(a, b);
    sketch.addPerpendicular(a, c);
    ASSERT_EQ(sketch.solve().status, SolveStatus::Solved);
    const auto gb = geometryOf(sketch, b);
    const auto gc = geometryOf(sketch, c);
    EXPECT_NEAR(length(gb), 10.0, 1e-6);
    EXPECT_NEAR(gb.b, gb.d, 1e-6);  // parallel to the horizontal line a
    EXPECT_NEAR(gc.a, gc.c, 1e-6);  // perpendicular: vertical
    expectInvalid([&] { sketch.addParallel(a, a); });
    const int circle = sketch.addCircle(0, 0, 1);
    expectInvalid([&] { sketch.addEqualLength(a, circle); });
    sketch.removeEntity(b);  // drops its two-line constraints too
    EXPECT_EQ(sketch.solve().status, SolveStatus::Solved);
}

TEST(SketchArc, EndpointsFollowRadiusAndConnectToLines) {
    Sketch sketch;
    const int arc = sketch.addArc(0, 0, 10, 0, M_PI / 2);  // quarter arc from (10,0) to (0,10)
    const int line = sketch.addLine(10, 0, 30, 0);
    sketch.addCoincident({arc, PointRole::Start}, {line, PointRole::Start});
    sketch.addFixed({arc, PointRole::Center});
    sketch.addRadius(arc, 15.0);
    const auto result = sketch.solve();
    ASSERT_EQ(result.status, SolveStatus::Solved);
    const auto g = geometryOf(sketch, arc);
    EXPECT_NEAR(g.c, 15.0, 1e-6);
    const auto l = geometryOf(sketch, line);
    EXPECT_NEAR(std::hypot(l.a - g.a, l.b - g.b), 15.0, 1e-6);  // line start sits on the arc
    expectInvalid([&] { sketch.addArc(0, 0, -1, 0, 1); });
    expectInvalid([&] { sketch.addHorizontal(arc); });
}

TEST(SketchEdit, EmptySketchSolves) {
    Sketch sketch;
    const auto result = sketch.solve();
    EXPECT_EQ(result.status, SolveStatus::Solved);
    EXPECT_EQ(result.degreesOfFreedom, 0);
}

TEST(SketchValidation, RejectsBadArguments) {
    Sketch sketch;
    const double nan = std::nan("");
    const double inf = std::numeric_limits<double>::infinity();
    expectInvalid([&] { sketch.addLine(nan, 0, 1, 1); });
    expectInvalid([&] { sketch.addLine(0, 0, inf, 1); });
    expectInvalid([&] { sketch.addCircle(0, 0, 0.0); });
    expectInvalid([&] { sketch.addCircle(0, 0, -1.0); });

    const int line = sketch.addLine(0, 0, 10, 0);
    const int circle = sketch.addCircle(0, 0, 5);
    expectInvalid([&] { sketch.addHorizontal(circle); });
    expectInvalid([&] { sketch.addRadius(line, 5.0); });
    expectInvalid([&] { sketch.addHorizontal(99); });
    expectInvalid([&] { sketch.addHorizontal(-1); });
    expectInvalid([&] { sketch.addCoincident({line, PointRole::Center}, {circle, PointRole::Center}); });
    expectInvalid([&] { sketch.addCoincident({circle, PointRole::Start}, {line, PointRole::End}); });
    expectInvalid([&] { sketch.addCoincident({line, PointRole::End}, {line, PointRole::End}); });
    expectInvalid([&] { sketch.addDistance({line, PointRole::Start}, {line, PointRole::End}, 0.0); });
    expectInvalid([&] { sketch.addDistance({line, PointRole::Start}, {line, PointRole::End}, nan); });
    expectInvalid([&] { sketch.setPoint({line, PointRole::Start}, nan, 0.0); });
    expectInvalid([&] { sketch.setCircleRadius(circle, -2.0); });
    expectInvalid([&] { sketch.removeConstraint(0); });
    expectInvalid([&] { sketch.solveDragging({line, PointRole::End}, inf, 0.0); });

    const int horizontal = sketch.addHorizontal(line);
    expectInvalid([&] { sketch.setDimension(horizontal, 5.0); });
    sketch.removeConstraint(horizontal);
    expectInvalid([&] { sketch.removeConstraint(horizontal); });
}
