package dev.velum.ble

import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.Bytes
import dev.velum.protocol.PlaudFile
import dev.velum.protocol.PlaudFileCrypto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/** Synthetic PLAUD.AI files only: stream decrypt must match in-memory decrypt with bounded pieces. */
class PlaudFileStreamDecryptTest {
    private val decryptor = JcaFileDecryptor()
    private val keys = rsaKeyPairToPem()
    private val contentKey = ByteArray(32) { (it + 1).toByte() } // encryptFile defaults
    private val nonce = ByteArray(12) { (it + 3).toByte() }

    private class Recorded(val out: ByteArray, val maxRead: Int, val maxWrite: Int, val reads: Int)

    private fun streamDecrypt(file: ByteArray, chunk: Int): Recorded {
        val out = java.io.ByteArrayOutputStream()
        var maxRead = 0
        var maxWrite = 0
        var reads = 0
        val total = PlaudFileCrypto.decryptStream(
            size = file.size.toLong(),
            readAt = { p, l ->
                reads++
                maxRead = maxOf(maxRead, l)
                file.copyOfRange(p.toInt(), minOf(file.size, p.toInt() + l))
            },
            privateKeyPem = keys.second,
            decryptor = decryptor,
            chunkBytes = chunk,
        ) {
            maxWrite = maxOf(maxWrite, it.size)
            out.write(it)
        }
        assertEquals(out.size().toLong(), total)
        return Recorded(out.toByteArray(), maxRead, maxWrite, reads)
    }

    private fun segmentedFile(plain: ByteArray, segment: Int): ByteArray {
        val file = decryptor.encryptFile(keys.first, plain)
        Bytes.u32Le(segment.toLong()).copyInto(file, PlaudFile.OFF_SEGMENT_SIZE)
        decryptor.chacha20Decrypt(contentKey, nonce, 0L, segment.toLong(), plain)
            .copyInto(file, PlaudFile.HEADER_SIZE)
        return file
    }

    @Test
    fun unsegmented_stream_matches_in_memory_decrypt_with_bounded_pieces() {
        val plain = Random(86).nextBytes(1_048_576 + 37)
        val file = decryptor.encryptFile(keys.first, plain)
        val r = streamDecrypt(file, chunk = 4096)
        assertContentEquals(PlaudFileCrypto.decrypt(file, keys.second, decryptor), r.out)
        assertContentEquals(plain, r.out)
        assertTrue(r.maxWrite <= 4096, "write piece ${r.maxWrite}")
        assertTrue(r.maxRead <= PlaudFile.HEADER_SIZE.coerceAtLeast(4096), "read piece ${r.maxRead}")
    }

    @Test
    fun segmented_stream_matches_in_memory_for_small_and_large_segments() {
        val plain = Random(95).nextBytes(300_001)
        for (segment in listOf(100, 4096, 10_000)) {
            val file = segmentedFile(plain, segment)
            assertContentEquals(plain, PlaudFileCrypto.decrypt(file, keys.second, decryptor), "ref seg=$segment")
            val r = streamDecrypt(file, chunk = 4096)
            assertContentEquals(plain, r.out, "stream seg=$segment")
            assertTrue(r.maxWrite <= 4096, "seg=$segment write piece ${r.maxWrite}")
        }
    }

    @Test
    fun truncated_and_corrupt_files_fail_explicitly() {
        val file = decryptor.encryptFile(keys.first, Random(1).nextBytes(10_000))
        val truncatedRead = assertFailsWith<IllegalArgumentException> {
            PlaudFileCrypto.decryptStream(
                size = file.size.toLong(),
                readAt = { p, l -> file.copyOfRange(p.toInt(), minOf(file.size - 100, p.toInt() + l)) },
                privateKeyPem = keys.second,
                decryptor = decryptor,
                chunkBytes = 4096,
            ) {}
        }
        assertTrue(truncatedRead.message!!.contains("Truncated"))
        val badMagic = file.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { streamDecrypt(badMagic, 4096) }
        assertFailsWith<IllegalArgumentException> { streamDecrypt(file.copyOf(PlaudFile.HEADER_SIZE - 1), 4096) }
        assertFailsWith<IllegalArgumentException> { streamDecrypt(file, chunk = 100) } // not block aligned
    }

    @Test
    fun cancellation_in_sink_stops_reading_and_propagates() {
        val file = decryptor.encryptFile(keys.first, ByteArray(64 * 1024))
        var reads = 0
        var writes = 0
        assertFailsWith<CancellationException> {
            PlaudFileCrypto.decryptStream(
                size = file.size.toLong(),
                readAt = { p, l -> reads++; file.copyOfRange(p.toInt(), p.toInt() + l) },
                privateKeyPem = keys.second,
                decryptor = decryptor,
                chunkBytes = 4096,
            ) {
                if (++writes == 3) throw CancellationException("user cancelled")
            }
        }
        assertEquals(3, writes)
        assertEquals(1 + 3, reads, "header + 3 body pieces, nothing read after cancel")
    }

    /**
     * Zero plaintext means ciphertext == keystream, so the source is generated per read and
     * never exists whole. Peak heap must not track stream length (16 MiB vs 256 MiB).
     */
    @Test
    fun heap_does_not_grow_with_stream_length() {
        val header = decryptor.encryptFile(keys.first, ByteArray(0)) // counter=1, unsegmented
        fun run(bodyBytes: Long): Long {
            val rt = Runtime.getRuntime()
            System.gc()
            val baseline = rt.totalMemory() - rt.freeMemory()
            var peak = 0L
            var pieces = 0
            var maxPiece = 0
            val total = PlaudFileCrypto.decryptStream(
                size = PlaudFile.HEADER_SIZE + bodyBytes,
                readAt = { p, l ->
                    if (p == 0L) {
                        header.copyOf(l)
                    } else {
                        val off = p - PlaudFile.HEADER_SIZE
                        decryptor.chacha20Decrypt(contentKey, nonce, 1L + off / 64, 0L, ByteArray(l))
                    }
                },
                privateKeyPem = keys.second,
                decryptor = decryptor,
            ) { piece ->
                maxPiece = maxOf(maxPiece, piece.size)
                for (b in piece) if (b != 0.toByte()) error("bad plaintext at piece $pieces")
                if (pieces++ % 128 == 0) {
                    System.gc()
                    peak = maxOf(peak, rt.totalMemory() - rt.freeMemory() - baseline)
                }
            }
            assertEquals(bodyBytes, total)
            assertTrue(maxPiece <= PlaudFileCrypto.STREAM_CHUNK_BYTES, "piece $maxPiece")
            return peak
        }
        val small = run(16L shl 20)
        val large = run(256L shl 20)
        println("stream-decrypt heap delta: 16MiB=${small shr 10}KiB 256MiB=${large shr 10}KiB")
        assertTrue(large < 32L shl 20, "256 MiB stream retained ${large shr 20} MiB")
        assertTrue(large - small < 16L shl 20, "heap grew with stream length: $small -> $large")
    }
}
