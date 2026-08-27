package ai.meteor.dshmobile.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the device's su implementation so its root manager can present the authorization UI. */
class RootAccessController {
    suspend fun request(): Boolean = withContext(Dispatchers.IO) {
        execute("id -u", ROOT_REQUEST_TIMEOUT_SECONDS)?.let { result ->
            result.exitCode == 0 && isRootUidOutput(result.output)
        } ?: false
    }

    fun start(command: String): Process {
        var lastError: Throwable? = null
        for (candidate in suCandidates()) {
            try {
                return ProcessBuilder(candidate, "-c", command)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("No supported su command is available", lastError)
    }

    suspend fun execute(command: String, timeoutSeconds: Long = ROOT_COMMAND_TIMEOUT_SECONDS): RootCommandResult? =
        withContext(Dispatchers.IO) {
            val process = runCatching { start(command) }.getOrNull() ?: return@withContext null
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext null
            }
            RootCommandResult(
                exitCode = process.exitValue(),
                output = process.inputStream.bufferedReader().use { it.readText() },
            )
        }

    private fun suCandidates(): List<String> = buildList {
        listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/debug_ramdisk/su")
            .filterTo(this) { File(it).canExecute() }
        add("su")
    }.distinct()
}

internal fun isRootUidOutput(output: String): Boolean =
    output.lineSequence().any { it.trim() == "0" }

data class RootCommandResult(
    val exitCode: Int,
    val output: String,
)

private const val ROOT_REQUEST_TIMEOUT_SECONDS = 60L
private const val ROOT_COMMAND_TIMEOUT_SECONDS = 15L
