#!/usr/bin/env bash
# Downloads the header-only libraries PlaneGCS needs into native/third_party:
#   eigen/  Eigen 3.4.0  (MPL-2.0; the build defines EIGEN_MPL2_ONLY)
#   boost/  Boost 1.86.0 (BSL-1.0; only headers are used)
# Nothing here is shipped as a binary of its own; both are compiled into libplanegcs.so.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/native/third_party"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ ! -f "$out/eigen/Eigen/Core" ]; then
    curl -fsSL https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz | tar xz -C "$tmp"
    rm -rf "$out/eigen"
    mv "$tmp/eigen-3.4.0" "$out/eigen"
    echo "eigen 3.4.0 -> $out/eigen"
fi

if [ ! -f "$out/boost/boost/graph/adjacency_list.hpp" ]; then
    curl -fsSL -o "$tmp/boost.tar.xz" \
        https://github.com/boostorg/boost/releases/download/boost-1.86.0/boost-1.86.0-b2-nodocs.tar.xz
    tar xJf "$tmp/boost.tar.xz" -C "$tmp" boost-1.86.0/boost
    rm -rf "$out/boost"
    mkdir -p "$out/boost"
    mv "$tmp/boost-1.86.0/boost" "$out/boost/boost"
    echo "boost 1.86.0 headers -> $out/boost"
fi
