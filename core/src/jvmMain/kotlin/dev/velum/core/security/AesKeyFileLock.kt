package dev.velum.core.security

import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-process + same-JVM serialization for AES key file load/create.
 *
 * Uses an owner-only sibling `*.lock` with [FileChannel.lock] for inter-process
 * exclusion, and a process-wide monitor keyed by a **stable** canonical path so
 * concurrent threads in one JVM do not hit
 * [java.nio.channels.OverlappingFileLockException].
 *
 * The map key is `parent.toRealPath().resolve(fileName)` after creating the
 * parent directory — not `keyFile.toRealPath()`, which throws while the key is
 * missing and would otherwise flip the map key once the file appears.
 *
 * Lock files that are symbolic links are refused ([Files.isSymbolicLink] and
 * [LinkOption.NOFOLLOW_LINKS] on open where supported).
 */
internal object AesKeyFileLock {
    private val jvmLocks = ConcurrentHashMap<String, Any>()

    fun <T> withLock(keyFile: Path, block: () -> T): T {
        Files.createDirectories(keyFile.parent)
        val stableKey = keyFile.parent.toRealPath().resolve(keyFile.fileName).toString()
        val jvmLock = jvmLocks.computeIfAbsent(stableKey) { Any() }
        synchronized(jvmLock) {
            val lockPath = keyFile.resolveSibling("${keyFile.fileName}.lock")
            ensureOwnerOnlyLockFile(lockPath)
            return openLockChannel(lockPath).use { channel ->
                channel.lock().use {
                    block()
                }
            }
        }
    }

    private fun ensureOwnerOnlyLockFile(lockPath: Path) {
        if (Files.isSymbolicLink(lockPath)) {
            error("AES key lock file must not be a symbolic link: $lockPath")
        }
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createOwnerOnlyFile(lockPath)
            } catch (_: FileAlreadyExistsException) {
                // Another process created it.
            }
        }
        if (Files.isSymbolicLink(lockPath)) {
            error("AES key lock file must not be a symbolic link: $lockPath")
        }
    }

    private fun openLockChannel(lockPath: Path): FileChannel {
        if (Files.isSymbolicLink(lockPath)) {
            error("AES key lock file must not be a symbolic link: $lockPath")
        }
        return try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: UnsupportedOperationException) {
            // Some providers reject NOFOLLOW_LINKS; symlink check above still applies.
            if (Files.isSymbolicLink(lockPath)) {
                error("AES key lock file must not be a symbolic link: $lockPath")
            }
            FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
        }
    }
}
