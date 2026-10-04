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
            "opt/dsh-mobile/gateway.mjs", "opt/dsh-mobile/android.patch.yml", "opt/dsh-mobile/bin/dsh-mobile-gateway",
            "opt/dsh-mobile/compat/file-publication.mjs", "opt/dsh-mobile/compat/file-publication.py"),
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

    fun command(payload: InstalledRuntime, token: String, mode: RuntimeMode = RuntimeMode.Proot): UbuntuCommand {
        val home = context.filesDir.toPath().resolve("linux-data/dsh-home")
        Files.createDirectories(home)
        // proroot's scandir translates a guest name to a host path before glibc
        // performs another intercepted open. Keep those host paths stable through
        // the second translation, including the Ubuntu tree and all app binds.
        val hostAliases = if (mode == RuntimeMode.Proroot) {
            listOf(context.filesDir.toPath(), context.cacheDir.toPath())
                .associate { directory -> directory.toString() to directory }
        } else emptyMap()
        return UbuntuCommand(
            arguments = listOf("/opt/dsh-mobile/bin/dsh-mobile-gateway"),
            environment = mapOf("DSH_HOME" to "/dsh-home", "DSH_MOBILE_TOKEN" to token,
                "DSH_PERMISSION_MODE" to "danger-full-access",
                // PRoot's simulated hard links in the shared cache resolve to non-.node
                // filenames under proroot, causing Node to parse native binaries as JS.
                // Load the checksum-verified packaged binaries directly instead.
                "NARB_DISABLE_NATIVE_CACHE" to "1",
                "DSH_MOBILE_RUNTIME_MODE" to mode.name.lowercase(),
                "PATH" to "/opt/dsh-mobile/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"),
            bindings = mapOf("/opt/node" to payload.rootfs.resolve("opt/node"),
                "/opt/dsh" to payload.rootfs.resolve("opt/dsh"),
                "/opt/dsh-mobile" to payload.rootfs.resolve("opt/dsh-mobile"), "/dsh-home" to home) + hostAliases,
        )
    }
}
