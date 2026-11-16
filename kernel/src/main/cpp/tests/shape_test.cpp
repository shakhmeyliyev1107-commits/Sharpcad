#include <cmath>
#include <cstdio>
#include <fstream>
#include <limits>
#include <string>

#include <gtest/gtest.h>

#include "cad/kernel/error.h"
#include "cad/kernel/shape.h"

using cad::kernel::ErrorCode;
using cad::kernel::KernelError;
using cad::kernel::Shape;

namespace {

std::string tempPath(const char* name) { return ::testing::TempDir() + name; }

template <typename Fn>
void expectError(ErrorCode code, Fn&& fn) {
    try {
        fn();
        ADD_FAILURE() << "expected KernelError";
    } catch (const KernelError& e) {
        EXPECT_EQ(e.code(), code) << e.what();
    }
}

}  // namespace

TEST(OcctVersion, IsNotEmpty) { EXPECT_FALSE(cad::kernel::occtVersion().empty()); }

TEST(ShapeBox, BoundingBoxMatchesDimensions) {
    const auto box = Shape::makeBox(10.0, 20.0, 30.0);
    const auto b = box->boundingBox();
    EXPECT_NEAR(b.min[0], 0.0, 1e-9);
    EXPECT_NEAR(b.min[1], 0.0, 1e-9);
    EXPECT_NEAR(b.min[2], 0.0, 1e-9);
    EXPECT_NEAR(b.max[0], 10.0, 1e-9);
    EXPECT_NEAR(b.max[1], 20.0, 1e-9);
    EXPECT_NEAR(b.max[2], 30.0, 1e-9);
}

TEST(ShapeBox, RejectsInvalidDimensions) {
    const double bad[] = {0.0, -5.0, 1e-9, std::nan(""),
                          std::numeric_limits<double>::infinity()};
    for (double v : bad) {
        for (int axis = 0; axis < 3; ++axis) {
            double d[3] = {1.0, 1.0, 1.0};
            d[axis] = v;
            expectError(ErrorCode::InvalidArgument, [&] { Shape::makeBox(d[0], d[1], d[2]); });
        }
    }
}

TEST(ShapeBox, HandlesExtremeAspectRatio) {
    const auto b = Shape::makeBox(1e-3, 1e4, 1.0)->boundingBox();
    EXPECT_NEAR(b.max[0], 1e-3, 1e-9);
    EXPECT_NEAR(b.max[1], 1e4, 1e-6);
}

TEST(Tessellate, BoxHasTwelveTrianglesAndTwelveEdges) {
    const auto mesh = Shape::makeBox(10.0, 20.0, 30.0)->tessellate(0.1, 0.35);
    EXPECT_EQ(mesh.indices.size(), 12u * 3u);
    EXPECT_EQ(mesh.positions.size(), 24u * 3u);  // 4 vertices per face, not shared
    EXPECT_EQ(mesh.normals.size(), mesh.positions.size());
    EXPECT_EQ(mesh.edgeSegments.size(), 12u * 2u * 3u);  // each edge once
    for (auto index : mesh.indices) EXPECT_LT(index, mesh.positions.size() / 3);
}

TEST(Tessellate, NormalsAreUnitAndPointOutwards) {
    const auto mesh = Shape::makeBox(10.0, 20.0, 30.0)->tessellate(0.1, 0.35);
    const float centre[3] = {5.0f, 10.0f, 15.0f};
    for (size_t i = 0; i < mesh.positions.size(); i += 3) {
        const float* n = &mesh.normals[i];
        const float* p = &mesh.positions[i];
        EXPECT_NEAR(std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1.0f, 1e-5f);
        const float outward = n[0] * (p[0] - centre[0]) + n[1] * (p[1] - centre[1]) +
                              n[2] * (p[2] - centre[2]);
        EXPECT_GT(outward, 0.0f);
    }
}

TEST(Tessellate, TrianglesWindCounterClockwiseSeenFromOutside) {
    const auto mesh = Shape::makeBox(10.0, 20.0, 30.0)->tessellate(0.1, 0.35);
    for (size_t t = 0; t < mesh.indices.size(); t += 3) {
        const float* a = &mesh.positions[mesh.indices[t] * 3];
        const float* b = &mesh.positions[mesh.indices[t + 1] * 3];
        const float* c = &mesh.positions[mesh.indices[t + 2] * 3];
        const float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        const float v[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        const float geometric[3] = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2],
                                    u[0] * v[1] - u[1] * v[0]};
        const float* n = &mesh.normals[mesh.indices[t] * 3];
        EXPECT_GT(geometric[0] * n[0] + geometric[1] * n[1] + geometric[2] * n[2], 0.0f);
    }
}

TEST(Tessellate, RejectsInvalidDeflection) {
    const auto box = Shape::makeBox(1.0, 1.0, 1.0);
    expectError(ErrorCode::InvalidArgument, [&] { box->tessellate(0.0, 0.35); });
    expectError(ErrorCode::InvalidArgument, [&] { box->tessellate(0.1, -1.0); });
    expectError(ErrorCode::InvalidArgument, [&] { box->tessellate(std::nan(""), 0.35); });
}

TEST(Step, RoundTripPreservesBounds) {
    const std::string path = tempPath("sharpcad_roundtrip.step");
    Shape::makeBox(10.0, 20.0, 30.0)->exportStep(path);
    const auto b = Shape::importStep(path)->boundingBox();
    EXPECT_NEAR(b.min[0], 0.0, 1e-6);
    EXPECT_NEAR(b.max[0], 10.0, 1e-6);
    EXPECT_NEAR(b.max[1], 20.0, 1e-6);
    EXPECT_NEAR(b.max[2], 30.0, 1e-6);
    std::remove(path.c_str());
}

TEST(Step, MissingFileIsIoError) {
    expectError(ErrorCode::IoError, [] { Shape::importStep("/nonexistent/none.step"); });
}

TEST(Step, GarbageFileIsIoError) {
    const std::string path = tempPath("sharpcad_garbage.step");
    std::ofstream(path) << "this is not a STEP file\n";
    expectError(ErrorCode::IoError, [&] { Shape::importStep(path); });
    std::remove(path.c_str());
}

TEST(Step, ExportToUnwritablePathIsIoError) {
    const auto box = Shape::makeBox(1.0, 1.0, 1.0);
    expectError(ErrorCode::IoError, [&] { box->exportStep("/nonexistent/dir/out.step"); });
}
