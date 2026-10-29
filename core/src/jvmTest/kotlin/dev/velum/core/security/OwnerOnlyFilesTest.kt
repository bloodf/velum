package dev.velum.core.security

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class OwnerOnlyFilesTest {

    @Test
    fun acl_owner_only_when_acl_view_exists() {
        assumeTrue(
            "ACL file attribute view required",
            supportsAclFileAttributes(),
        )
        val dir = kotlin.io.path.createTempDirectory("velum-acl-test")
        val path = dir.resolve("owner-only.bin")
        try {
            createOwnerOnlyAclFile(path)
            assertTrue(Files.exists(path))
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            assertTrue(view != null)
            val acl = view!!.acl
            assertEquals(1, acl.size)
            val entry = acl.single()
            assertEquals(AclEntryType.ALLOW, entry.type())
            assertEquals(view.owner, entry.principal())
            val perms = entry.permissions()
            assertTrue(perms.contains(AclEntryPermission.READ_DATA))
            assertTrue(perms.contains(AclEntryPermission.WRITE_DATA))
            assertTrue(perms.contains(AclEntryPermission.READ_ATTRIBUTES))
            assertTrue(perms.contains(AclEntryPermission.WRITE_ATTRIBUTES))
            assertTrue(perms.contains(AclEntryPermission.READ_ACL))
            assertTrue(perms.contains(AclEntryPermission.WRITE_ACL))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun temp_file_has_owner_only_posix_mode_from_creation() {
        assumeTrue("POSIX permissions required", supportsPosixPermissions())
        val dir = kotlin.io.path.createTempDirectory("velum-temp-perms")
        try {
            val tmp = createOwnerOnlyTempFile(dir, ".probe.", ".tmp")
            val expected = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            assertEquals(expected, Files.getPosixFilePermissions(tmp))
            Files.deleteIfExists(tmp)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun publish_falls_back_to_move_on_plain_filesystem_exception() {
        val dir = kotlin.io.path.createTempDirectory("velum-publish-fs-ex")
        val target = dir.resolve("secure.aes256")
        val bytes = ByteArray(AES_256_KEY_BYTES) { i -> (i + 7).toByte() }
        try {
            publishOwnerOnlyBytesExclusive(
                target = target,
                bytes = bytes,
                createHardLink = { _, _ ->
                    throw FileSystemException(target.toString(), null, "hard links unsupported")
                },
            )
            assertTrue(Files.exists(target))
            assertTrue(bytes.contentEquals(Files.readAllBytes(target)))
            assumeTrue("POSIX permissions required", supportsPosixPermissions())
            assertEquals(
                OWNER_READ_WRITE,
                Files.getPosixFilePermissions(target),
                "move-fallback key file must be owner-only rw-------",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun filesystem_exception_fallback_is_not_cached_uoe_is() {
        val dir = kotlin.io.path.createTempDirectory("velum-publish-cache")
        try {
            var fsAttempts = 0
            val first = dir.resolve("a.aes256")
            val second = dir.resolve("b.aes256")
            val bytesA = ByteArray(AES_256_KEY_BYTES) { 1 }
            val bytesB = ByteArray(AES_256_KEY_BYTES) { 2 }
            publishOwnerOnlyBytesExclusive(
                target = first,
                bytes = bytesA,
                createHardLink = { _, _ ->
                    fsAttempts++
                    throw FileSystemException(first.toString(), null, "ENOSPC")
                },
            )
            publishOwnerOnlyBytesExclusive(
                target = second,
                bytes = bytesB,
                createHardLink = { _, _ ->
                    fsAttempts++
                    throw FileSystemException(second.toString(), null, "EIO")
                },
            )
            // Generic FileSystemException must not cache "unsupported".
            assertEquals(2, fsAttempts)
            assertTrue(bytesA.contentEquals(Files.readAllBytes(first)))
            assertTrue(bytesB.contentEquals(Files.readAllBytes(second)))

            val uoeDir = kotlin.io.path.createTempDirectory("velum-publish-uoe")
            try {
                var uoeAttempts = 0
                val u1 = uoeDir.resolve("c.aes256")
                val u2 = uoeDir.resolve("d.aes256")
                val bytesC = ByteArray(AES_256_KEY_BYTES) { 3 }
                val bytesD = ByteArray(AES_256_KEY_BYTES) { 4 }
                publishOwnerOnlyBytesExclusive(
                    target = u1,
                    bytes = bytesC,
                    createHardLink = { _, _ ->
                        uoeAttempts++
                        throw UnsupportedOperationException("no hard links")
                    },
                )
                publishOwnerOnlyBytesExclusive(
                    target = u2,
                    bytes = bytesD,
                    createHardLink = { _, _ ->
                        uoeAttempts++
                        throw UnsupportedOperationException("no hard links")
                    },
                )
                // UnsupportedOperationException is cached per directory.
                assertEquals(1, uoeAttempts)
                assertTrue(bytesC.contentEquals(Files.readAllBytes(u1)))
                assertTrue(bytesD.contentEquals(Files.readAllBytes(u2)))
            } finally {
                uoeDir.toFile().deleteRecursively()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
