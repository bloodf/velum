package dev.velum.core.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuarantineBadSiblingTest {

    @Test
    fun preexisting_bad_sibling_survives_quarantine() {
        val dir = kotlin.io.path.createTempDirectory("velum-quarantine-collision")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val originalBadBytes = byteArrayOf(0x09, 0x08, 0x07)
            val collidingName = "secure.aes256.bad-fixed"
            val existingBad = dir.resolve(collidingName)
            Files.write(existingBad, originalBadBytes)

            val sourceBytes = byteArrayOf(0x01, 0x02, 0x03)
            Files.write(keyFile, sourceBytes)

            var attempts = 0
            val quarantined = quarantineToBadSibling(keyFile) {
                attempts++
                if (attempts == 1) collidingName else "secure.aes256.bad-retry-$attempts"
            }
            assertNotNull(quarantined)
            assertFalse(Files.exists(keyFile))

            // Pre-existing .bad must be untouched.
            assertTrue(Files.exists(existingBad))
            assertTrue(originalBadBytes.contentEquals(Files.readAllBytes(existingBad)))

            // Quarantined source landed under a different unique name.
            assertTrue(Files.exists(quarantined))
            assertTrue(sourceBytes.contentEquals(Files.readAllBytes(quarantined)))
            assertTrue(attempts >= 2, "expected retry after FileAlreadyExistsException")
            assertEquals("secure.aes256.bad-retry-2", quarantined.fileName.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unique_default_names_do_not_clobber() {
        val dir = kotlin.io.path.createTempDirectory("velum-quarantine-unique")
        try {
            val first = dir.resolve("secure.aes256")
            Files.write(first, byteArrayOf(0x01))
            val q1 = assertNotNull(quarantineToBadSibling(first))

            val second = dir.resolve("secure.aes256")
            Files.write(second, byteArrayOf(0x02))
            val q2 = assertNotNull(quarantineToBadSibling(second))

            assertTrue(Files.exists(q1))
            assertTrue(Files.exists(q2))
            assertFalse(q1 == q2)
            assertTrue(byteArrayOf(0x01).contentEquals(Files.readAllBytes(q1)))
            assertTrue(byteArrayOf(0x02).contentEquals(Files.readAllBytes(q2)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
