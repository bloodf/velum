package dev.velum.ai.audio

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
}
