package dev.velum.core.security

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.SecureRandom
import java.util.EnumSet
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** AES-256 raw key length in bytes (shared by vault blob / key-file logic). */
internal const val AES_256_KEY_BYTES: Int = 32

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
 *
 * [onTempCreated] is invoked immediately after creation (before any bytes are written)
 * so tests can assert mode-at-creation without a production global hook.
 */
internal fun createOwnerOnlyTempFile(
    directory: Path,
    prefix: String,
    suffix: String,
    onTempCreated: ((Path) -> Unit)? = null,
): Path {
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
    onTempCreated?.invoke(tmp)
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
 * Write [bytes] to [target] via a sibling temp file + atomic move (replace).
 * The temp file is owner-only from creation so a crash never leaves a world-readable key.
 */
internal fun atomicWriteOwnerOnlyBytes(
    target: Path,
    bytes: ByteArray,
    onTempCreated: ((Path) -> Unit)? = null,
) {
    Files.createDirectories(target.parent)
    val tmp = createOwnerOnlyTempFile(
        directory = target.parent,
        prefix = ".${target.fileName}.",
        suffix = ".tmp",
        onTempCreated = onTempCreated,
    )
    try {
        Files.write(tmp, bytes)
        moveTempToTargetReplacing(tmp, target)
        setOwnerOnlyPermissions(target)
        if (!supportsPosixPermissions()) {
            applyOwnerOnlyAcl(target)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/** Cached hard-link support keyed by canonical parent directory path. */
private val hardLinkSupportedByDir = ConcurrentHashMap<String, Boolean>()

private fun directoryHardLinkCacheKey(directory: Path): String =
    try {
        directory.toRealPath().toString()
    } catch (_: Exception) {
        directory.toAbsolutePath().normalize().toString()
    }

/**
 * Best-effort fsync of a directory after publishing a name into it.
 * Ignored when the platform rejects directory opens (typical on Windows).
 */
internal fun fsyncDirectoryBestEffort(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
            channel.force(/* metaData = */ true)
        }
    } catch (_: Throwable) {
        // Windows / some network FS — ignore.
    }
}

/**
 * Publish [bytes] as a complete [target] so readers never observe a partial file.
 *
 * Writes an owner-only temp in the same directory, fsyncs it, then links it into
 * place with [Files.createLink] (no replace). Where hard links are unsupported
 * ([UnsupportedOperationException] or a plain [FileSystemException] such as on
 * FAT/exFAT/network shares), falls back to [StandardCopyOption.ATOMIC_MOVE] only
 * when [target] is absent (caller must hold [AesKeyFileLock]).
 *
 * Never falls back on [AccessDeniedException] or [FileAlreadyExistsException]
 * (decided by exception type, never message text).
 * [UnsupportedOperationException] caches "hard links unsupported" per directory;
 * a plain [FileSystemException] (e.g. ENOSPC/EIO) falls back for that call only.
 *
 * [afterTempReady] is a test-only hook invoked after the temp is durable and
 * before the publish step (stall a writer while another loader waits on the lock).
 * [createHardLink] is injectable for tests (`link` = new path, `existing` = tmp).
 *
 * @throws FileAlreadyExistsException if [target] already exists
 * @throws AccessDeniedException if linking is denied (no move fallback)
 */
internal fun publishOwnerOnlyBytesExclusive(
    target: Path,
    bytes: ByteArray,
    onTempCreated: ((Path) -> Unit)? = null,
    afterTempReady: (() -> Unit)? = null,
    createHardLink: (link: Path, existing: Path) -> Unit = { link, existing ->
        Files.createLink(link, existing)
    },
) {
    require(bytes.size == AES_256_KEY_BYTES) { "AES-256 key must be $AES_256_KEY_BYTES bytes" }
    Files.createDirectories(target.parent)
    if (Files.exists(target)) {
        throw FileAlreadyExistsException(target.toFile())
    }
    val tmp = createOwnerOnlyTempFile(
        directory = target.parent,
        prefix = ".${target.fileName}.",
        suffix = ".publish.tmp",
        onTempCreated = onTempCreated,
    )
    try {
        FileChannel.open(
            tmp,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(/* metaData = */ true)
        }
        afterTempReady?.invoke()
        val linked = tryPublishViaHardLink(
            directory = target.parent,
            target = target,
            tmp = tmp,
            createHardLink = createHardLink,
        )
        if (!linked) {
            publishByAtomicMoveNoReplace(tmp, target)
            fsyncDirectoryBestEffort(target.parent)
            return
        }
        // Drop the temp name; [target] keeps the inode.
        Files.deleteIfExists(tmp)
        setOwnerOnlyPermissions(target)
        if (!supportsPosixPermissions()) {
            applyOwnerOnlyAcl(target)
        }
        fsyncDirectoryBestEffort(target.parent)
    } catch (e: AccessDeniedException) {
        Files.deleteIfExists(tmp)
        throw e
    } catch (e: FileAlreadyExistsException) {
        Files.deleteIfExists(tmp)
        throw e
    } catch (t: Throwable) {
        Files.deleteIfExists(tmp)
        Files.deleteIfExists(target)
        throw t
    }
}

/**
 * Attempt hard-link publish. Returns false when hard links are unsupported so
 * the caller can ATOMIC_MOVE. Rethrows [AccessDeniedException] /
 * [FileAlreadyExistsException] without falling back.
 */
private fun tryPublishViaHardLink(
    directory: Path,
    target: Path,
    tmp: Path,
    createHardLink: (link: Path, existing: Path) -> Unit,
): Boolean {
    val cacheKey = directoryHardLinkCacheKey(directory)
    if (hardLinkSupportedByDir[cacheKey] == false) {
        return false
    }
    return try {
        // link(existing=tmp, link=target) — target must not exist.
        createHardLink(target, tmp)
        hardLinkSupportedByDir.putIfAbsent(cacheKey, true)
        true
    } catch (_: UnsupportedOperationException) {
        // Provider/FS permanently lacks hard links — cache and skip next time.
        hardLinkSupportedByDir[cacheKey] = false
        false
    } catch (e: AccessDeniedException) {
        throw e
    } catch (e: FileAlreadyExistsException) {
        throw e
    } catch (_: FileSystemException) {
        // Transient or environment-specific (ENOSPC/EIO, some network shares).
        // Fall back for this call only — do not cache as "unsupported".
        false
    }
}

/**
 * ATOMIC_MOVE fallback when hard links are unavailable. Must run under
 * [AesKeyFileLock]; refuses to replace an existing [target].
 *
 * Note: Linux `rename(2)` can clobber without REPLACE_EXISTING — we check
 * existence first and rely on the lock so no concurrent creator races us.
 */
private fun publishByAtomicMoveNoReplace(tmp: Path, target: Path) {
    if (Files.exists(target)) {
        throw FileAlreadyExistsException(target.toFile())
    }
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.toFile())
        }
        Files.move(tmp, target)
    }
    setOwnerOnlyPermissions(target)
    if (!supportsPosixPermissions()) {
        applyOwnerOnlyAcl(target)
    }
}

private fun moveTempToTargetReplacing(tmp: Path, target: Path) {
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
}

private val quarantineRandom = SecureRandom()

internal fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    quarantineRandom.nextBytes(bytes)
    return bytes.joinToString("") { b -> "%02x".format(b) }
}

/**
 * Default quarantine sibling name: `[filename].bad-<millis>-<random hex>`.
 * Injected in tests to force a name collision.
 */
internal fun defaultBadSiblingName(path: Path): String =
    "${path.fileName}.bad-${System.currentTimeMillis()}-${randomHex(4)}"

/**
 * Atomically rename [path] to a unique `[filename].bad-<millis>-<hex>` sibling
 * without clobbering an existing `.bad` file. Retries with a new suffix on
 * [FileAlreadyExistsException].
 *
 * Reserves the destination with an owner-only [createOwnerOnlyFile] (CREATE_NEW)
 * before replacing it with [path]. That keeps existing `.bad` siblings intact —
 * Linux `ATOMIC_MOVE`/`rename(2)` can otherwise overwrite even without
 * [StandardCopyOption.REPLACE_EXISTING] — and seals the quarantine name
 * owner-only at creation.
 *
 * @return the quarantine path, or null if [path] disappeared (another process
 * already moved it — [java.nio.file.NoSuchFileException]).
 */
internal fun quarantineToBadSibling(
    path: Path,
    newBadName: () -> String = { defaultBadSiblingName(path) },
): Path? {
    var attempts = 0
    while (attempts < 32) {
        attempts++
        val bad = path.resolveSibling(newBadName())
        try {
            // Owner-only CREATE_NEW reservation — fails if this .bad name is taken.
            createOwnerOnlyFile(bad)
        } catch (_: FileAlreadyExistsException) {
            // Leave the existing .bad untouched; pick a new unique suffix.
            continue
        }
        try {
            // Abort if [path] vanished or a concurrent starter already wrote a
            // valid AES-256 key there — never quarantine a good key.
            if (!Files.exists(path) || Files.size(path) == AES_256_KEY_BYTES.toLong()) {
                Files.deleteIfExists(bad)
                return null
            }
            try {
                Files.move(
                    path,
                    bad,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(path, bad, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(bad)
            if (!supportsPosixPermissions()) {
                applyOwnerOnlyAcl(bad)
            }
            return bad
        } catch (_: java.nio.file.NoSuchFileException) {
            // Another process already quarantined [path] — drop our empty reservation.
            Files.deleteIfExists(bad)
            return null
        } catch (t: Throwable) {
            Files.deleteIfExists(bad)
            throw t
        }
    }
    throw FileAlreadyExistsException(
        path.toFile(),
        null,
        "exhausted unique quarantine names for ${path.fileName}",
    )
}

/**
 * Write [snapshot] to [target] via a sibling temp file + [StandardCopyOption.ATOMIC_MOVE]
 * (falls back to non-atomic replace when the store does not support atomic move).
 */
internal fun atomicWriteProperties(
    target: Path,
    snapshot: Map<String, String>,
    comment: String,
    onTempCreated: ((Path) -> Unit)? = null,
) {
    Files.createDirectories(target.parent)
    val tmp = createOwnerOnlyTempFile(
        directory = target.parent,
        prefix = ".${target.fileName}.",
        suffix = ".tmp",
        onTempCreated = onTempCreated,
    )
    try {
        val props = Properties()
        snapshot.forEach { (k, v) -> props.setProperty(k, v) }
        Files.newOutputStream(tmp).use { props.store(it, comment) }
        moveTempToTargetReplacing(tmp, target)
        setOwnerOnlyPermissions(target)
        if (!supportsPosixPermissions()) {
            applyOwnerOnlyAcl(target)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}
