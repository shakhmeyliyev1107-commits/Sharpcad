# Builds PlaneGCS (FreeCAD's 2D geometric constraint solver) as a standalone shared
# library. LGPL: always SHARED, sources under src/Mod/Sketcher/App/planegcs unmodified.
cmake_minimum_required(VERSION 3.22)
project(planegcs LANGUAGES CXX)

set(PLANEGCS_DEPS_ROOT "" CACHE PATH "Directory holding eigen/ and boost/ (tools/fetch-sketch-deps.sh)")
foreach(header "eigen/Eigen/Core" "boost/boost/graph/adjacency_list.hpp")
    if(NOT EXISTS "${PLANEGCS_DEPS_ROOT}/${header}")
        message(FATAL_ERROR
            "${header} not found under '${PLANEGCS_DEPS_ROOT}'. Run tools/fetch-sketch-deps.sh first.")
    endif()
endforeach()

set(PLANEGCS_SRC "${CMAKE_CURRENT_SOURCE_DIR}/src/Mod/Sketcher/App/planegcs")
add_library(planegcs SHARED
    "${PLANEGCS_SRC}/Constraints.cpp"
    "${PLANEGCS_SRC}/GCS.cpp"
    "${PLANEGCS_SRC}/Geo.cpp"
    "${PLANEGCS_SRC}/SubSystem.cpp"
    "${PLANEGCS_SRC}/qp_eq.cpp")
set_target_properties(planegcs PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_VISIBILITY_PRESET hidden
    POSITION_INDEPENDENT_CODE ON)
target_include_directories(planegcs
    PUBLIC "${PLANEGCS_SRC}"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/shim")
# SYSTEM: third-party headers, keep their warnings out of our build.
target_include_directories(planegcs SYSTEM PUBLIC
    "${PLANEGCS_DEPS_ROOT}/eigen"
    "${PLANEGCS_DEPS_ROOT}/boost")
# Refuse to compile any Eigen code that is not under MPL-2.0.
target_compile_definitions(planegcs PUBLIC EIGEN_MPL2_ONLY)
target_compile_options(planegcs PRIVATE -w)
if(ANDROID)
    target_link_options(planegcs PRIVATE "-Wl,-z,max-page-size=16384")
endif()
