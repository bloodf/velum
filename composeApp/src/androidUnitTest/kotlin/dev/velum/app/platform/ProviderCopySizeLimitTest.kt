package dev.velum.app.platform

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class ProviderCopySizeLimitTest {
    private fun target() = File.createTempFile("provider-cap", ".wav").also { it.delete() }

    @Test
    fun productionCapIsOneGiB() {
        assertEquals(1_073_741_824L, MAX_PROVIDER_COPY_BYTES)
    }

    @Test
    fun inclusiveCapPublishesAllBytes() {
        val out = target()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            assertTrue(copyToCache({ ByteArrayInputStream(bytes) }, out, maxBytes = 3))
            assertContentEquals(bytes, out.readBytes())
        } finally {
            out.delete()
        }
    }

    @Test
    fun unknownAndLyingLengthCannotPublishCapPlusOne() {
        for (length in listOf(null, 1L, 3L)) {
            val out = target()
            assertFalse(copyToCache({ ByteArrayInputStream(ByteArray(4)) }, out, knownLength = length, maxBytes = 3))
            assertFalse(out.exists())
        }
    }

    @Test
    fun knownOversizeIsRejectedBeforeOpen() {
        val out = target()
        assertFalse(copyToCache({ error("must not open") }, out, knownLength = 4, maxBytes = 3))
        assertFalse(out.exists())
    }

    @Test
    fun existingDestinationIsNeitherTruncatedNorDeleted() {
        val out = target()
        try {
            out.writeBytes(byteArrayOf(9))
            assertFalse(copyToCache({ ByteArrayInputStream(byteArrayOf(1)) }, out))
            assertContentEquals(byteArrayOf(9), out.readBytes())
        } finally {
            out.delete()
        }
    }

    @Test
    fun partialCancellationPropagatesAndRemovesOwnedFile() {
        val out = target()
        var closed = false
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = error("bulk read expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw CancellationException("cancelled")
                bytes[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        }
        assertFailsWith<CancellationException> { copyToCache({ input }, out) }
        assertTrue(closed)
        assertFalse(out.exists())
    }
}
