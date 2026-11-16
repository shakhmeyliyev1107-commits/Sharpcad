#!/usr/bin/env bash
# Builds OpenCASCADE as shared libraries into native/third_party/occt/<target>.
#
#   tools/build-occt.sh android   # arm64-v8a, for the APK
#   tools/build-occt.sh host      # this machine, for host-side tests
#
# Environment:
#   OCCT_SRC      OCCT source tree (default: cloned into native/third_party/occt/src)
#   OCCT_TAG      git tag to clone when OCCT_SRC is absent (default: V7_9_3)
#   ANDROID_NDK   NDK root (default: $ANDROID_HOME/ndk/<version from libs.versions.toml>)
#   JOBS          parallel jobs (default: nproc)
#
# OCCT is LGPL-2.1 with exception: it is always built SHARED and never patched.
set -euo pipefail

target="${1:-}"
case "$target" in android|host) ;; *) echo "usage: $0 android|host" >&2; exit 2 ;; esac

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/native/third_party/occt"
tag="${OCCT_TAG:-V7_9_3}"
src="${OCCT_SRC:-$out/src}"
jobs="${JOBS:-$(nproc)}"

if [ ! -f "$src/CMakeLists.txt" ]; then
    git clone --depth 1 --branch "$tag" https://github.com/Open-Cascade-SAS/OCCT.git "$src"
fi

# Modeling plus DataExchange (STEP/IGES/STL/OBJ). DataExchange needs XCAF, which pulls in
# the windowing-free parts of Visualization (TKService, TKV3d); no OpenGL toolkit is built.
common=(
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_LIBRARY_TYPE=Shared
    -DBUILD_MODULE_FoundationClasses=ON
    -DBUILD_MODULE_ModelingData=ON
    -DBUILD_MODULE_ModelingAlgorithms=ON
    -DBUILD_MODULE_Visualization=OFF
    -DBUILD_MODULE_ApplicationFramework=ON
    -DBUILD_MODULE_DataExchange=ON
    -DBUILD_MODULE_DETools=OFF
    -DBUILD_MODULE_Draw=OFF
    -DBUILD_DOC_Overview=OFF
    -DBUILD_SAMPLES_QT=OFF
    -DUSE_FREETYPE=OFF
    -DUSE_TK=OFF
    -DUSE_RAPIDJSON=OFF
    -DUSE_XLIB=OFF
    -DUSE_OPENGL=OFF
    -DUSE_GLES2=OFF
    -DINSTALL_DIR_INCLUDE=include
)

build="$out/build-$target"
prefix="$out/$target"

if [ "$target" = android ]; then
    ndk_version="$(sed -n 's/^ndk = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")"
    ndk="${ANDROID_NDK:-${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK}/ndk/$ndk_version}"
    cmake -S "$src" -B "$build" "${common[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-29 \
        -DANDROID_STL=c++_shared \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DINSTALL_DIR_LIB=jniLibs/arm64-v8a
else
    cmake -S "$src" -B "$build" "${common[@]}" \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DINSTALL_DIR_LIB=lib
fi

cmake --build "$build" -j "$jobs"
cmake --install "$build" >/dev/null
cp "$src/LICENSE_LGPL_21.txt" "$src/OCCT_LGPL_EXCEPTION.txt" "$prefix/" 2>/dev/null || true
echo "OCCT $tag ($target) installed in $prefix"
