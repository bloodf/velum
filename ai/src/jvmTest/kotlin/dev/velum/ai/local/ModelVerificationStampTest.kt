package dev.velum.ai.local

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Start-up readiness must not re-hash multi-GB single-file models that have not changed since they were verified. */
class ModelVerificationStampTest {
    private val root = Files.createTempDirectory("velum-verify-stamp").toFile()
    private val weights = File(root, "model.litertlm")
    private val marker = File(root, "model.litertlm$VERIFIED_SUFFIX")

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun check(expected: String) = verifiedSingleFile(ModelStoreDir(weights.path), ModelStoreDir(marker.path), expected)

    @Test fun first_verification_hashes_and_records_size_mtime_and_checksum() {
        weights.writeBytes(ByteArray(4096) { it.toByte() })
        val expected = sha(weights.readBytes())
        assertTrue(check(expected.uppercase()))
        assertEquals("${weights.length()}:${weights.lastModified()}:$expected", marker.readText())
    }

    @Test fun an_unchanged_file_is_trusted_from_the_stamp_without_hashing() {
        weights.writeBytes(ByteArray(4096) { it.toByte() })
        val expected = sha(weights.readBytes())
        assertTrue(check(expected))
        // Same size and mtime, different bytes: only a re-hash could notice, so a true result proves no re-hash.
        val mtime = weights.lastModified()
        weights.writeBytes(ByteArray(4096) { 7 })
        assertTrue(weights.setLastModified(mtime))
        assertTrue(check(expected))
    }

    @Test fun a_changed_file_is_hashed_again_and_a_mismatch_drops_the_stamp() {
        weights.writeBytes(ByteArray(4096) { it.toByte() })
        val expected = sha(weights.readBytes())
        assertTrue(check(expected))
        weights.writeBytes(ByteArray(4095) { 1 }) // truncated/replaced: size differs
        assertFalse(check(expected))
        assertFalse(marker.exists())
    }

    @Test fun a_wrong_checksum_never_writes_a_stamp() {
        weights.writeBytes(ByteArray(16) { 3 })
        assertFalse(check("00".repeat(32)))
        assertFalse(marker.exists())
    }

    @Test fun a_stamp_for_another_checksum_is_not_trusted() {
        weights.writeBytes(ByteArray(16) { 3 })
        marker.writeText("${weights.length()}:${weights.lastModified()}:${"ab".repeat(32)}")
        assertTrue(check(sha(weights.readBytes())))
        assertEquals("${weights.length()}:${weights.lastModified()}:${sha(weights.readBytes())}", marker.readText())
    }
}
