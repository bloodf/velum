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

    @Test
    fun demuxAtForEach_emits_trailing_continued_packet() {
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, 1, 0, 0,
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
            0, 0, 0,
        )
        // Continued segment (255) then terminator — and a final continued-only page flushed at EOS.
        val audioA = ByteArray(255) { 0x11 }
        val audioB = byteArrayOf(0x22, 0x33)
        val pageHead = buildOggPage(head)
        val pageCont = buildOggPageMulti(listOf(255 to audioA, audioB.size to audioB))
        // Continued segment (255 bytes, no terminator) left open at EOS → must flush.
        val trailingOnly = buildOggPageMulti(listOf(255 to ByteArray(255) { 0x44 }))
        var packets = 0
        var lastSize = 0
        OggOpusDemuxer.demuxForEach(
            pageHead + pageCont + trailingOnly,
            onPacket = {
                packets++
                lastSize = it.size
            },
        )
        assertEquals(2, packets, "one finished packet + one EOS continued flush")
        assertEquals(255, lastSize)
    }

    @Test
    fun demuxForEach_invokes_onHeader_without_retaining_packets() {
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, 1, 0, 0,
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
            0, 0, 0,
        )
        var headerRate = 0
        var headerCh = 0
        var packets = 0
        val meta = OggOpusDemuxer.demuxForEach(
            buildOggPage(head),
            onHeader = { rate, ch ->
                headerRate = rate
                headerCh = ch
            },
            onPacket = { packets++ },
        )
        assertEquals(48_000, meta.sampleRateHz)
        assertEquals(48_000, headerRate)
        assertEquals(1, headerCh)
        assertEquals(0, packets)
    }

    private fun buildOggPage(packet: ByteArray): ByteArray {
        require(packet.size < 255)
        return buildOggPageMulti(listOf(packet.size to packet))
    }

    private fun buildOggPageMulti(segments: List<Pair<Int, ByteArray>>): ByteArray {
        val bodySize = segments.sumOf { it.second.size }
        val header = ByteArray(27 + segments.size + bodySize)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0
        header[5] = 2
        header[26] = segments.size.toByte()
        var o = 27
        for ((segSize, _) in segments) {
            header[o++] = segSize.toByte()
        }
        for ((_, data) in segments) {
            data.copyInto(header, o)
            o += data.size
        }
        return header
    }
}
