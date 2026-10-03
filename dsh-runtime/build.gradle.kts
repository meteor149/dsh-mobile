plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}
apply(from = file("gradle/dsh-artifacts.gradle.kts"))
android {
    namespace = "ai.meteor.dsh.runtime"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtime/assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn("prepareRuntimeAssets") }

dependencies {
    api("io.github.meteor149:ubuntu-runtime:${providers.gradleProperty("UBUNTU_RUNTIME_VERSION").get()}")
    implementation("androidx.core:core-ktx:1.16.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test-junit"))
}

