# SharpCAD

Touch-first parametric 3D CAD modeler for Android tablets.
Target: arm64-v8a only, minSdk 29, 8–13 inch tablets. Phone layout out of scope but not blocked.

## Stack
- UI: Kotlin 2.4 (Filament's AAR needs stdlib 2.4), Jetpack Compose (Material 3 as a base, own design system on top).
- Geometry kernel: OpenCASCADE (OCCT) 7.9.x, C++17, built as SHARED libraries (LGPL — see Licensing).
- Rendering: Google Filament (Apache-2.0), Kotlin API from Maven, on a dedicated SurfaceView.
  No SceneView wrapper. Materials are compiled with `matc` of the SAME Filament version.
- Sketch solver: PlaneGCS (LGPL-2.0+, extracted from FreeCAD) behind our own C++ interface.
- Persistence: Room (project metadata), OCCT BinXCAF/BRep binary (bodies), JSON (feature parameters).
- AR: ARCore + Filament (Phase 5).
- Build: Gradle 8.14 wrapper (Kotlin DSL, version catalog) + CMake 3.22 + NDK r27d. JDK 21, bytecode 17.

## Modules (dependencies point downward only)
    :app                 activity, DI wiring, navigation
    :ui:workspace        top bar, rails, panels          (Phase 1+)
    :ui:designsystem     theme, tokens, icons            (Phase 1+)
    :viewport            SurfaceView host, Filament scene, camera, gestures
    :document            feature tree, undo/redo, autosave (Phase 3)
    :sketch              sketch entities + solver bindings (Phase 2)
    :kernel              Kotlin API + JNI + C++ `cadcore` wrapping OCCT
    native/third_party   prebuilt OCCT .so + headers (not in git; built by tools/)

## Architecture rules
1. `:kernel` and `:sketch` never import Android UI, Compose or Filament types.
2. `:viewport` consumes meshes (direct ByteBuffers) and ids; it never calls OCCT.
3. UI modules never call JNI directly; they go through `:document` (Phase 3+) or `:kernel` Kotlin API.
4. No OCCT type crosses JNI. Only primitives, direct ByteBuffers, and opaque `Long` handles.
5. Every native handle is owned by exactly one Kotlin `AutoCloseable`; no finalizers for correctness.
6. Kernel calls are blocking and run on a worker dispatcher (a bounded `KernelDispatcher`
   from Phase 3). Never on Main.
7. Tessellation always runs on workers and returns LOD meshes. Never on the UI or render thread.
8. The render loop is driven by Choreographer and Filament's own thread, never by recomposition.
   Compose state may only be read into the renderer through an explicit snapshot hand-off.
9. C++ exceptions (including `Standard_Failure`) are caught at the JNI boundary and rethrown
   as `KernelException` with a stable error code. Nothing may unwind into the JVM.
10. Feature tree is the source of truth; B-rep bodies are a cache that can be rebuilt from it.
11. Units: kernel works in millimetres and radians. Conversion happens only in UI formatting.

## Naming
- Kotlin packages: `com.sharpcad.<module>.<area>`; application id `com.sharpcad.app`.
- Kotlin: `PascalCase` types, `camelCase` members, Composables are `PascalCase` nouns.
- JNI entry class per module: `KernelNative`, `SketchNative` (internal `external fun`s only).
- C++: namespace `cad::<module>`, files `snake_case.{h,cpp}`, types `PascalCase`, functions `camelCase`.
- JNI C++ files: `jni_<area>.cpp`; no logic in them beyond marshalling and exception translation.
- Native libs: `libcadcore.so` (ours), `libTK*.so` (OCCT, unmodified names).
- Tests: `<Subject>Test.kt`, `<subject>_test.cpp`.

## Testing
- C++ kernel wrappers and the sketch solver: GoogleTest, run on host (Linux x86_64 OCCT build).
- JNI boundary: JUnit tests on the host JVM against a host build of `libcadcore.so`,
  plus instrumented tests on device for arm64-specific behaviour.
- UI tests are secondary until Phase 3.
- Never claim a performance number that was not measured; record device, build type and model.

## Commands
    tools/build-occt.sh android   # cross-compile OCCT for arm64-v8a into native/third_party
    tools/build-occt.sh host      # host build used by tests
    tools/build-materials.sh      # recompile *.mat after editing or bumping Filament
    ./gradlew :app:assembleDebug
    tools/fetch-sketch-deps.sh    # Eigen + Boost headers for PlaneGCS
    ./gradlew :kernel:hostTest :sketch:hostTest   # C++ + JNI tests on host

## Licensing (every new native dependency must be listed here before it is added)
- OCCT: LGPL-2.1 with the Open CASCADE exception. Link dynamically, ship unmodified `.so` files,
  include the licence text and a source offer. Do not link statically.
- PlaneGCS: LGPL-2.0+ — same handling; unmodified sources vendored in `third_party/planegcs`,
  built as `libplanegcs.so`. Depends on Eigen (MPL-2.0 only) and Boost headers (BSL-1.0).
- Filament, ARCore SDK, AndroidX: Apache-2.0.
- Forbidden: GPL code (SolveSpace solver, LibreDWG, CGAL GPL parts) and any proprietary SDK
  without a signed licence (Parasolid, D-Cubed, ODA, SolidWorks readers).

## Guardrails
- No Shapr3D assets, names or trademarks anywhere in code, resources or store listing.
- Plan first for any change touching more than three files or adding a dependency.
- Phases are gated: do not start the next phase without explicit approval.

## Docs
`docs/` is a wiki maintained by an LLM. Page format, which page to update, and the
`/docs-sync` workflow live in `docs/CLAUDE.md`. Start at `docs/index.md`.
