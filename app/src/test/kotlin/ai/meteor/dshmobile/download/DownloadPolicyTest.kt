package ai.meteor.dshmobile.download

import org.junit.Test
import kotlin.test.*

class DownloadPolicyTest {
    @Test fun acceptsOnlyAuthenticatedGatewayOrigin() {
        val origin = "http://127.0.0.1:8080"
        assertTrue(isDownloadOrigin("$origin/files/a?x=1", origin))
        listOf("http://127.0.0.1:8081/a", "https://127.0.0.1:8080/a", "http://localhost:8080/a",
            "http://evil.test:8080/a", "http://user@127.0.0.1:8080/a", "blob:$origin/a", "garbage").forEach {
            assertFalse(isDownloadOrigin(it, origin), it)
        }
    }
    @Test fun preservesUnicodeNamesAndSanitizesPaths() {
        assertEquals("报告.txt", safeDownloadName("../../报告.txt"))
        assertEquals("download", safeDownloadName(".."))
        assertEquals("a_b.txt", safeDownloadName("a\u0000b.txt"))
        assertEquals("报告+a.txt", responseFilename("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A+a.txt", "fallback"))
        assertEquals("report.txt", responseFilename("attachment; filename=\"report.txt\"", "fallback"))
        assertEquals("fallback", responseFilename(null, "fallback"))
    }
}
