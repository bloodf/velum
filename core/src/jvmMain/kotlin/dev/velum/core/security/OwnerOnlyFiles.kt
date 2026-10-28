package dev.velum.core.security

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** POSIX owner-read/write only (`rw-------`). */
internal val OWNER_READ_WRITE: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

internal fun supportsPosixPermissions(): Boolean =
    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

/**
 * Create [path] with owner-only permissions when the FS supports POSIX attributes.
 * Falls back to a plain [Files.createFile] on non-POSIX stores.
 */
internal fun createOwnerOnlyFile(path: Path): Path {
    Files.createDirectories(path.parent)
    return try {
        val attrs = PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE)
        Files.createFile(path, attrs)
    } catch (_: UnsupportedOperationException) {
        Files.createFile(path)
    }
}

/** Best-effort `rw-------` on an existing path; no-op when POSIX is unavailable. */
internal fun setOwnerOnlyPermissions(path: Path) {
    if (!supportsPosixPermissions()) return
    runCatching {
        Files.setPosixFilePermissions(path, OWNER_READ_WRITE)
    }
}

/**
 * Write [snapshot] to [target] via a sibling temp file + [StandardCopyOption.ATOMIC_MOVE]
 * (falls back to non-atomic replace when the store does not support atomic move).
 */
internal fun atomicWriteProperties(target: Path, snapshot: Map<String, String>, comment: String) {
    Files.createDirectories(target.parent)
    val tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        setOwnerOnlyPermissions(tmp)
        val props = Properties()
        snapshot.forEach { (k, v) -> props.setProperty(k, v) }
        Files.newOutputStream(tmp).use { props.store(it, comment) }
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnlyPermissions(target)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
