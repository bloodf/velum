package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppleOpusDecoderTest {
    // RFC 6716 comfort-noise/silence packet, 20 ms, used by existing Opus implementations.
    private val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    private fun page(packet: ByteArray): ByteArray = ByteArray(28 + packet.size).also {
        "OggS".encodeToByteArray().copyInto(it)
        it[26] = 1
        it[27] = packet.size.toByte()
        packet.copyInto(it, 28)
    }

    private fun ogg(channels: Int = 1, preSkip: Int = 0, packets: Int = 10): ByteArray {
        val head = ByteArray(19)
        "OpusHead".encodeToByteArray().copyInto(head)
        head[8] = 1
        head[9] = channels.toByte()
        head[10] = preSkip.toByte()
        head[11] = (preSkip shr 8).toByte()
        // Deliberately leave the informational input sample rate at zero.
        return page(head) + List(packets) { page(silence) }.fold(ByteArray(0)) { a, b -> a + b }
    }

    @Test
    fun native_packets_produce_fixed_rate_mono_and_reset_between_recordings() {
        val decoder = createOpusDecoder()
        for (channels in 1..2) {
            val first = decoder.decodeToPcm(ogg(channels))
            assertEquals(16_000, first.sampleRateHz)
            assertEquals(1, first.channels)
            assertEquals(3200, first.samples.size)
            assertTrue(first.samples.all { it.toInt() in -8..8 })
            assertContentEquals(first.samples, decoder.decodeToPcm(ogg(channels)).samples)
        }
    }

    @Test
    fun pre_skip_spans_packets_and_truncation_is_preserved() {
        val decoder = createOpusDecoder()
        val full = decoder.decodeToPcm(ogg())
        val skipped = decoder.decodeToPcm(ogg(preSkip = 3000))
        assertContentEquals(full.samples.copyOfRange(1000, full.samples.size), skipped.samples)
        val truncated = decoder.decodeToPcm(ogg() + byteArrayOf(1))
        assertTrue(truncated.truncated)
        assertContentEquals(full.samples, truncated.samples)
        assertEquals(0, decoder.decodeToPcm(ogg(preSkip = 65535)).samples.size)
    }

    @Test
    fun invalid_input_does_not_poison_next_recording() {
        val decoder = createOpusDecoder()
        assertFailsWith<IllegalArgumentException> { decoder.decodeToPcm(ByteArray(3)) }
        assertEquals(3200, decoder.decodeToPcm(ogg()).samples.size)
    }

    private fun withDirectory(block: (ModelStoreDir) -> Unit) {
        val directory = ModelStoreDir(NSTemporaryDirectory()).resolve("opus-test-${NSUUID().UUIDString}")
        directory.createDirectories()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun spill_writes_many_chunks_with_exact_byte_count_and_no_temporary_files() = withDirectory { directory ->
        val destination = directory.resolve("decoded.pcm")
        val source = directory.resolve("source.ogg")
        source.writeBytes(ogg(packets = 1000))
        val decoder = createOpusDecoder()
        val meta = decoder.decodePathToPcmFile(source.absolutePath, destination)
        assertEquals(640_000L, meta.sizeBytes)
        assertEquals(meta.sizeBytes, destination.length())
        assertEquals(16_000, meta.sampleRateHz)
        assertEquals(1, meta.channels)
        assertContentEquals(decoder.decodeToPcm(ogg(packets = 1000)).toByteArray(), destination.readBytes())
        assertEquals(setOf("source.ogg", "decoded.pcm"), directory.listChildren().map { it.name }.toSet())
    }

    @Test
    fun decode_failure_removes_owned_spill_and_preserves_existing_destination() = withDirectory { directory ->
        val destination = directory.resolve("decoded.pcm")
        val original = byteArrayOf(42, 43)
        destination.writeBytes(original)
        // The failure occurs after complete audio pages have already emitted PCM.
        val malformed = ogg() + ByteArray(27)
        assertFailsWith<IllegalArgumentException> { createOpusDecoder().decodeToPcmFile(malformed, destination) }
        assertContentEquals(original, destination.readBytes())
        assertEquals(listOf("decoded.pcm"), directory.listChildren().map { it.name })
    }

    @Test
    fun cancellation_removes_owned_spill_and_leaves_source_and_destination_intact() = withDirectory { directory ->
        val source = directory.resolve("source.ogg")
        val destination = directory.resolve("decoded.pcm")
        val original = byteArrayOf(42)
        val encoded = ogg(packets = 100)
        source.writeBytes(encoded)
        destination.writeBytes(original)
        val cancellation = IllegalStateException("test cancellation")
        var checks = 0
        val caught = assertFailsWith<IllegalStateException> {
            createOpusDecoder().decodePathToPcmFile(source.absolutePath, destination) {
                if (++checks == 40) throw cancellation
            }
        }
        assertTrue(caught === cancellation)
        assertContentEquals(original, destination.readBytes())
        assertContentEquals(encoded, source.readBytes())
        assertEquals(setOf("source.ogg", "decoded.pcm"), directory.listChildren().map { it.name }.toSet())
    }

    @Test
    fun publication_failure_closes_and_removes_spill_without_removing_directory() = withDirectory { directory ->
        val destination = directory.resolve("existing-directory")
        destination.createDirectories()
        destination.resolve("keep").writeBytes(byteArrayOf(42))
        assertFailsWith<IllegalStateException> { createOpusDecoder().decodeToPcmFile(ogg(), destination) }
        assertContentEquals(byteArrayOf(42), destination.resolve("keep").readBytes())
        assertEquals(listOf("existing-directory"), directory.listChildren().map { it.name })
    }

}
