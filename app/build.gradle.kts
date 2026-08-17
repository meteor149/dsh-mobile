import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime/assets")
val generatedRuntimeJni = layout.buildDirectory.dir("generated/runtime/jniLibs")
val runtimeDist = rootProject.layout.projectDirectory.dir("runtime/dist")
val fallbackManifest = rootProject.layout.projectDirectory.file("runtime/manifest/unavailable.json")
val releaseStoreFile = System.getenv("ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
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

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val prepareRuntimeAssets by tasks.registering {
    group = "runtime"
    description = "Validates and stages the versioned Android runtime artifacts."
    inputs.dir(runtimeDist)
    inputs.file(fallbackManifest)
    outputs.dir(generatedRuntimeAssets)
    outputs.dir(generatedRuntimeJni)

    doLast {
        val assetsDirectory = generatedRuntimeAssets.get().asFile
        val jniDirectory = generatedRuntimeJni.get().asFile
        delete(assetsDirectory, jniDirectory)
        val runtimeAssetDirectory = assetsDirectory.resolve("runtime").apply { mkdirs() }
        jniDirectory.mkdirs()

        val releaseManifest = runtimeDist.file("runtime-manifest.json").asFile
        val selectedManifest = releaseManifest.takeIf(File::isFile) ?: fallbackManifest.asFile
        val document = JsonSlurper().parse(selectedManifest) as Map<*, *>
        copy {
            from(selectedManifest)
            into(runtimeAssetDirectory)
            rename { "runtime-manifest.json" }
        }

        if (document["available"] != true) return@doLast

        fun stageArtifact(fileName: String, expectedSha256: String, destination: File) {
            val source = runtimeDist.file(fileName).asFile
            check(source.isFile) { "Runtime artifact is missing: ${source.absolutePath}" }
            val actualSha256 = sha256(source)
            check(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "Runtime artifact checksum mismatch for $fileName: expected=$expectedSha256 actual=$actualSha256"
            }
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }

        val rootfs = document["rootfs"] as Map<*, *>
        val rootfsFile = rootfs["file"] as String
        stageArtifact(
            fileName = rootfsFile,
            expectedSha256 = rootfs["sha256"] as String,
            destination = runtimeAssetDirectory.resolve(rootfsFile),
        )

        @Suppress("UNCHECKED_CAST")
        val nativeLibraries = document["nativeLibraries"] as List<Map<String, String>>
        val abi = document["abi"] as String
        nativeLibraries.forEach { library ->
            stageArtifact(
                fileName = library.getValue("file"),
                expectedSha256 = library.getValue("sha256"),
                destination = jniDirectory.resolve("$abi/${library.getValue("packagedName")}"),
            )
        }
    }
}

android {
    namespace = "ai.meteor.dshmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.meteor.dshmobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-BETA"
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
        getByName("release") {
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].assets.srcDir(generatedRuntimeAssets)
    sourceSets["main"].jniLibs.srcDir(generatedRuntimeJni)

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
                "**/libdsh_proot.so",
                "**/libdsh_proot_loader.so",
                "**/libandroid-shmem.so",
                "**/libdsh_talloc.so",
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
    dependsOn(prepareRuntimeAssets)
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.compose.foundation:foundation:1.8.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.github.luben:zstd-jni:1.5.7-6")
}
