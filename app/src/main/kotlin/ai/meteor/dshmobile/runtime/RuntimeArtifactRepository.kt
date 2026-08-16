package ai.meteor.dshmobile.runtime

import android.content.Context
import java.io.InputStream
import kotlinx.serialization.json.Json

class RuntimeArtifactRepository(
    context: Context,
) {
    private val assets = context.applicationContext.assets
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun readManifest(): RuntimeManifest = assets
        .open(MANIFEST_ASSET)
        .bufferedReader()
        .use { reader -> json.decodeFromString<RuntimeManifest>(reader.readText()) }
        .also { manifest ->
            require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported runtime manifest schema: ${manifest.schemaVersion}"
            }
            if (manifest.available) requireNotNull(manifest.rootfs) {
                "An available runtime manifest must declare a rootfs artifact"
            }
        }

    fun openRootfs(manifest: RuntimeManifest): InputStream {
        val rootfs = requireNotNull(manifest.rootfs)
        return assets.open("runtime/${rootfs.file}")
    }

    private companion object {
        const val MANIFEST_ASSET = "runtime/runtime-manifest.json"
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
