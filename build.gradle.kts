plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    id("com.android.application") version "8.10.0" apply false
    id("com.android.library") version "8.10.0" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}

tasks.register<Exec>("buildRuntime") {
    group = "runtime"
    description = "Builds the ARM64 PRoot and Ubuntu/DSH runtime artifacts with Docker."
    workingDir(rootDir)
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        commandLine("pwsh", "-NoProfile", "-File", "runtime/build-runtime.ps1")
    } else {
        commandLine("bash", "runtime/build-runtime.sh")
    }
}
