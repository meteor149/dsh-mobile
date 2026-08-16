package ai.meteor.dshmobile.runtime

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeManifest(
    val schemaVersion: Int,
    val available: Boolean,
    val runtimeVersion: String,
    val abi: String,
    val rootfs: RootfsArtifact? = null,
    val nativeLibraries: List<NativeArtifact> = emptyList(),
    val entrypoint: RuntimeEntrypoint,
    val sources: RuntimeSources? = null,
)

@Serializable
data class RootfsArtifact(
    val file: String,
    val sha256: String,
    val compressedBytes: Long,
    val minimumFreeBytes: Long,
)

@Serializable
data class NativeArtifact(
    val file: String,
    val packagedName: String,
    val sha256: String,
)

@Serializable
data class RuntimeEntrypoint(
    val prootLibrary: String,
    val loaderLibrary: String,
    val guestCommand: String,
)

@Serializable
data class RuntimeSources(
    val ubuntuImage: String,
    val nodeVersion: String,
    val nodeDistributionSha256: String,
    val dshVersion: String,
    val dshPackageIntegrity: String,
    val termuxProotVersion: String,
    val termuxProotCommit: String,
    val termuxPackagesCommit: String,
)

data class InstalledRuntime(
    val manifest: RuntimeManifest,
    val rootfs: java.nio.file.Path,
    val runtimeDirectory: java.nio.file.Path,
)
