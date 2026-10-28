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
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
