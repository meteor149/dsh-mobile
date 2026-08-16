package ai.meteor.dshmobile.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class RuntimeManifestTest {
    @Test
    fun parsesCurrentOfficialPackageSourceMetadata() {
        val manifest = Json.decodeFromString<RuntimeManifest>(
            """
            {
              "schemaVersion": 1,
              "available": true,
              "runtimeVersion": "ubuntu-24.04_dsh-0.1.0-rc.6_1",
              "abi": "arm64-v8a",
              "rootfs": {
                "file": "dsh-ubuntu-arm64.tar.zst",
                "sha256": "rootfs-sha256",
                "compressedBytes": 119985274,
                "minimumFreeBytes": 2147483648
              },
              "nativeLibraries": [],
              "entrypoint": {
                "prootLibrary": "libdsh_proot.so",
                "loaderLibrary": "libdsh_proot_loader.so",
                "guestCommand": "/usr/local/bin/dsh-mobile-gateway"
              },
              "sources": {
                "ubuntuImage": "ubuntu:24.04",
                "nodeVersion": "24.14.1",
                "nodeDistributionSha256": "node-sha256",
                "dshVersion": "0.1.0-rc.6",
                "dshPackageIntegrity": "sha512-integrity",
                "termuxProotVersion": "5.1.107.89",
                "termuxProotCommit": "proot-commit",
                "termuxPackagesCommit": "packages-commit"
              }
            }
            """.trimIndent(),
        )

        assertEquals("node-sha256", manifest.sources?.nodeDistributionSha256)
        assertEquals("sha512-integrity", manifest.sources?.dshPackageIntegrity)
    }
}
