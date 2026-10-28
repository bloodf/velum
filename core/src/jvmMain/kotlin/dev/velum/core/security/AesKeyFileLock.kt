package dev.velum.core.security

import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-process + same-JVM serialization for AES key file load/create.
 *
 * Uses an owner-only sibling `*.lock` with [FileChannel.lock] for inter-process
 * exclusion, and a process-wide monitor keyed by canonical path so concurrent
 * threads in one JVM do not hit [java.nio.channels.OverlappingFileLockException].
 */
internal object AesKeyFileLock {
    private val jvmLocks = ConcurrentHashMap<String, Any>()

    fun <T> withLock(keyFile: Path, block: () -> T): T {
        val canonical = try {
            keyFile.toRealPath().toString()
        } catch (_: Exception) {
            keyFile.toAbsolutePath().normalize().toString()
        }
        val jvmLock = jvmLocks.computeIfAbsent(canonical) { Any() }
        synchronized(jvmLock) {
            val lockPath = keyFile.resolveSibling("${keyFile.fileName}.lock")
            Files.createDirectories(lockPath.parent)
            if (!Files.exists(lockPath)) {
                try {
                    createOwnerOnlyFile(lockPath)
                } catch (_: FileAlreadyExistsException) {
                    // Another process created it.
                }
            }
            return FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    block()
                }
            }
        }
    }
}
