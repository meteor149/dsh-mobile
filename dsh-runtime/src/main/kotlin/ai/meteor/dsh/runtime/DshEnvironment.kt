package ai.meteor.dsh.runtime

import ai.meteor.ubuntu.runtime.*
import ai.meteor.ubuntu.runtime.RuntimeMode as UbuntuMode
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The proprietary backend belongs to this app, not the reusable Ubuntu library. */
enum class RuntimeMode { Proot, Proroot, Chroot }

class DshEnvironment(context: Context) {
    private val installer = RootfsInstaller(context, RuntimeArtifactRepository(context))
    private val supervisor = DshProcessSupervisor(context, RootAccessController())
    private val mutex = Mutex()
    suspend fun execute(command: UbuntuCommand, mode: RuntimeMode = RuntimeMode.Proot) = mutex.withLock {
        supervisor.execute(requireNotNull(installer.probe()) { "Install Ubuntu first" }, mode, command)
    }
    suspend fun start(command: UbuntuCommand, mode: RuntimeMode = RuntimeMode.Proot,
        onLog: (String) -> Unit = {}, onExit: (Int) -> Unit = {}) = mutex.withLock {
        supervisor.start(requireNotNull(installer.probe()) { "Install Ubuntu first" }, mode, command, onLog, onExit)
    }
    suspend fun stop() = mutex.withLock { supervisor.stop() }
}

internal class DshProcessSupervisor(context: Context, rootAccess: RootAccessController) {
    private val context = context.applicationContext
    private val ubuntu = UbuntuProcessSupervisor(context, rootAccess)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var groupFile: Path? = null
    private var outputJob: Job? = null
    private var exitJob: Job? = null

    suspend fun start(runtime: InstalledRuntime, mode: RuntimeMode, command: UbuntuCommand,
        onLog: (String) -> Unit = {}, onExit: (Int) -> Unit = {}) {
        if (mode != RuntimeMode.Proroot) {
            ubuntu.start(runtime, mode.ubuntu(), command, onLog, onExit)
            return
        }
        withContext(Dispatchers.IO) {
            val child = launchProroot(runtime, command)
            val pidFile = groupFile
            outputJob = scope.launch {
                try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLog(it.take(4096)) } } }
                catch (error: java.io.IOException) { if (isActive && child.isAlive) child.destroy() }
            }
            exitJob = scope.launch {
                val code = child.waitFor()
                signal(pidFile, OsConstants.SIGKILL)
                pidFile?.let(Files::deleteIfExists)
                if (process === child) { process = null; groupFile = null; onExit(code) }
            }
        }
    }

    suspend fun execute(runtime: InstalledRuntime, mode: RuntimeMode, command: UbuntuCommand): UbuntuCommandResult {
        if (mode != RuntimeMode.Proroot) return ubuntu.execute(runtime, mode.ubuntu(), command)
        return withContext(Dispatchers.IO) {
            val child = launchProroot(runtime, command)
            val watcher = CoroutineScope(currentCoroutineContext() + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { stopProroot() } }
            }
            try {
                val output = child.inputStream.bufferedReader().use { it.readText() }
                UbuntuCommandResult(child.waitFor(), output)
            }
            finally { watcher.cancel(); withContext(NonCancellable) { watcher.join(); stopProroot() }; child.inputStream.close() }
        }
    }

    suspend fun stop() { ubuntu.stop(); stopProroot() }

    private suspend fun stopProroot() = withContext(Dispatchers.IO) {
        val child = process ?: return@withContext
        val pidFile = groupFile
        process = null
        outputJob?.cancel()
        signal(pidFile, OsConstants.SIGTERM)
        child.destroy()
        repeat(30) { if (!child.isAlive) return@repeat; delay(100) }
        signal(pidFile, OsConstants.SIGKILL)
        if (child.isAlive) child.destroyForcibly()
        child.waitFor()
        exitJob?.cancel()
        pidFile?.let(Files::deleteIfExists)
        groupFile = null
    }

    private fun launchProroot(runtime: InstalledRuntime, command: UbuntuCommand): Process {
        check(process?.isAlive != true) { "The runtime is already running" }
        command.bindings.values.forEach { require(Files.isDirectory(it)) }
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.distinct()
        if (servers.isNotEmpty()) {
            val resolver = runtime.rootfs.resolve("etc/resolv.conf")
            Files.createDirectories(resolver.parent)
            Files.deleteIfExists(resolver)
            Files.write(resolver, servers.joinToString("\n", postfix = "\n") { "nameserver $it" }.toByteArray(Charsets.UTF_8))
        }
        val native = java.nio.file.Paths.get(context.applicationInfo.nativeLibraryDir)
        val launcher = native.resolve("libproroot.so")
        for (name in listOf("libproroot.so", "libproroot-runtime.so", "libproroot-bridge.so", "libproroot-linker.so", "libproroot-stub-loader.so")) {
            require(Files.isRegularFile(native.resolve(name))) { "Missing proroot library: $name" }
        }
        require(Files.isExecutable(launcher))
        val data = context.filesDir.toPath().resolve("linux-data")
        val home = data.resolve("home")
        val workspace = data.resolve("workspaces")
        val temporary = context.filesDir.toPath().resolve("proroot-tmp")
        val control = context.cacheDir.toPath().resolve("runtime-processes")
        listOf(home, workspace, temporary, control).forEach(Files::createDirectories)
        val pidFile = control.resolve("proroot-${System.nanoTime()}.pid")
        val arguments = buildList {
            addAll(listOf("/system/bin/setsid", "-w", "/system/bin/sh", "-c", "echo \$\$ > \"\$1\"; shift; exec \"\$@\"", "runtime-session", pidFile.toString()))
            addAll(listOf(launcher.toString(), "-r", runtime.rootfs.toString(), "-0", "--link2symlink"))
            (mapOf("/root" to home, "/workspace" to workspace) + command.bindings).forEach { (target, source) -> addAll(listOf("-b", "$source:$target")) }
            addAll(listOf("-w", command.workingDirectory, "/usr/bin/env", "-i", "PROROOT_TMP_DIR=$temporary", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash", "TERM=xterm-256color", "LANG=C.UTF-8", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
            command.environment.forEach { (key, value) -> add("$key=$value") }
            addAll(command.arguments)
        }
        val child = ProcessBuilder(arguments).directory(runtime.runtimeDirectory.toFile()).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(mapOf("HOME" to home.toString(), "TMPDIR" to temporary.toString(), "PROROOT_TMP_DIR" to temporary.toString(), "LANG" to "C.UTF-8"))
        }.start()
        groupFile = pidFile
        process = child
        return child
    }

    private fun signal(file: Path?, signal: Int) {
        val pid = try { file?.let { String(Files.readAllBytes(it), Charsets.UTF_8).trim().toIntOrNull() } } catch (_: java.nio.file.NoSuchFileException) { null }
        if (pid == null || pid <= 1) return
        try { Os.kill(-pid, signal) } catch (error: ErrnoException) { if (error.errno != OsConstants.ESRCH) throw error }
    }

    private fun RuntimeMode.ubuntu() = when (this) {
        RuntimeMode.Proot -> UbuntuMode.Proot
        RuntimeMode.Chroot -> UbuntuMode.Chroot
        RuntimeMode.Proroot -> error("proroot is app-owned")
    }
}
