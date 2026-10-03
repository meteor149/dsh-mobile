plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
    implementation(project(":dsh-runtime"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
    implementation(project(":dsh-runtime"))
            implementation("io.github.meteor149:ubuntu-runtime:${providers.gradleProperty("UBUNTU_RUNTIME_VERSION").get()}")
        }
        commonTest.dependencies {
    implementation(project(":dsh-runtime"))
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "ai.meteor.dshmobile.resources"
}

android {
    namespace = "ai.meteor.dshmobile.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
