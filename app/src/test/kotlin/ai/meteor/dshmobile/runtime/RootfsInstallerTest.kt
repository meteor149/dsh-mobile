package ai.meteor.dshmobile.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.nio.file.Paths

class RootfsInstallerTest {
    @Test
    fun archivePathsAreNormalizedWithoutEscaping() {
        assertEquals(Paths.get("usr", "bin", "bash"), RootfsInstaller.validatedArchivePath("./usr/bin/bash"))
        assertNull(RootfsInstaller.validatedArchivePath("./"))
        assertFailsWith<IllegalArgumentException> {
            RootfsInstaller.validatedArchivePath("../../data/data/secret")
        }
        assertFailsWith<IllegalArgumentException> {
            RootfsInstaller.validatedArchivePath("/absolute/path")
        }
    }
}
