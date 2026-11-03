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
    fun codec_fromFriendSwiftValues() {
        assertEquals(OmiCodec.Opus16, OmiCodec.fromByte(20))
        assertEquals(OmiCodec.Pcm16, OmiCodec.fromByte(0))
        assertEquals(OmiCodec.MuLaw8, OmiCodec.fromByte(11))
    }
}
