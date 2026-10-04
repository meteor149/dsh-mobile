package ai.meteor.dshmobile.download

import java.net.URI
import java.net.URLDecoder

internal fun isDownloadOrigin(url: String, origin: String): Boolean = runCatching {
    val target = URI(url)
    val expected = URI(origin)
    target.scheme == "http" && target.host == "127.0.0.1" && target.port == expected.port &&
        expected.scheme == target.scheme && expected.host == target.host && target.rawUserInfo == null
}.getOrDefault(false)

internal fun safeDownloadName(name: String?): String = name.orEmpty()
    .substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().take(180)
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "download"

internal fun responseFilename(disposition: String?, fallback: String): String {
    val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(disposition.orEmpty())?.groupValues?.get(1)
    val ordinary = Regex("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
        .find(disposition.orEmpty())?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
    return safeDownloadName(encoded?.let { runCatching { URLDecoder.decode(it.trim().replace("+", "%2B"), "UTF-8") }.getOrNull() }
        ?: ordinary ?: fallback)
}
