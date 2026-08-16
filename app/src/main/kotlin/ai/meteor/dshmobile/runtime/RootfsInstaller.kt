package ai.meteor.dshmobile.runtime

import android.content.Context
import android.os.storage.StorageManager
import android.system.Os
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

class RootfsInstaller(
    context: Context,
    private val artifacts: RuntimeArtifactRepository,
) {
    private val appContext = context.applicationContext
    private val runtimeRoot = appContext.filesDir.toPath().resolve("runtime")
    private val versionsRoot = runtimeRoot.resolve("versions")

    suspend fun probe(manifest: RuntimeManifest = artifacts.readManifest()): InstalledRuntime? = withContext(Dispatchers.IO) {
        if (!manifest.available) return@withContext null
        installedRuntime(manifest).takeIf(::isComplete)
    }

    suspend fun install(
        manifest: RuntimeManifest = artifacts.readManifest(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): InstalledRuntime = InstallMutex.withLock {
        withContext(Dispatchers.IO) {
            require(manifest.available) { "This APK does not contain a runtime artifact" }
            require(android.os.Build.SUPPORTED_ABIS.contains(manifest.abi)) {
                "Runtime ${manifest.abi} is incompatible with ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
            }
            val rootfsArtifact = requireNotNull(manifest.rootfs)
            require(allocatableBytes() >= rootfsArtifact.minimumFreeBytes) {
                "At least ${rootfsArtifact.minimumFreeBytes / MEBIBYTE} MiB free space is required"
            }

            val installed = installedRuntime(manifest)
            if (isComplete(installed)) return@withContext installed

            Files.createDirectories(versionsRoot)
            val staging = runtimeRoot.resolve("installing-${safeVersion(manifest.runtimeVersion)}")
            deleteTree(staging)
            Files.createDirectories(staging)

            try {
                onProgress(0.03f, "校验 rootfs")
                verifyRootfs(manifest)
                onProgress(0.12f, "展开 Ubuntu")
                extractRootfs(manifest, staging.resolve("rootfs"), onProgress)
                validateRootfs(staging.resolve("rootfs"))
                Files.write(staging.resolve(INSTALL_MARKER), "${manifest.runtimeVersion}\n".encodeToByteArray())

                val target = installed.runtimeDirectory
                deleteTree(target)
                moveAtomically(staging, target)
                deleteObsoleteVersions(target)
                onProgress(1f, "安装完成")
                installed
            } catch (error: Throwable) {
                deleteTree(staging)
                throw error
            }
        }
    }

    private fun installedRuntime(manifest: RuntimeManifest): InstalledRuntime {
        val directory = versionsRoot.resolve(safeVersion(manifest.runtimeVersion))
        return InstalledRuntime(
            manifest = manifest,
            rootfs = directory.resolve("rootfs"),
            runtimeDirectory = directory,
        )
    }

    private fun isComplete(runtime: InstalledRuntime): Boolean {
        val marker = runtime.runtimeDirectory.resolve(INSTALL_MARKER)
        return runCatching { Files.readAllBytes(marker).decodeToString().trim() == runtime.manifest.runtimeVersion }.getOrDefault(false) &&
            REQUIRED_ROOTFS_PATHS.all { path -> Files.exists(runtime.rootfs.resolve(path), LinkOption.NOFOLLOW_LINKS) }
    }

    private suspend fun verifyRootfs(manifest: RuntimeManifest) {
        val expected = requireNotNull(manifest.rootfs).sha256
        val digest = MessageDigest.getInstance("SHA-256")
        artifacts.openRootfs(manifest).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        require(actual.equals(expected, ignoreCase = true)) {
            "Rootfs checksum mismatch: expected=$expected actual=$actual"
        }
    }

    private suspend fun extractRootfs(
        manifest: RuntimeManifest,
        destination: Path,
        onProgress: (Float, String) -> Unit,
    ) {
        Files.createDirectories(destination)
        val pendingHardLinks = mutableListOf<PendingHardLink>()
        var entries = 0
        artifacts.openRootfs(manifest).buffered().use { compressed ->
            ZstdCompressorInputStream(compressed).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextEntry ?: break
                        extractEntry(tar, entry, destination, pendingHardLinks)
                        entries++
                        if (entries % PROGRESS_ENTRY_INTERVAL == 0) {
                            val progress = (0.12f + entries / PROGRESS_ENTRY_SCALE).coerceAtMost(0.92f)
                            onProgress(progress, "展开 Ubuntu · $entries 项")
                        }
                    }
                }
            }
        }
        pendingHardLinks.forEach { hardLink ->
            ensureSafeParent(destination, hardLink.link.parent)
            require(
                hardLink.target.startsWith(destination) &&
                    Files.isRegularFile(hardLink.target, LinkOption.NOFOLLOW_LINKS),
            ) {
                "Invalid rootfs hard link target: ${hardLink.target}"
            }
            Files.deleteIfExists(hardLink.link)
            try {
                Files.createLink(hardLink.link, hardLink.target)
            } catch (_: UnsupportedOperationException) {
                Files.copy(hardLink.target, hardLink.link, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // Android app UIDs cannot create hard links on /data. Copying the
                // trusted in-rootfs target preserves the userspace content PRoot needs.
                Files.copy(hardLink.target, hardLink.link, StandardCopyOption.REPLACE_EXISTING)
            }
            chmod(hardLink.link, hardLink.mode)
        }
    }

    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        root: Path,
        pendingHardLinks: MutableList<PendingHardLink>,
    ) {
        val relative = validatedArchivePath(entry.name) ?: return
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Rootfs entry escapes the install directory: ${entry.name}" }
        ensureSafeParent(root, target.parent)

        when {
            entry.isDirectory -> Files.createDirectories(target)
            entry.isSymbolicLink -> {
                require('\u0000' !in entry.linkName) { "Invalid rootfs symlink" }
                Files.deleteIfExists(target)
                Files.createSymbolicLink(target, Paths.get(entry.linkName))
            }
            entry.isLink -> {
                val linkTarget = root.resolve(validatedArchivePath(entry.linkName) ?: error("Invalid hard link")).normalize()
                pendingHardLinks += PendingHardLink(target, linkTarget, entry.mode)
            }
            entry.isFile -> {
                Files.newOutputStream(target).use { output -> tar.copyTo(output, COPY_BUFFER_BYTES) }
                chmod(target, entry.mode)
            }
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
            else -> throw IOException("Unsupported rootfs archive entry: ${entry.name}")
        }
        if (entry.isDirectory) chmod(target, entry.mode)
    }

    private fun ensureSafeParent(root: Path, parent: Path?) {
        if (parent == null) return
        var current = root
        root.relativize(parent).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Rootfs archive would traverse a symlink: $current" }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
        }
    }

    private fun validateRootfs(root: Path) {
        require(REQUIRED_ROOTFS_PATHS.all { path -> Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS) }) {
            "Built rootfs is incomplete"
        }
        Files.createDirectories(root.resolve("workspace"))
        Files.createDirectories(root.resolve("root"))
        Files.createDirectories(root.resolve("tmp"))
    }

    private fun allocatableBytes(): Long = runCatching {
        val storage = appContext.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(appContext.filesDir))
    }.getOrElse { appContext.filesDir.usableSpace }

    private fun chmod(path: Path, mode: Int) {
        runCatching { Os.chmod(path.toString(), mode and UNIX_PERMISSION_MASK) }
    }

    private fun moveAtomically(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(path.normalize().startsWith(runtimeRoot.normalize()) && path.normalize() != runtimeRoot.normalize()) {
            "Refusing to delete outside the runtime directory: $path"
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteTree) }
        }
        Files.deleteIfExists(path)
    }

    private fun deleteObsoleteVersions(current: Path) {
        Files.newDirectoryStream(versionsRoot).use { versions ->
            versions
                .filter { it.normalize() != current.normalize() }
                .forEach(::deleteTree)
        }
    }

    internal companion object {
        val InstallMutex = Mutex()

        fun validatedArchivePath(name: String): Path? {
            require('\u0000' !in name) { "Invalid rootfs archive entry" }
            val text = name.removePrefix("./").trimEnd('/')
            if (text.isEmpty() || text == ".") return null
            require(!text.startsWith('/') && '\\' !in text && !text.matches(Regex("^[A-Za-z]:.*"))) {
                "Rootfs entry must use a relative POSIX path: $name"
            }
            val path = Paths.get(text).normalize()
            require(!path.isAbsolute && path.none { component -> component.toString() == ".." }) {
                "Rootfs entry escapes the install directory: $name"
            }
            return path
        }

        private fun safeVersion(version: String): String {
            require(version.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid runtime version: $version" }
            return version
        }
    }
}

private const val INSTALL_MARKER = ".dsh-mobile-installed"
private data class PendingHardLink(val link: Path, val target: Path, val mode: Int)
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val PROGRESS_ENTRY_INTERVAL = 250
private const val PROGRESS_ENTRY_SCALE = 20_000f
private const val UNIX_PERMISSION_MASK = 0x1ff
private const val MEBIBYTE = 1024L * 1024L
private val REQUIRED_ROOTFS_PATHS = listOf(
    "bin/bash",
    "usr/bin/env",
    "usr/bin/git",
    "usr/local/bin/dsh-mobile-gateway",
    "etc/os-release",
)
