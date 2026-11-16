cmake_minimum_required(VERSION 3.22)
project(cadcore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(CADCORE_BUILD_TESTS "Build host-side C++ tests" OFF)
set(OCCT_ROOT "" CACHE PATH "Prebuilt OCCT (see tools/build-occt.sh)")

if(ANDROID)
    set(OCCT_LIB_DIR "${OCCT_ROOT}/jniLibs/${ANDROID_ABI}")
else()
    set(OCCT_LIB_DIR "${OCCT_ROOT}/lib")
endif()
if(NOT EXISTS "${OCCT_ROOT}/include/Standard_Version.hxx")
    message(FATAL_ERROR
        "OCCT not found at '${OCCT_ROOT}'. Run tools/build-occt.sh android|host first.")
endif()

# OCCT toolkits cadcore links against directly. Always dynamic (LGPL).
set(OCCT_TOOLKITS
    TKernel TKMath TKG2d TKG3d TKGeomBase TKBRep TKGeomAlgo TKTopAlgo TKPrim
    TKMesh TKShHealing TKDE TKXSBase TKDESTEP)
foreach(tk IN LISTS OCCT_TOOLKITS)
    add_library(occt::${tk} SHARED IMPORTED)
    set_target_properties(occt::${tk} PROPERTIES
        IMPORTED_LOCATION "${OCCT_LIB_DIR}/lib${tk}.so"
        IMPORTED_NO_SONAME ON
        INTERFACE_INCLUDE_DIRECTORIES "${OCCT_ROOT}/include")
    list(APPEND OCCT_TARGETS occt::${tk})
endforeach()

# Kernel logic, free of JNI so it can be tested directly.
add_library(cadkernel STATIC
    src/shape.cpp
    src/error.cpp)
target_include_directories(cadkernel PUBLIC include)
target_link_libraries(cadkernel PRIVATE ${OCCT_TARGETS})
target_compile_options(cadkernel PRIVATE -Wall -Wextra)

add_library(cadcore SHARED jni/jni_kernel.cpp)
target_link_libraries(cadcore PRIVATE cadkernel ${OCCT_TARGETS})
target_compile_options(cadcore PRIVATE -Wall -Wextra)

if(ANDROID)
    target_link_options(cadcore PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(cadcore PRIVATE ${JNI_INCLUDE_DIRS})
    # Host only: classic RPATH (not RUNPATH) so OCCT's own inter-toolkit dependencies
    # resolve from the same directory without LD_LIBRARY_PATH.
    add_link_options("-Wl,--disable-new-dtags")
    target_link_options(cadcore PRIVATE "-Wl,--disable-new-dtags")
    set_target_properties(cadcore PROPERTIES BUILD_RPATH "${OCCT_LIB_DIR}")
endif()

if(CADCORE_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    include(FetchContent)
    FetchContent_Declare(googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.17.0
        GIT_SHALLOW ON)
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    add_executable(cadkernel_tests tests/shape_test.cpp)
    target_link_libraries(cadkernel_tests PRIVATE cadkernel ${OCCT_TARGETS} GTest::gtest_main)
    set_target_properties(cadkernel_tests PROPERTIES BUILD_RPATH "${OCCT_LIB_DIR}")
    include(GoogleTest)
    gtest_discover_tests(cadkernel_tests)

    # Generates the sample part bundled in the app's assets (run manually).
    add_executable(make_sample tools/make_sample.cpp)
    target_include_directories(make_sample PRIVATE "${OCCT_ROOT}/include")
    target_link_libraries(make_sample PRIVATE ${OCCT_TARGETS}
        "${OCCT_LIB_DIR}/libTKBO.so" "${OCCT_LIB_DIR}/libTKFillet.so")
    set_target_properties(make_sample PROPERTIES BUILD_RPATH "${OCCT_LIB_DIR}")
endif()
