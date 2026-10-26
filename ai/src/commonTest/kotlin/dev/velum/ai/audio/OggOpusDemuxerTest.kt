package dev.velum.ai.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OggOpusDemuxerTest {
    @Test
    fun rejects_non_ogg() {
        assertFailsWith<IllegalArgumentException> {
            OggOpusDemuxer.demux(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
    }

    @Test
    fun demuxes_minimal_opus_head_page() {
        // Minimal synthetic Ogg page carrying OpusHead (no audio packets).
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, // version
            1, // channels
            0, 0, // pre-skip
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00, // 48000 LE
            0, 0, // gain
            0, // mapping family
        )
        val page = buildOggPage(head)
        val stream = OggOpusDemuxer.demux(page)
        assertEquals(48_000, stream.sampleRateHz)
        assertEquals(1, stream.channels)
        assertTrue(stream.packets.isEmpty())
    }

    private fun buildOggPage(packet: ByteArray): ByteArray {
        require(packet.size < 255)
        val header = ByteArray(27 + 1 + packet.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0 // version
        header[5] = 2 // BOS
        // granule / serial / seq / crc left zero
        header[26] = 1 // one segment
        header[27] = packet.size.toByte()
        packet.copyInto(header, 28)
        return header
    }
}
