package ai.meteor.dshmobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
    private val rootAccess: RootAccessController,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var outputJob: Job? = null
    private var runningMode: RuntimeMode? = null
    private var chrootPidFile: Path? = null

    suspend fun start(
        runtime: InstalledRuntime,
        mode: RuntimeMode,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RuntimeSession = withContext(Dispatchers.IO) {
        check(process?.isAlive != true) { "The runtime is already running" }
        val data = prepareDataDirectories()
        val token = randomToken()
        val readiness = CompletableDeferred<Int>()
        configureResolver(runtime.rootfs)

        val child = when (mode) {
            RuntimeMode.Proot -> startProot(runtime, data, token)
            RuntimeMode.Chroot -> startChroot(runtime, data, token)
        }
        process = child
        runningMode = mode

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
            Log.i(LOG_TAG, "${mode.name} process exited with code $exitCode")
            if (!readiness.isCompleted) readiness.completeExceptionally(
                IllegalStateException("DSH exited before becoming ready (exit=$exitCode)"),
            )
            process = null
            runningMode = null
            chrootPidFile = null
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
        val mode = runningMode
        if (mode == RuntimeMode.Chroot) {
            chrootPidFile?.let { pidFile ->
                rootAccess.execute(buildChrootStopCommand(pidFile))
            }
        } else {
            child.destroy()
        }
        val pollAttempts = if (mode == RuntimeMode.Chroot) {
            CHROOT_STOP_POLL_ATTEMPTS
        } else {
            STOP_POLL_ATTEMPTS
        }
        repeat(pollAttempts) {
            if (!child.isAlive) return@withContext
            delay(STOP_POLL_MILLIS)
        }
        child.destroyForcibly()
        outputJob?.cancel()
        process = null
        runningMode = null
        chrootPidFile = null
    }

    fun close() {
        scope.cancel()
    }

    private fun startProot(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        token: String,
    ): Process {
        val nativeDirectory = Paths.get(appContext.applicationInfo.nativeLibraryDir)
        val proot = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.prootLibrary)
        val loader = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.loaderLibrary)
        return ProcessBuilder(buildProotCommand(runtime, proot, data, token))
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
    }

    private fun startChroot(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        token: String,
    ): Process {
        val controlDirectory = appContext.cacheDir.toPath().resolve("chroot")
        Files.createDirectories(controlDirectory)
        val script = controlDirectory.resolve("run-${System.nanoTime()}.sh")
        val pidFile = controlDirectory.resolve("session.pid")
        Files.deleteIfExists(pidFile)
        Files.write(
            script,
            buildChrootScript(runtime, data, token, pidFile).toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        chrootPidFile = pidFile
        val command = "exec /system/bin/sh ${shellQuote(script.toString())}"
        return rootAccess.start(command)
    }

    private fun buildProotCommand(
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

    private fun buildChrootScript(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        token: String,
        pidFile: Path,
    ): String = chrootLaunchScript(
        rootfs = runtime.rootfs,
        home = data.home,
        dshHome = data.dshHome,
        workspaces = data.workspaces,
        pidFile = pidFile,
        token = token,
        guestCommand = runtime.manifest.entrypoint.guestCommand,
        appUid = android.os.Process.myUid(),
        appGid = android.system.Os.getgid(),
        appPid = android.os.Process.myPid(),
    )

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

internal fun chrootLaunchScript(
    rootfs: Path,
    home: Path,
    dshHome: Path,
    workspaces: Path,
    pidFile: Path,
    token: String,
    guestCommand: String,
    appUid: Int,
    appGid: Int,
    appPid: Int,
): String = """#!/system/bin/sh
set -u

if [ "${'$'}{1:-}" != "--isolated" ] && command -v unshare >/dev/null 2>&1; then
    if unshare -m /system/bin/true >/dev/null 2>&1; then
        exec unshare -m /system/bin/sh "${'$'}0" --isolated
    fi
fi

if [ "${'$'}{1:-}" = "--isolated" ]; then
    mount --make-rprivate / 2>/dev/null || true
fi

rm -f "${'$'}0"

ROOTFS=${shellQuote(rootfs.toString())}
HOME_SOURCE=${shellQuote(home.toString())}
DSH_HOME_SOURCE=${shellQuote(dshHome.toString())}
WORKSPACES_SOURCE=${shellQuote(workspaces.toString())}
SESSION_PID=${shellQuote(pidFile.toString())}
APP_OWNER=${shellQuote("$appUid:$appGid")}
APP_PID=${shellQuote(appPid.toString())}
CHILD_PID=""
WATCHDOG_PID=""

cleanup() {
    trap - EXIT INT TERM HUP
    if [ -n "${'$'}CHILD_PID" ]; then
        kill -TERM "${'$'}CHILD_PID" 2>/dev/null || true
        wait "${'$'}CHILD_PID" 2>/dev/null || true
    fi
    if [ -n "${'$'}WATCHDOG_PID" ]; then
        kill "${'$'}WATCHDOG_PID" 2>/dev/null || true
        wait "${'$'}WATCHDOG_PID" 2>/dev/null || true
    fi
    umount "${'$'}ROOTFS/workspace" 2>/dev/null || true
    umount "${'$'}ROOTFS/dsh-home" 2>/dev/null || true
    umount "${'$'}ROOTFS/root" 2>/dev/null || true
    umount "${'$'}ROOTFS/sys" 2>/dev/null || true
    umount "${'$'}ROOTFS/proc" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev/pts" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev/shm" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev" 2>/dev/null || true
    chown -R "${'$'}APP_OWNER" "${'$'}ROOTFS" "${'$'}HOME_SOURCE" \
        "${'$'}DSH_HOME_SOURCE" "${'$'}WORKSPACES_SOURCE" 2>/dev/null || true
    rm -f "${'$'}SESSION_PID"
}

trap cleanup EXIT
trap 'exit 143' INT TERM HUP

if [ "${'$'}(id -u)" != "0" ]; then
    echo "chroot mode requires uid 0" >&2
    exit 126
fi

echo "${'$'}${'$'}" > "${'$'}SESSION_PID"
(while kill -0 "${'$'}APP_PID" 2>/dev/null; do sleep 2; done; kill -TERM "${'$'}${'$'}") &
WATCHDOG_PID="${'$'}!"
mkdir -p "${'$'}ROOTFS/dev" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" \
    "${'$'}ROOTFS/root" "${'$'}ROOTFS/dsh-home" "${'$'}ROOTFS/workspace"
mount --bind /dev "${'$'}ROOTFS/dev" || exit 120
[ ! -d /dev/pts ] || mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || exit 120
[ ! -d /dev/shm ] || mount --bind /dev/shm "${'$'}ROOTFS/dev/shm" || exit 120
mount -t proc proc "${'$'}ROOTFS/proc" || exit 121
mount --bind /sys "${'$'}ROOTFS/sys" || exit 122
mount --bind "${'$'}HOME_SOURCE" "${'$'}ROOTFS/root" || exit 123
mount --bind "${'$'}DSH_HOME_SOURCE" "${'$'}ROOTFS/dsh-home" || exit 124
mount --bind "${'$'}WORKSPACES_SOURCE" "${'$'}ROOTFS/workspace" || exit 125

chroot "${'$'}ROOTFS" /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DSH_HOME=/dsh-home DSH_PERMISSION_MODE=danger-full-access \
    DSH_MOBILE_TOKEN=${shellQuote(token)} \
    ${shellQuote(guestCommand)} &
CHILD_PID="${'$'}!"
wait "${'$'}CHILD_PID"
STATUS="${'$'}?"
CHILD_PID=""
exit "${'$'}STATUS"
""".trimIndent() + "\n"

internal fun buildChrootStopCommand(pidFile: Path): String = """
PID_FILE=${shellQuote(pidFile.toString())}
[ -r "${'$'}PID_FILE" ] || exit 0
PID="${'$'}(cat "${'$'}PID_FILE")"
case "${'$'}PID" in *[!0-9]*|'') exit 1;; esac
kill -TERM "${'$'}PID"
""".trimIndent()

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
private const val CHROOT_STOP_POLL_ATTEMPTS = 300
private const val STOP_POLL_MILLIS = 100L
private const val MAX_LOG_LINE_CHARS = 4_096
private const val LOG_TAG = "DshRuntime"
