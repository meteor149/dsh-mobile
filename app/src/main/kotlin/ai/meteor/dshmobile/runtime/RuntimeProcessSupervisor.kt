package ai.meteor.dshmobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class RuntimeSession(
    val authenticatedUrl: String,
)

class RuntimeProcessSupervisor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var outputJob: Job? = null

    suspend fun start(
        runtime: InstalledRuntime,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RuntimeSession = withContext(Dispatchers.IO) {
        check(process?.isAlive != true) { "The runtime is already running" }
        val nativeDirectory = Paths.get(appContext.applicationInfo.nativeLibraryDir)
        val proot = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.prootLibrary)
        val loader = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.loaderLibrary)
        val data = prepareDataDirectories()
        val token = randomToken()
        val readiness = CompletableDeferred<Int>()
        configureResolver(runtime.rootfs)

        val command = buildCommand(runtime, proot, data, token)
        val child = ProcessBuilder(command)
            .directory(runtime.runtimeDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["HOME"] = data.home.toString()
                environment()["TMPDIR"] = data.temporary.toString()
                environment()["PROOT_TMP_DIR"] = data.temporary.toString()
                environment()["PROOT_LOADER"] = loader.toString()
                environment()["LD_LIBRARY_PATH"] = nativeDirectory.toString()
                environment()["LANG"] = "C.UTF-8"
            }
            .start()
        process = child

        outputJob = scope.launch {
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.take(MAX_LOG_LINE_CHARS)
                    Log.i(LOG_TAG, line)
                    onLog(line)
                    READY_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { port ->
                        if (!readiness.isCompleted) readiness.complete(port)
                    }
                }
            }
        }
        scope.launch {
            val exitCode = child.waitFor()
            Log.i(LOG_TAG, "PRoot process exited with code $exitCode")
            if (!readiness.isCompleted) readiness.completeExceptionally(
                IllegalStateException("DSH exited before becoming ready (exit=$exitCode)"),
            )
            process = null
            onExit(exitCode)
        }

        try {
            val port = withTimeout(START_TIMEOUT_MILLIS) { readiness.await() }
            RuntimeSession("http://127.0.0.1:$port/?token=$token")
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val child = process ?: return@withContext
        child.destroy()
        repeat(STOP_POLL_ATTEMPTS) {
            if (!child.isAlive) return@withContext
            delay(STOP_POLL_MILLIS)
        }
        child.destroyForcibly()
        outputJob?.cancel()
        process = null
    }

    fun close() {
        scope.cancel()
    }

    private fun buildCommand(
        runtime: InstalledRuntime,
        proot: Path,
        data: RuntimeDataDirectories,
        token: String,
    ): List<String> = buildList {
        add(proot.toString())
        add("--kill-on-exit")
        add("--link2symlink")
        add("--sysvipc")
        add("-0")
        add("-r")
        add(runtime.rootfs.toString())
        bindIfReadable(Paths.get("/dev"), "/dev")
        bindIfReadable(Paths.get("/dev/null"), "/dev/null")
        bindIfReadable(Paths.get("/dev/urandom"), "/dev/urandom")
        bindIfReadable(Paths.get("/dev/random"), "/dev/random")
        bindIfReadable(Paths.get("/dev/zero"), "/dev/zero")
        bindIfReadable(Paths.get("/proc"), "/proc")
        bindIfReadable(Paths.get("/sys"), "/sys")
        bind(data.home, "/root")
        bind(data.dshHome, "/dsh-home")
        bind(data.workspaces, "/workspace")
        add("-w")
        add("/workspace")
        add("/usr/bin/env")
        add("-i")
        add("HOME=/root")
        add("USER=root")
        add("LOGNAME=root")
        add("SHELL=/bin/bash")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        add("DSH_HOME=/dsh-home")
        add("DSH_PERMISSION_MODE=danger-full-access")
        add("DSH_MOBILE_TOKEN=$token")
        add(runtime.manifest.entrypoint.guestCommand)
    }

    private fun MutableList<String>.bindIfReadable(source: Path, target: String) {
        if (Files.exists(source) && Files.isReadable(source)) bind(source, target)
    }

    private fun MutableList<String>.bind(source: Path, target: String) {
        add("-b")
        add("$source:$target")
    }

    private fun prepareDataDirectories(): RuntimeDataDirectories {
        val dataRoot = appContext.filesDir.toPath().resolve("linux-data")
        return RuntimeDataDirectories(
            home = dataRoot.resolve("home"),
            dshHome = dataRoot.resolve("dsh-home"),
            workspaces = dataRoot.resolve("workspaces"),
            temporary = appContext.cacheDir.toPath().resolve("proot"),
        ).also { directories ->
            listOf(directories.home, directories.dshHome, directories.workspaces, directories.temporary)
                .forEach(Files::createDirectories)
        }
    }

    private fun configureResolver(rootfs: Path) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress }
            .distinct()
        if (servers.isEmpty()) return

        val resolvConf = rootfs.resolve("etc/resolv.conf")
        Files.createDirectories(resolvConf.parent)
        Files.deleteIfExists(resolvConf)
        val contents = servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
        Files.write(resolvConf, contents.toByteArray(Charsets.UTF_8))
    }

    private fun requireExecutable(directory: Path, name: String): Path {
        val path = directory.resolve(name)
        require(Files.isRegularFile(path) && Files.isExecutable(path)) {
            "Required executable is not available in the APK native libraries: $name"
        }
        return path
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

private data class RuntimeDataDirectories(
    val home: Path,
    val dshHome: Path,
    val workspaces: Path,
    val temporary: Path,
)

private val READY_PATTERN = Regex("dsh-mobile gateway: http://127\\.0\\.0\\.1:(\\d+)")
private const val TOKEN_BYTES = 32
private const val START_TIMEOUT_MILLIS = 90_000L
private const val STOP_POLL_ATTEMPTS = 30
private const val STOP_POLL_MILLIS = 100L
private const val MAX_LOG_LINE_CHARS = 4_096
private const val LOG_TAG = "DshRuntime"
