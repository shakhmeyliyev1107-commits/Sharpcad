#!/usr/bin/env bash
# Compiles Filament materials (*.mat) into viewport assets (*.filamat).
# The compiled files are committed, so a normal Gradle build does not need matc.
#
# matc MUST come from the same Filament release as the `filament` version in
# gradle/libs.versions.toml, otherwise materials fail to load at runtime.
#   MATC=/path/to/matc tools/build-materials.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
matc="${MATC:-$root/native/third_party/filament/bin/matc}"
[ -x "$matc" ] || { echo "matc not found at $matc (set MATC)" >&2; exit 1; }

src="$root/viewport/src/main/materials"
dst="$root/viewport/src/main/assets/materials"
mkdir -p "$dst"
for mat in "$src"/*.mat; do
    name="$(basename "$mat" .mat)"
    "$matc" --platform mobile --api opengl --api vulkan --optimize-size \
        --output "$dst/$name.filamat" "$mat"
    echo "compiled $name.filamat"
done
