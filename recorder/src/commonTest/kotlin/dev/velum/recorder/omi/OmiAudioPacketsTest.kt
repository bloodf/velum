package dev.velum.recorder.omi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmiAudioPacketsTest {

    @Test
    fun parseFragment_readsLeHeader() {
        val bytes = byteArrayOf(
            0x02, 0x01, // packet 0x0102
            0x00, // index 0
            0xAA.toByte(), 0xBB.toByte(),
        )
        val frag = assertNotNull(OmiAudioPackets.parseFragment(bytes))
        assertEquals(0x0102, frag.packetNumber)
        assertEquals(0, frag.fragmentIndex)
        assertTrue(frag.payload.contentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
    }

    @Test
    fun reassembler_flushesOnNextPacket() {
        val reassembler = OmiPacketReassembler()
        val first = OmiAudioPackets.parseFragment(
            byteArrayOf(0x01, 0x00, 0x00, 0x11, 0x22),
        )!!
        assertNull(reassembler.push(first))
        val cont = OmiAudioPackets.parseFragment(
            byteArrayOf(0x01, 0x00, 0x01, 0x33),
        )!!
        assertNull(reassembler.push(cont))
        val next = OmiAudioPackets.parseFragment(
            byteArrayOf(0x02, 0x00, 0x00, 0x44),
        )!!
        val flushed = assertNotNull(reassembler.push(next))
        assertTrue(flushed.contentEquals(byteArrayOf(0x11, 0x22, 0x33)))
        val tail = assertNotNull(reassembler.flush())
        assertTrue(tail.contentEquals(byteArrayOf(0x44)))
    }

    @Test
    fun codec_fromFriendSwiftValues() {
        assertEquals(OmiCodec.Opus16, OmiCodec.fromByte(20))
        assertEquals(OmiCodec.Pcm16, OmiCodec.fromByte(0))
        assertEquals(OmiCodec.MuLaw8, OmiCodec.fromByte(11))
    }

    @Test
    fun packetCounter_countsDistinctIndices() {
        val c = BleAudioPacketCounter()
        assertTrue(c.record(byteArrayOf(0x00, 0x00, 0x00, 0x01)))
        assertTrue(c.record(byteArrayOf(0x01, 0x00, 0x00, 0x02)))
        val (received, expected) = c.snapshot()
        assertEquals(2L, received)
        assertEquals(2L, expected)
    }
}
