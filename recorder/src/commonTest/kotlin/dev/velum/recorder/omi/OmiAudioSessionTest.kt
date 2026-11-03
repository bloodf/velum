package dev.velum.recorder.omi

import dev.velum.recorder.RecorderAudioCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Synthetic notifications in the documented framing: 2-byte LE packet number, 1-byte fragment index, payload. */
class OmiAudioSessionTest {
    private fun notification(packet: Int, index: Int, vararg payload: Int) = byteArrayOf(
        (packet and 0xff).toByte(), ((packet shr 8) and 0xff).toByte(), index.toByte(),
    ) + ByteArray(payload.size) { payload[it].toByte() }

    @Test
    fun reassemblesFragmentsIntoFramesAndFlushesTheTail() {
        val s = OmiAudioSession(OmiCodec.Opus16)
        assertNull(s.accept(notification(1, 0, 1, 2)))
        assertNull(s.accept(notification(1, 1, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), s.accept(notification(2, 0, 9)))
        assertContentEquals(byteArrayOf(9), s.finish())
        assertNull(s.finish())
    }

    @Test
    fun shortNotificationsAreIgnored() {
        val s = OmiAudioSession(OmiCodec.Pcm16)
        assertNull(s.accept(byteArrayOf(1, 0)))
        assertNull(s.finish())
    }

    @Test
    fun lostPacketShowsInStats() {
        val s = OmiAudioSession(OmiCodec.Opus16)
        s.accept(notification(1, 0, 1))
        s.accept(notification(3, 0, 3)) // packet 2 never arrived
        val (received, expected) = s.stats()
        assertEquals(2L, received)
        assertEquals(3L, expected)
    }

    @Test
    fun batteryOnlyAcceptsOneByteZeroToHundred() {
        assertEquals(87, OmiBattery.parse(byteArrayOf(87)))
        assertEquals(0, OmiBattery.parse(byteArrayOf(0)))
        assertEquals(100, OmiBattery.parse(byteArrayOf(100, 55)))
        assertNull(OmiBattery.parse(byteArrayOf(101)))
        assertNull(OmiBattery.parse(byteArrayOf(0xFF.toByte())))
        assertNull(OmiBattery.parse(byteArrayOf()))
    }

    @Test
    fun codecsMapToRecorderCodecs() {
        assertEquals(RecorderAudioCodec.RawOpus, OmiCodec.Opus16.toRecorderCodec())
        assertEquals(RecorderAudioCodec.Pcm16, OmiCodec.Pcm8.toRecorderCodec())
        assertEquals(RecorderAudioCodec.MuLaw, OmiCodec.MuLaw8.toRecorderCodec())
    }
}
