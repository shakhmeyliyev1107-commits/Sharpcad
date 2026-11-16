#include <cmath>
#include <limits>

#include <gtest/gtest.h>

#include "cad/kernel/error.h"
#include "cad/kernel/solid.h"

using cad::kernel::ErrorCode;
using cad::kernel::KernelError;
using cad::kernel::Solid;

TEST(OcctVersion, IsNotEmpty) { EXPECT_FALSE(cad::kernel::occtVersion().empty()); }

TEST(SolidBox, BoundingBoxMatchesDimensions) {
    const auto box = Solid::makeBox(10.0, 20.0, 30.0);
    const auto b = box->boundingBox();
    EXPECT_NEAR(b.min[0], 0.0, 1e-9);
    EXPECT_NEAR(b.min[1], 0.0, 1e-9);
    EXPECT_NEAR(b.min[2], 0.0, 1e-9);
    EXPECT_NEAR(b.max[0], 10.0, 1e-9);
    EXPECT_NEAR(b.max[1], 20.0, 1e-9);
    EXPECT_NEAR(b.max[2], 30.0, 1e-9);
}

TEST(SolidBox, RejectsInvalidDimensions) {
    const double bad[] = {0.0, -5.0, 1e-9, std::nan(""),
                          std::numeric_limits<double>::infinity()};
    for (double v : bad) {
        for (int axis = 0; axis < 3; ++axis) {
            double d[3] = {1.0, 1.0, 1.0};
            d[axis] = v;
            try {
                Solid::makeBox(d[0], d[1], d[2]);
                ADD_FAILURE() << "expected KernelError for " << v << " on axis " << axis;
            } catch (const KernelError& e) {
                EXPECT_EQ(e.code(), ErrorCode::InvalidArgument);
            }
        }
    }
}

TEST(SolidBox, HandlesExtremeAspectRatio) {
    const auto b = Solid::makeBox(1e-3, 1e4, 1.0)->boundingBox();
    EXPECT_NEAR(b.max[0], 1e-3, 1e-9);
    EXPECT_NEAR(b.max[1], 1e4, 1e-6);
}
