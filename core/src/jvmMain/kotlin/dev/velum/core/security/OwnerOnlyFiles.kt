package dev.velum.core.security

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

/** POSIX owner-read/write only (`rw-------`). */
internal val OWNER_READ_WRITE: Set<PosixFilePermission> = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

/**
 * Optional probe invoked immediately after an owner-only temp file is created
 * (before any bytes are written). Used by JVM tests to assert mode-at-creation
 * on the same path [DesktopSecureStore] uses for key and properties writes.
 */
internal val ownerOnlyTempCreatedProbe: AtomicReference<((Path) -> Unit)?> =
    AtomicReference(null)

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
 * Create [path] restricted to the owning principal via ACL (Windows / ACL-capable stores).
 * Permissions: read/write data, read/write attributes, read/write ACL.
 *
 * Prefer [Files.createFile] with an `acl:acl` [FileAttribute] so the owner-only ACL
 * is applied at creation. If the store rejects that attribute (or the owner cannot
 * be resolved beforehand), falls back to create-then-[applyOwnerOnlyAcl] — that
 * path leaves a brief window where the new file inherits the parent directory ACL.
 */
internal fun createOwnerOnlyAclFile(path: Path): Path {
    Files.createDirectories(path.parent)
    val owner = resolveAclOwner(path.parent)
    if (owner != null) {
        try {
            return Files.createFile(path, ownerOnlyAclAttribute(owner))
        } catch (_: UnsupportedOperationException) {
            // Fall through: create then tighten (documented window above).
        } catch (_: IllegalArgumentException) {
            // Some providers reject acl:acl on createFile.
        }
    }
    val created = Files.createFile(path)
    applyOwnerOnlyAcl(created)
    return created
}

internal fun ownerOnlyAclAttribute(owner: UserPrincipal): FileAttribute<List<AclEntry>> {
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(OWNER_ACL_PERMISSIONS)
        .build()
    val acl = listOf(entry)
    return object : FileAttribute<List<AclEntry>> {
        override fun name(): String = "acl:acl"
        override fun value(): List<AclEntry> = acl
    }
}

private val OWNER_ACL_PERMISSIONS: EnumSet<AclEntryPermission> = EnumSet.of(
    AclEntryPermission.READ_DATA,
    AclEntryPermission.WRITE_DATA,
    AclEntryPermission.READ_ATTRIBUTES,
    AclEntryPermission.WRITE_ATTRIBUTES,
    AclEntryPermission.READ_ACL,
    AclEntryPermission.WRITE_ACL,
)

/** Best-effort owner principal from [directory] (ACL view or [Files.getOwner]). */
internal fun resolveAclOwner(directory: Path): UserPrincipal? {
    Files.getFileAttributeView(directory, AclFileAttributeView::class.java)?.owner?.let { return it }
    return runCatching { Files.getOwner(directory) }.getOrNull()
}

/** Best-effort owner-only ACL; no-op when the ACL view is unavailable. */
internal fun applyOwnerOnlyAcl(path: Path): Boolean {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
    val owner = view.owner
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(OWNER_ACL_PERMISSIONS)
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
 * when available; ACL `acl:acl` FileAttribute at creation when possible).
 *
 * ACL fallback (create then [applyOwnerOnlyAcl]) leaves a brief inherited-ACL window —
 * see [createOwnerOnlyAclFile].
 */
internal fun createOwnerOnlyTempFile(directory: Path, prefix: String, suffix: String): Path {
    Files.createDirectories(directory)
    val tmp = if (supportsPosixPermissions()) {
        try {
            Files.createTempFile(
                directory,
                prefix,
                suffix,
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            createTempFileWithAcl(directory, prefix, suffix)
        }
    } else {
        createTempFileWithAcl(directory, prefix, suffix)
    }
    ownerOnlyTempCreatedProbe.get()?.invoke(tmp)
    return tmp
}

private fun createTempFileWithAcl(directory: Path, prefix: String, suffix: String): Path {
    val owner = resolveAclOwner(directory)
    if (owner != null) {
        try {
            return Files.createTempFile(directory, prefix, suffix, ownerOnlyAclAttribute(owner))
        } catch (_: UnsupportedOperationException) {
            // Fall through.
        } catch (_: IllegalArgumentException) {
            // Fall through.
        }
    }
    // Remaining window: temp inherits parent ACL until applyOwnerOnlyAcl runs.
    return Files.createTempFile(directory, prefix, suffix).also { applyOwnerOnlyAcl(it) }
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
 * Atomically rename [path] to `[filename].bad-<timestamp>` (owner-only on the
 * destination). Used to quarantine a wrong-length key file before minting a new one.
 */
internal fun quarantineToBadSibling(path: Path): Path {
    val bad = path.resolveSibling("${path.fileName}.bad-${System.currentTimeMillis()}")
    try {
        Files.move(path, bad, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(path, bad)
    }
    setOwnerOnlyPermissions(bad)
    if (!supportsPosixPermissions()) {
        applyOwnerOnlyAcl(bad)
    }
    return bad
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
