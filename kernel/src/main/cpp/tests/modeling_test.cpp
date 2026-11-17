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

TEST(Modeling, CompoundKeepsAllBodies) {
    const auto a = Shape::makeBox(1, 1, 1);
    const auto b = Shape::makeBox(2, 2, 2);
    const auto both = Shape::compound({a.get(), b.get()});
    EXPECT_EQ(both->faceCount(), 12);
    EXPECT_NEAR(both->volume(), 9.0, 1e-9);
}
