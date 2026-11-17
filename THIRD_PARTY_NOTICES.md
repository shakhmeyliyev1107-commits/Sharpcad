# Third-party notices

| Component | Version | Licence | How it is used |
|---|---|---|---|
| OpenCASCADE Technology (OCCT) | 7.9.3 | LGPL-2.1 with Open CASCADE exception | Unmodified, dynamically linked `libTK*.so` |
| PlaneGCS (from FreeCAD) | 1.0.2 | LGPL-2.0-or-later | Unmodified sources in `third_party/planegcs`, built as separate `libplanegcs.so` |
| Eigen | 3.4.0 | MPL-2.0 (`EIGEN_MPL2_ONLY`) | Headers compiled into `libplanegcs.so` |
| Boost (headers) | 1.86.0 | BSL-1.0 | Headers compiled into `libplanegcs.so` |
| Filament | 1.80.1 | Apache-2.0 | Maven AAR `filament-android` |
| AndroidX / Jetpack Compose | see `gradle/libs.versions.toml` | Apache-2.0 | Maven |
| Kotlin, kotlinx.coroutines | see `gradle/libs.versions.toml` | Apache-2.0 | Maven |
| LLVM libc++ (`libc++_shared.so`) | NDK r27d | Apache-2.0 with LLVM exception | Shipped from the NDK |
| GoogleTest | 1.17.0 | BSD-3-Clause | Host tests only, not shipped |

## LGPL obligations (OCCT and PlaneGCS)

Before any public release the app must:

1. Ship the OCCT libraries and `libplanegcs.so` as separate, unmodified shared objects
   (done: never link statically).
2. Include the LGPL-2.1 text and the Open CASCADE exception in the app's licence screen.
   Both files are copied to `native/third_party/occt/<target>/` by `tools/build-occt.sh`.
3. State which OCCT version is used and where its source can be obtained
   (https://github.com/Open-Cascade-SAS/OCCT, tag `V7_9_3`), and keep `tools/build-occt.sh`
   so a user can rebuild and replace the libraries. For PlaneGCS the source and its build
   file are in this repository under `third_party/planegcs`.
4. Not prohibit reverse engineering for the purpose of debugging such replacements.

This is an engineering summary, not legal advice; have counsel confirm before store release.
