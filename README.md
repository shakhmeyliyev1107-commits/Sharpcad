# SharpCAD

Touch-first parametric 3D CAD modeler for Android tablets (arm64-v8a, Android 10+).
Status: **Phase 1** — viewport navigation, grid, STEP import with shaded display, static tool rails.

## Modules

```
                 :app
                   │
          ┌────────┴────────┐
      :viewport          :kernel
   (Filament, Kotlin)   (Kotlin API → JNI → libcadcore.so)
                             │
                      libTK*.so (OpenCASCADE, prebuilt, LGPL)
```

`:viewport` never calls OCCT; `:kernel` knows nothing about Android UI or Filament.
Rules and naming conventions are in [CLAUDE.md](CLAUDE.md).

## Building

Requirements: JDK 21, Android SDK (platform 37, build-tools 36, CMake 3.22.1),
NDK 27.3.13750724, Ninja, and on Linux a host C++17 compiler for the tests.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

```bash
tools/build-occt.sh android
```

```bash
./gradlew :app:assembleDebug
```

The OCCT step takes roughly 20 minutes on 4 cores and is needed once per OCCT version.
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests (no device needed)

```bash
tools/build-occt.sh host
```

```bash
./gradlew :kernel:hostTest
```

This runs the C++ kernel tests (GoogleTest) and the JNI boundary tests (JUnit on the
host JVM against a host build of `libcadcore.so`).

## Licences

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
