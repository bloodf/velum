package dev.velum.core.security

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.Properties

/** POSIX owner-read/write only (`rw-------`). */
internal val OWNER_READ_WRITE: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

internal fun supportsPosixPermissions(): Boolean =
    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

internal fun supportsAclFileAttributes(): Boolean =
    FileSystems.getDefault().supportedFileAttributeViews().contains("acl")

/**
 * Create [path] with owner-only permissions when the FS supports POSIX attributes.
 * On non-POSIX stores that expose [AclFileAttributeView] (e.g. Windows), restrict
 * the ACL to the owner's principal with read/write data, attributes, and ACL.
 * Otherwise falls back to a plain [Files.createFile].
 */
internal fun createOwnerOnlyFile(path: Path): Path {
    Files.createDirectories(path.parent)
    return try {
        val attrs = PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE)
        Files.createFile(path, attrs)
    } catch (_: UnsupportedOperationException) {
        createOwnerOnlyAclFile(path)
    }
}

/**
 * Restrict [path] to the owning principal via ACL (Windows / ACL-capable stores).
 * Permissions: read/write data, read/write attributes, read/write ACL.
 */
internal fun createOwnerOnlyAclFile(path: Path): Path {
    val created = Files.createFile(path)
    applyOwnerOnlyAcl(created)
    return created
}

/** Best-effort owner-only ACL; no-op when the ACL view is unavailable. */
internal fun applyOwnerOnlyAcl(path: Path): Boolean {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
    val owner = view.owner
    val permissions = EnumSet.of(
        AclEntryPermission.READ_DATA,
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.READ_ATTRIBUTES,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.READ_ACL,
        AclEntryPermission.WRITE_ACL,
    )
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(permissions)
        .build()
    view.acl = listOf(entry)
    return true
}

/** Best-effort `rw-------` on an existing path; no-op when POSIX is unavailable. */
internal fun setOwnerOnlyPermissions(path: Path) {
    if (!supportsPosixPermissions()) return
    runCatching {
        Files.setPosixFilePermissions(path, OWNER_READ_WRITE)
    }
}

/**
 * Create a sibling temp file with owner-only mode from creation (POSIX `rw-------`
 * when available; ACL owner-only otherwise).
 */
internal fun createOwnerOnlyTempFile(directory: Path, prefix: String, suffix: String): Path {
    Files.createDirectories(directory)
    return if (supportsPosixPermissions()) {
        try {
            Files.createTempFile(
                directory,
                prefix,
                suffix,
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createTempFile(directory, prefix, suffix).also { applyOwnerOnlyAcl(it) }
        }
    } else {
        Files.createTempFile(directory, prefix, suffix).also { applyOwnerOnlyAcl(it) }
    }
}

/**
 * Write [bytes] to [target] via a sibling temp file + atomic move.
 * The temp file is owner-only from creation so a crash never leaves a world-readable key.
 */
internal fun atomicWriteOwnerOnlyBytes(target: Path, bytes: ByteArray) {
    Files.createDirectories(target.parent)
    val tmp = createOwnerOnlyTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.write(tmp, bytes)
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
        if (!supportsPosixPermissions()) {
            applyOwnerOnlyAcl(target)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/**
 * Write [snapshot] to [target] via a sibling temp file + [StandardCopyOption.ATOMIC_MOVE]
 * (falls back to non-atomic replace when the store does not support atomic move).
 */
internal fun atomicWriteProperties(target: Path, snapshot: Map<String, String>, comment: String) {
    Files.createDirectories(target.parent)
    val tmp = createOwnerOnlyTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
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
        if (!supportsPosixPermissions()) {
            applyOwnerOnlyAcl(target)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}
