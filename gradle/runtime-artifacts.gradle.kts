import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

val kind = extra["runtimeArtifactKind"] as String
val dist = providers.gradleProperty("UBUNTU_${kind.uppercase()}_DIST")
    .map { rootProject.file(it) }.getOrElse(rootProject.file("runtime/dist"))
val fallback = rootProject.file("runtime/manifest/unavailable.json")
val assetsOutput = layout.buildDirectory.dir("generated/runtime/assets")
val jniOutput = layout.buildDirectory.dir("generated/runtime/jniLibs")
val manifestName = when (kind) {
    "image" -> "ubuntu-image-manifest.json"
    "engine" -> "ubuntu-engine-manifest.json"
    else -> "ubuntu-proroot-manifest.json"
}
val prootNames = setOf("libdsh_proot.so", "libdsh_proot_loader.so", "libandroid-shmem.so", "libdsh_talloc.so")
val prorootNames = setOf("libproroot.so", "libproroot-runtime.so", "libproroot-bridge.so", "libproroot-linker.so", "libproroot-stub-loader.so")

tasks.register("prepareRuntimeAssets") {
    group = "runtime"
    description = "Validates and stages only the $kind artifacts."
    inputs.dir(dist)
    inputs.file(fallback)
    inputs.property("kind", kind)
    outputs.dir(assetsOutput)
    outputs.dir(jniOutput)
    doLast {
        val assets = assetsOutput.get().asFile
        val jni = jniOutput.get().asFile
        delete(assets, jni)
        val runtimeAssets = assets.resolve("runtime").apply { mkdirs() }
        jni.mkdirs()
        val sourceManifest = dist.resolve("runtime-manifest.json").takeIf { it.isFile } ?: fallback
        @Suppress("UNCHECKED_CAST")
        val document = (JsonSlurper().parse(sourceManifest) as Map<String, Any?>).toMutableMap()
        check(document["schemaVersion"] == 2) { "Unsupported runtime manifest schema" }
        val available = document["available"] == true
        @Suppress("UNCHECKED_CAST")
        val libraries = (document["nativeLibraries"] as? List<Map<String, String>>).orEmpty()
        val names = if (kind == "engine") prootNames else prorootNames
        val selectedLibraries = if (kind == "image") emptyList() else libraries.filter { it["packagedName"] in names }
        document["nativeLibraries"] = selectedLibraries
        if (kind != "image") document.remove("rootfs")

        fun stage(name: String, expectedHash: String, destination: File) {
            check(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid artifact filename: $name" }
            val source = dist.resolve(name)
            check(source.isFile) { "Runtime artifact is missing: $source" }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedHash, ignoreCase = true)) { "Runtime artifact checksum mismatch for $name" }
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
        if (available) {
            if (kind == "image") {
                val rootfs = document["rootfs"] as Map<*, *>
                val name = rootfs["file"] as String
                stage(name, rootfs["sha256"] as String, runtimeAssets.resolve(name))
            } else {
                check(selectedLibraries.map { it.getValue("packagedName") }.toSet() == names) {
                    "The $kind manifest must include all required native libraries: $names"
                }
                val abi = document["abi"] as String
                check(abi == "arm64-v8a") { "Unsupported runtime ABI: $abi" }
                selectedLibraries.forEach { library ->
                    stage(library.getValue("file"), library.getValue("sha256"),
                        jni.resolve("$abi/${library.getValue("packagedName")}"))
                }
            }
        }
        runtimeAssets.resolve(manifestName).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n")
    }
}

tasks.register("validatePublicationArtifacts") {
    dependsOn("prepareRuntimeAssets")
    doLast {
        val document = JsonSlurper().parse(assetsOutput.get().file("runtime/$manifestName").asFile) as Map<*, *>
        check(document["available"] == true) {
            "Cannot publish $project: runtime artifacts are unavailable. Build or supply the $kind artifacts first."
        }
    }
}
