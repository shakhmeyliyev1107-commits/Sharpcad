plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val occtHost = rootProject.layout.projectDirectory.dir("native/third_party/occt/host")

android {
    namespace = "com.sharpcad.document"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // Rebuild tests run the real kernel on the host JVM.
            val kernelHost = project(":kernel").layout.buildDirectory.dir("host-native").get().asFile
            it.systemProperty(
                "java.library.path",
                listOf(kernelHost.absolutePath, occtHost.dir("lib").asFile.absolutePath).joinToString(File.pathSeparator),
            )
            it.environment("LD_LIBRARY_PATH", occtHost.dir("lib").asFile.absolutePath)
            it.environment("LC_ALL", "C.UTF-8")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":kernel"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { dependsOn(":kernel:buildHostNative") }
