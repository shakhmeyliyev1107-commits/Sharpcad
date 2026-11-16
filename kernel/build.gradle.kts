plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val occtAndroid = rootProject.layout.projectDirectory.dir("native/third_party/occt/android")
val occtHost = rootProject.layout.projectDirectory.dir("native/third_party/occt/host")
val hostBuildDir = layout.buildDirectory.dir("host-native")

android {
    namespace = "com.sharpcad.kernel"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DOCCT_ROOT=${occtAndroid.asFile.absolutePath}",
                )
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    // OCCT shared libraries (LGPL) are packaged unmodified next to libcadcore.so.
    sourceSets["main"].jniLibs.srcDir(occtAndroid.dir("jniLibs"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // JNI boundary tests run on the host JVM against a host build of libcadcore.
            it.systemProperty(
                "java.library.path",
                listOf(hostBuildDir.get().asFile.absolutePath, occtHost.dir("lib").asFile.absolutePath)
                    .joinToString(File.pathSeparator),
            )
            it.environment("LD_LIBRARY_PATH", occtHost.dir("lib").asFile.absolutePath)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

// ---- Host-side native build and tests (no device needed) -------------------

val configureHostNative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Configures libcadcore for the host using the host OCCT build."
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(hostBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake", "-S", file("src/main/cpp").absolutePath, "-B", hostBuildDir.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
        "-DOCCT_ROOT=${occtHost.asFile.absolutePath}", "-DCADCORE_BUILD_TESTS=ON",
    )
}

val buildHostNative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds libcadcore and its C++ tests for the host."
    dependsOn(configureHostNative)
    inputs.dir("src/main/cpp")
    outputs.dir(hostBuildDir)
    commandLine("cmake", "--build", hostBuildDir.get().asFile.absolutePath)
}

val hostNativeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the C++ kernel tests on the host."
    dependsOn(buildHostNative)
    commandLine("ctest", "--test-dir", hostBuildDir.get().asFile.absolutePath, "--output-on-failure")
}

tasks.withType<Test>().configureEach { dependsOn(buildHostNative) }

tasks.register("hostTest") {
    group = "verification"
    description = "C++ kernel tests plus JNI boundary tests, all on the host."
    dependsOn(hostNativeTest, "testDebugUnitTest")
}
