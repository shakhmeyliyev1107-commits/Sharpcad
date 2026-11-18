#include <cmath>

#include <gtest/gtest.h>

#include "cad/kernel/error.h"
#include "cad/kernel/shape.h"

using namespace cad::kernel;

namespace {

SketchProfileInput xyPlane() {
    SketchProfileInput in{};
    in.xAxis[0] = 1.0;
    in.yAxis[1] = 1.0;
    return in;
}

void addRect(SketchProfileInput& in, double x0, double y0, double x1, double y1) {
    in.lines.insert(in.lines.end(), {x0, y0, x1, y0, x1, y0, x1, y1, x1, y1, x0, y1, x0, y1, x0, y0});
}

template <typename Fn>
void expectError(ErrorCode code, Fn&& fn) {
    try {
        fn();
        ADD_FAILURE() << "expected KernelError";
    } catch (const KernelError& e) {
        EXPECT_EQ(e.code(), code) << e.what();
    }
}

constexpr double kPi = 3.14159265358979323846;

}  // namespace

TEST(Profile, RectangleGivesOneRegion) {
    auto in = xyPlane();
    addRect(in, 0, 0, 40, 20);
    const auto profile = Shape::profileFromSketch(in);
    EXPECT_EQ(profile->faceCount(), 1);
}

TEST(Profile, CircleInsideRectangleGivesRingAndDisc) {
    auto in = xyPlane();
    addRect(in, 0, 0, 40, 40);
    in.circles = {20, 20, 5};
    const auto profile = Shape::profileFromSketch(in);
    ASSERT_EQ(profile->faceCount(), 2);
    const int disc = profile->regionAt(in, 20, 20);
    const int ring = profile->regionAt(in, 2, 2);
    EXPECT_NE(disc, -1);
    EXPECT_NE(ring, -1);
    EXPECT_NE(disc, ring);
    EXPECT_EQ(profile->regionAt(in, 100, 100), -1);

    // Extruding only the ring leaves a hole.
    const auto block = profile->subset({ring})->extrude(0, 0, 10);
    EXPECT_NEAR(block->volume(), (40.0 * 40.0 - kPi * 25.0) * 10.0, 1e-3);
}

TEST(Profile, CrossingLinesCloseARegion) {
    // Two overshooting lines and two more form a closed quad only after splitting.
    auto in = xyPlane();
    in.lines = {-5, 0, 45, 0,   40, -5, 40, 25,   45, 20, -5, 20,   0, 25, 0, -5};
    const auto profile = Shape::profileFromSketch(in);
    ASSERT_GE(profile->faceCount(), 1);
    const int inner = profile->regionAt(in, 20, 10);
    ASSERT_NE(inner, -1);
    EXPECT_NEAR(profile->subset({inner})->extrude(0, 0, 1)->volume(), 800.0, 1e-6);
}

TEST(Profile, SingleCircleGivesADisc) {
    auto in = xyPlane();
    in.circles = {0, 0, 5};
    EXPECT_NEAR(Shape::profileFromSketch(in)->extrude(0, 0, 2)->volume(), kPi * 25.0 * 2.0, 1e-3);
}

TEST(Profile, LineAndArcCloseADShape) {
    auto in = xyPlane();
    in.lines = {-10, 0, 10, 0};
    in.arcs = {0, 0, 10, 0, kPi};  // upper half circle closes the diameter line
    EXPECT_NEAR(Shape::profileFromSketch(in)->extrude(0, 0, 1)->volume(), kPi * 100.0 / 2.0, 1e-3);
}

TEST(Profile, OpenSketchIsRejected) {
    auto in = xyPlane();
    in.lines = {0, 0, 10, 0, 10, 0, 10, 10};
    expectError(ErrorCode::InvalidArgument, [&] { Shape::profileFromSketch(in); });
    SketchProfileInput empty = xyPlane();
    expectError(ErrorCode::InvalidArgument, [&] { Shape::profileFromSketch(empty); });
}

TEST(Profile, WorksOnATiltedPlane) {
    SketchProfileInput in{};
    in.origin[2] = 5.0;
    in.xAxis[0] = 1.0;  // XZ-like plane through z = 5
    in.yAxis[2] = 1.0;
    addRect(in, 0, 0, 10, 10);
    const auto solid = Shape::profileFromSketch(in)->extrude(0, -3, 0);
    EXPECT_NEAR(solid->volume(), 300.0, 1e-6);
    const auto b = solid->boundingBox();
    EXPECT_NEAR(b.min[2], 5.0, 1e-6);
    EXPECT_NEAR(b.max[2], 15.0, 1e-6);
    EXPECT_NEAR(b.min[1], -3.0, 1e-6);
}

TEST(Modeling, ExtrudeRectangleVolume) {
    auto in = xyPlane();
    addRect(in, 0, 0, 40, 20);
    EXPECT_NEAR(Shape::profileFromSketch(in)->extrude(0, 0, 10)->volume(), 8000.0, 1e-6);
    expectError(ErrorCode::InvalidArgument, [&] { Shape::profileFromSketch(in)->extrude(0, 0, 0); });
}

TEST(Modeling, RevolveRectangleMakesATube) {
    auto in = xyPlane();
    addRect(in, 10, 0, 20, 5);  // annulus 10..20 around the Y axis, 5 high
    const double origin[3] = {0, 0, 0};
    const double axis[3] = {0, 1, 0};
    const auto tube = Shape::profileFromSketch(in)->revolve(origin, axis, 2 * kPi);
    EXPECT_NEAR(tube->volume(), kPi * (400.0 - 100.0) * 5.0, 1e-3);
    const auto half = Shape::profileFromSketch(in)->revolve(origin, axis, kPi);
    EXPECT_NEAR(half->volume(), kPi * 300.0 * 5.0 / 2.0, 1e-3);
    expectError(ErrorCode::InvalidArgument, [&] { Shape::profileFromSketch(in)->revolve(origin, axis, 0.0); });
}

TEST(Modeling, BooleansOfOverlappingBoxes) {
    const auto a = Shape::makeBox(10, 10, 10);
    auto in = xyPlane();
    addRect(in, 5, 0, 15, 10);
    const auto b = Shape::profileFromSketch(in)->extrude(0, 0, 10);
    EXPECT_NEAR(a->booleanWith(*b, BooleanOp::Union)->volume(), 1500.0, 1e-6);
    EXPECT_NEAR(a->booleanWith(*b, BooleanOp::Subtract)->volume(), 500.0, 1e-6);
    EXPECT_NEAR(a->booleanWith(*b, BooleanOp::Intersect)->volume(), 500.0, 1e-6);
    // Union of two touching-overlapping boxes is tidied back into a single box: 6 faces.
    EXPECT_EQ(a->booleanWith(*b, BooleanOp::Union)->faceCount(), 6);
}

TEST(Modeling, FilletAndChamferRemoveMaterial) {
    const auto box = Shape::makeBox(20, 20, 20);
    const double full = box->volume();
    const auto rounded = box->fillet(2.0);
    EXPECT_LT(rounded->volume(), full);
    EXPECT_GT(rounded->faceCount(), 6);
    const auto bevelled = box->chamfer(2.0);
    EXPECT_LT(bevelled->volume(), full);
    EXPECT_EQ(bevelled->faceCount(), 6 + 12 + 8);  // a face per edge and per corner

    // Only the four edges of one face.
    const auto oneFace = box->chamfer(2.0, 0);
    EXPECT_EQ(oneFace->faceCount(), 6 + 4);
    expectError(ErrorCode::InvalidArgument, [&] { box->fillet(-1.0); });
    expectError(ErrorCode::InvalidArgument, [&] { box->fillet(1.0, 99); });
    expectError(ErrorCode::OcctFailure, [&] { box->fillet(50.0); });
}

TEST(Modeling, PickFindsTheNearestFace) {
    const auto box = Shape::makeBox(10, 10, 10);
    const double origin[3] = {5, 5, 100};
    const double down[3] = {0, 0, -1};
    const auto hit = box->pick(origin, down);
    ASSERT_GE(hit.face, 0);
    EXPECT_NEAR(hit.distance, 90.0, 1e-6);
    const double away[3] = {0, 0, 1};
    EXPECT_EQ(box->pick(origin, away).face, -1);
}

TEST(Modeling, PushPullTopFaceChangesHeight) {
    const auto box = Shape::makeBox(10, 10, 10);
    const double origin[3] = {5, 5, 100};
    const double down[3] = {0, 0, -1};
    const int top = box->pick(origin, down).face;
    ASSERT_GE(top, 0);

    const auto taller = box->pushPull(top, 5.0);
    EXPECT_NEAR(taller->volume(), 1500.0, 1e-6);
    EXPECT_NEAR(taller->boundingBox().max[2], 15.0, 1e-6);
    EXPECT_EQ(taller->faceCount(), 6);  // side faces merged back

    const auto shorter = box->pushPull(top, -4.0);
    EXPECT_NEAR(shorter->volume(), 600.0, 1e-6);
    EXPECT_NEAR(shorter->boundingBox().max[2], 6.0, 1e-6);
}

TEST(Modeling, PushPullRejectsCurvedFaces) {
    auto in = xyPlane();
    in.circles = {0, 0, 5};
    const auto cylinder = Shape::profileFromSketch(in)->extrude(0, 0, 10);
    const double origin[3] = {100, 0, 5};
    const double ray[3] = {-1, 0, 0};
    const int side = cylinder->pick(origin, ray).face;
    ASSERT_GE(side, 0);
    expectError(ErrorCode::InvalidArgument, [&] { cylinder->pushPull(side, 2.0); });
}

TEST(Modeling, TessellationReportsFacePerTriangle) {
    const auto box = Shape::makeBox(10, 10, 10);
    const auto mesh = box->tessellate(0.1, 0.35);
    ASSERT_EQ(mesh.triangleFaces.size(), mesh.indices.size() / 3);
    for (int f : mesh.triangleFaces) {
        EXPECT_GE(f, 0);
        EXPECT_LT(f, box->faceCount());
    }
}

TEST(Modeling, DistanceBetweenShapes) {
    const auto a = Shape::makeBox(10, 10, 10);
    auto in = xyPlane();
    addRect(in, 15, 0, 20, 5);
    const auto apart = Shape::profileFromSketch(in)->extrude(0, 0, 1);
    EXPECT_NEAR(a->distanceTo(*apart), 5.0, 1e-6);
    auto touching = xyPlane();
    addRect(touching, 10, 0, 12, 5);
    EXPECT_NEAR(a->distanceTo(*Shape::profileFromSketch(touching)->extrude(0, 0, 1)), 0.0, 1e-6);
}

namespace {
void extent(const std::vector<float>& segs, float out[4]) {
    out[0] = out[1] = 1e9f;
    out[2] = out[3] = -1e9f;
    for (size_t i = 0; i < segs.size(); i += 2) {
        out[0] = std::min(out[0], segs[i]);
        out[1] = std::min(out[1], segs[i + 1]);
        out[2] = std::max(out[2], segs[i]);
        out[3] = std::max(out[3], segs[i + 1]);
    }
}
}  // namespace

TEST(Drawing, FrontViewOfABoxIsItsWidthByHeight) {
    const auto box = Shape::makeBox(10, 20, 30);
    const double front[3] = {0, -1, 0};  // viewer in front (-Y), looking at +Y
    const double up[3] = {0, 0, 1};
    const auto view = box->project(front, up, 0.05);
    ASSERT_FALSE(view.visible.empty());
    float e[4];
    extent(view.visible, e);
    EXPECT_NEAR(e[2] - e[0], 10.0f, 1e-3f);
    EXPECT_NEAR(e[3] - e[1], 30.0f, 1e-3f);
    EXPECT_EQ(view.visible.size() % 4, 0u);
}

TEST(Drawing, HiddenEdgesAppearForAHoleSeenFromTheSide) {
    auto in = xyPlane();
    addRect(in, 0, 0, 40, 40);
    in.circles = {20, 20, 5};
    auto profile = Shape::profileFromSketch(in);
    const auto plate = profile->subset({profile->regionAt(in, 1, 1)})->extrude(0, 0, 10);
    const double front[3] = {0, -1, 0};
    const double up[3] = {0, 0, 1};
    EXPECT_FALSE(plate->project(front, up, 0.05).hidden.empty());
    const double top[3] = {0, 0, 1};
    const double north[3] = {0, 1, 0};
    // From above, hidden edges (bottom outline, hole bottom) lie under visible ones.
    const auto plan = plate->project(top, north, 0.05);
    float v[4], h[4];
    extent(plan.visible, v);
    extent(plan.hidden, h);
    EXPECT_GE(h[0], v[0] - 1e-3f);
    EXPECT_LE(h[2], v[2] + 1e-3f);
    const double bad[3] = {0, 0, 0};
    expectError(ErrorCode::InvalidArgument, [&] { plate->project(bad, up, 0.05); });
    expectError(ErrorCode::InvalidArgument, [&] { plate->project(top, top, 0.05); });
}

TEST(Modeling, ShellOpensAFaceAndKeepsTheOuterSize) {
    const auto box = Shape::makeBox(20, 20, 20);
    const double origin[3] = {10, 10, 100};
    const double down[3] = {0, 0, -1};
    const int top = box->pick(origin, down).face;
    const auto cup = box->shell(top, 2.0);
    EXPECT_NEAR(cup->volume(), 8000.0 - 16.0 * 16.0 * 18.0, 1e-3);
    EXPECT_NEAR(cup->boundingBox().max[0], 20.0, 1e-6);
    EXPECT_NEAR(box->faceArea(top), 400.0, 1e-6);
    expectError(ErrorCode::InvalidArgument, [&] { box->shell(top, 0.0); });
    expectError(ErrorCode::InvalidArgument, [&] { box->faceArea(99); });
}

TEST(Modeling, MoveRotateAndMirrorKeepTheVolume) {
    const auto box = Shape::makeBox(10, 20, 30);
    const double origin[3] = {0, 0, 0};
    const double z[3] = {0, 0, 1};
    const auto moved = box->moved(origin, z, kPi / 2, 100, 0, 0);  // 90° about Z, then +100 X
    auto b = moved->boundingBox();
    EXPECT_NEAR(b.min[0], 80.0, 1e-6);  // y extent 20 became -x
    EXPECT_NEAR(b.max[0], 100.0, 1e-6);
    EXPECT_NEAR(b.max[1], 10.0, 1e-6);
    EXPECT_NEAR(moved->volume(), 6000.0, 1e-6);
    const double yz[3] = {1, 0, 0};
    const auto mirror = box->mirrored(origin, yz);
    b = mirror->boundingBox();
    EXPECT_NEAR(b.min[0], -10.0, 1e-6);
    EXPECT_NEAR(b.max[0], 0.0, 1e-6);
    EXPECT_NEAR(mirror->volume(), 6000.0, 1e-6);
    const double zero[3] = {0, 0, 0};
    expectError(ErrorCode::InvalidArgument, [&] { box->mirrored(origin, zero); });
}

TEST(Modeling, CompoundKeepsAllBodies) {
    const auto a = Shape::makeBox(1, 1, 1);
    const auto b = Shape::makeBox(2, 2, 2);
    const auto both = Shape::compound({a.get(), b.get()});
    EXPECT_EQ(both->faceCount(), 12);
    EXPECT_NEAR(both->volume(), 9.0, 1e-9);
}

TEST(Loft, TwoEqualSquaresMakeABox) {
    auto bottom = xyPlane();
    addRect(bottom, 0, 0, 10, 10);
    auto top = xyPlane();
    top.origin[2] = 10.0;
    addRect(top, 0, 0, 10, 10);
    const auto a = Shape::profileFromSketch(bottom);
    const auto b = Shape::profileFromSketch(top);
    EXPECT_NEAR(Shape::loft({a.get(), b.get()})->volume(), 1000.0, 1e-3);
    expectError(ErrorCode::InvalidArgument, [&] { Shape::loft({a.get()}); });
}

TEST(Sweep, SquareAlongStraightPath) {
    auto profile = xyPlane();
    addRect(profile, -1, -1, 1, 1);
    SketchProfileInput path{};
    path.xAxis[0] = 1.0;  // XZ plane
    path.yAxis[2] = 1.0;
    path.lines = {0, 0, 0, 10};  // straight up z
    EXPECT_NEAR(Shape::profileFromSketch(profile)->sweep(path)->volume(), 40.0, 1e-3);
}

TEST(Modeling, DraftTiltsASideFace) {
    const auto box = Shape::makeBox(10, 10, 10);
    // Side face x = 10: pick it with a ray along -X.
    const double origin[3] = {100, 5, 5};
    const double dir[3] = {-1, 0, 0};
    const int side = box->pick(origin, dir).face;
    ASSERT_GE(side, 0);
    const double up[3] = {0, 0, 1};
    const double base[3] = {0, 0, 0};
    const auto drafted = box->draft(side, 10.0 * M_PI / 180.0, up, base);
    EXPECT_LT(drafted->volume(), 1000.0);  // leans inwards going up
    EXPECT_GT(drafted->volume(), 900.0);
    const double top[3] = {5, 5, 100};
    const double down[3] = {0, 0, -1};
    expectError(ErrorCode::OcctFailure, [&] { box->draft(box->pick(top, down).face, 0.2, up, base); });
}

TEST(Modeling, ScaledMultipliesVolumeByCube) {
    const auto box = Shape::makeBox(10, 10, 10);
    const double c[3] = {5, 5, 5};
    EXPECT_NEAR(box->scaled(c, 2.0)->volume(), 8000.0, 1e-6);
    expectError(ErrorCode::InvalidArgument, [&] { box->scaled(c, 0.0); });
}

TEST(Modeling, PickEdgeAndBlendOneEdge) {
    const auto box = Shape::makeBox(10, 10, 10);
    // Looking down at the top face, aim just inside the edge y = 0, z = 10.
    const double origin[3] = {5, 0.2, 100};
    const double down[3] = {0, 0, -1};
    const int edge = box->pickEdge(origin, down, 0.5);
    ASSERT_GE(edge, 0);
    const auto points = box->edgePoints(edge);
    ASSERT_GE(points.size(), 6u);
    for (size_t i = 0; i < points.size(); i += 3) {
        EXPECT_NEAR(points[i + 1], 0.0, 1e-6);
        EXPECT_NEAR(points[i + 2], 10.0, 1e-6);
    }
    EXPECT_EQ(box->pickEdge(origin, down, 0.1), -1);

    const auto rounded = box->blendEdge(2.0, edge, false);
    EXPECT_EQ(rounded->faceCount(), 7);
    EXPECT_LT(rounded->volume(), box->volume());
    const auto bevelled = box->blendEdge(2.0, edge, true);
    EXPECT_EQ(bevelled->faceCount(), 7);
    expectError(ErrorCode::InvalidArgument, [&] { box->blendEdge(1.0, 99, false); });
}

TEST(Modeling, SplineClosesAProfileSmoothly) {
    // A straight base line plus a spline arching over it from (0,0) to (20,0).
    SketchProfileInput in{{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 20, 0}, {}, {}, {4, 0, 0, 5, 8, 15, 8, 20, 0}};
    const auto profile = Shape::profileFromSketch(in);
    EXPECT_EQ(profile->faceCount(), 1);
    const auto solid = profile->extrude(0, 0, 5);
    // Smooth: the solid has the base, top, bottom and one curved side face, not many flats.
    EXPECT_EQ(solid->faceCount(), 4);
    EXPECT_GT(solid->volume(), 0.0);

    SketchProfileInput bad{{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {}, {}, {}, {9, 0, 0}};
    expectError(ErrorCode::InvalidArgument, [&] { Shape::profileFromSketch(bad); });
}
