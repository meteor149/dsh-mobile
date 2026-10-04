
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
val appVersionName = providers.gradleProperty("APP_VERSION_NAME").get()
val appVersionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)

check(releaseSigningValues.all { it == null } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Release signing requires ANDROID_RELEASE_STORE_FILE, ANDROID_RELEASE_STORE_PASSWORD, " +
        "ANDROID_RELEASE_KEY_ALIAS, and ANDROID_RELEASE_KEY_PASSWORD."
}

// proroot is distributed only in the complete app, as required by its license.
extra["runtimeArtifactKind"] = "proroot"
apply(from = rootProject.file("gradle/runtime-artifacts.gradle.kts"))

android {
    namespace = "ai.meteor.dshmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.meteor.dshmobile"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Device smoke tests use a separate app identity to preserve the user's installation.
            applicationIdSuffix = providers.gradleProperty("SMOKE_TEST_APPLICATION_ID_SUFFIX").orNull
        }
        getByName("release") {
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtime/assets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/runtime/jniLibs"))

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // PRoot and its loader must be extracted onto Android's executable native-library filesystem.
            useLegacyPackaging = true
            // The runtime manifest hashes the exact Termux build outputs. Keep AGP from
            // rewriting those files so packaged bytes remain independently verifiable.
            keepDebugSymbols += setOf(
                "**/libubuntu_proot.so",
                "**/libubuntu_proot_loader.so",
                "**/libandroid-shmem.so",
                "**/libubuntu_talloc.so",
                "**/libproroot.so",
                "**/libproroot-runtime.so",
                "**/libproroot-bridge.so",
                "**/libproroot-linker.so",
                "**/libproroot-stub-loader.so",
            )
        }
    }

    androidResources {
        noCompress += "zst"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("prepareRuntimeAssets")
}

dependencies {
    implementation(project(":dsh-runtime"))
    implementation(project(":shared"))
    implementation("io.github.meteor149:ubuntu-runtime:${providers.gradleProperty("UBUNTU_RUNTIME_VERSION").get()}")
    implementation("io.github.meteor149:ubuntu-image:${providers.gradleProperty("UBUNTU_IMAGE_VERSION").get()}")
    implementation("org.jetbrains.compose.foundation:foundation:1.8.2")
    implementation("org.jetbrains.compose.material3:material3:1.8.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
