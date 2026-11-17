plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val hostBuildDir = layout.buildDirectory.dir("host-native")

android {
    namespace = "com.sharpcad.sketch"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSHARPCAD_ROOT=${rootProject.projectDir.absolutePath}",
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // JNI boundary tests run on the host JVM against a host build of libcadsketch.
            val dir = hostBuildDir.get().asFile
            it.systemProperty(
                "java.library.path",
                listOf(dir.absolutePath, File(dir, "planegcs").absolutePath).joinToString(File.pathSeparator),
            )
            it.environment("LD_LIBRARY_PATH", File(dir, "planegcs").absolutePath)
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
    description = "Configures libcadsketch and PlaneGCS for the host."
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(hostBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake", "-S", file("src/main/cpp").absolutePath, "-B", hostBuildDir.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
        "-DSHARPCAD_ROOT=${rootProject.projectDir.absolutePath}", "-DCADSKETCH_BUILD_TESTS=ON",
    )
}

val buildHostNative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds libcadsketch and its C++ tests for the host."
    dependsOn(configureHostNative)
    inputs.dir("src/main/cpp")
    inputs.dir(rootProject.file("third_party/planegcs"))
    outputs.dir(hostBuildDir)
    commandLine("cmake", "--build", hostBuildDir.get().asFile.absolutePath)
}

val hostNativeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the C++ sketch tests on the host."
    dependsOn(buildHostNative)
    commandLine("ctest", "--test-dir", hostBuildDir.get().asFile.absolutePath, "--output-on-failure")
}

tasks.withType<Test>().configureEach { dependsOn(buildHostNative) }

tasks.register("hostTest") {
    group = "verification"
    description = "C++ sketch tests plus JNI boundary and Kotlin tests, all on the host."
    dependsOn(hostNativeTest, "testDebugUnitTest")
}
