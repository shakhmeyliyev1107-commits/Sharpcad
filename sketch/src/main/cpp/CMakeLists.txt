cmake_minimum_required(VERSION 3.22)
project(cadsketch LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(CADSKETCH_BUILD_TESTS "Build host-side C++ tests" OFF)
set(SHARPCAD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." CACHE PATH "Repository root")

if(NOT ANDROID)
    # Host only: classic RPATH so libplanegcs.so is found next to the test binaries.
    add_link_options("-Wl,--disable-new-dtags")
endif()

# PlaneGCS (LGPL): its own shared library, built from unmodified vendored sources.
set(PLANEGCS_DEPS_ROOT "${SHARPCAD_ROOT}/native/third_party" CACHE PATH "eigen/ and boost/")
add_subdirectory("${SHARPCAD_ROOT}/third_party/planegcs" planegcs)

# Sketch logic, free of JNI so it can be tested directly.
add_library(cadsketchcore STATIC src/sketch.cpp)
target_include_directories(cadsketchcore PUBLIC include)
target_link_libraries(cadsketchcore PRIVATE planegcs)
target_compile_options(cadsketchcore PRIVATE -Wall -Wextra)

add_library(cadsketch SHARED jni/jni_sketch.cpp)
target_link_libraries(cadsketch PRIVATE cadsketchcore planegcs)
target_compile_options(cadsketch PRIVATE -Wall -Wextra)

if(ANDROID)
    target_link_options(cadsketch PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(cadsketch PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(CADSKETCH_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    include(FetchContent)
    FetchContent_Declare(googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.17.0
        GIT_SHALLOW ON)
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    add_executable(cadsketch_tests tests/sketch_test.cpp)
    target_link_libraries(cadsketch_tests PRIVATE cadsketchcore planegcs GTest::gtest_main)
    include(GoogleTest)
    gtest_discover_tests(cadsketch_tests)
endif()
