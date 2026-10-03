package ai.meteor.dsh.runtime

import android.content.Context
import ai.meteor.ubuntu.runtime.*
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DshPayloadManifest(
    val schemaVersion: Int = 1,
    val available: Boolean,
    val version: String,
    val abi: String,
    val archive: RootfsArtifact? = null,
    val nodeVersion: String = "",
    val dshVersion: String = "",
)

/** Installs the application payload separately from the Ubuntu root filesystem. */
class DshInstaller(context: Context) {
    private val context = context.applicationContext
    private val artifacts = RuntimeArtifactRepository(context)
    private val installer = AssetPackageInstaller(
        context, artifacts, directoryName = "dsh-packages",
        openArchive = { manifest -> context.assets.open("dsh/${requireNotNull(manifest.rootfs).file}") },
        requiredPaths = listOf("opt/node/bin/node", "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "opt/dsh-mobile/gateway.mjs", "opt/dsh-mobile/bin/dsh-mobile-gateway"),
    )

    fun readManifest(): DshPayloadManifest = context.assets.open("dsh/dsh-manifest.json")
        .bufferedReader().use { Json.decodeFromString<DshPayloadManifest>(it.readText()) }
        .also { require(it.schemaVersion == 1) { "Unsupported DSH payload schema" } }

    private fun runtimeManifest(): RuntimeManifest {
        val payload = readManifest()
        val ubuntu = artifacts.readManifest()
        require(payload.abi == ubuntu.abi) { "DSH and Ubuntu ABIs differ" }
        return ubuntu.copy(available = payload.available, runtimeVersion = payload.version,
            rootfs = payload.archive, nativeLibraries = emptyList(), sources = null)
    }

    suspend fun probe(): InstalledRuntime? = installer.probe(runtimeManifest())
    suspend fun install(onProgress: (Float, RuntimeMessage) -> Unit = { _, _ -> }): InstalledRuntime =
        installer.install(runtimeManifest(), onProgress)

    fun command(payload: InstalledRuntime, token: String): UbuntuCommand {
        val home = context.filesDir.toPath().resolve("linux-data/dsh-home")
        Files.createDirectories(home)
        return UbuntuCommand(
            arguments = listOf("/opt/dsh-mobile/bin/dsh-mobile-gateway"),
            environment = mapOf("DSH_HOME" to "/dsh-home", "DSH_MOBILE_TOKEN" to token,
                "DSH_PERMISSION_MODE" to "danger-full-access",
                "PATH" to "/opt/dsh-mobile/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"),
            bindings = mapOf("/opt/node" to payload.rootfs.resolve("opt/node"),
                "/opt/dsh" to payload.rootfs.resolve("opt/dsh"),
                "/opt/dsh-mobile" to payload.rootfs.resolve("opt/dsh-mobile"), "/dsh-home" to home),
        )
    }
}
