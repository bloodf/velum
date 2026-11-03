package dev.velum.recorder.omi

import dev.velum.recorder.RecorderAudioCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Synthetic notifications in the upstream framing (BasedHardware/omi wav_bytes.dart): a 16-bit LE packet index that
 * advances by one PER NOTIFICATION, a fragment id (0 starts a frame, then counts up) and the payload.
 */
class OmiAudioSessionTest {
    private fun n(packet: Int, fragment: Int, vararg payload: Int) = byteArrayOf(
        (packet and 0xff).toByte(), ((packet shr 8) and 0xff).toByte(), fragment.toByte(),
    ) + ByteArray(payload.size) { payload[it].toByte() }

    @Test
    fun reassemblesInSequenceFramesAndFlushesTheTail() {
        val s = OmiAudioSession(OmiCodec.Opus16)
        assertNull(s.accept(n(10, 0, 1, 2)))
        assertNull(s.accept(n(11, 1, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), s.accept(n(12, 0, 9)))
        assertContentEquals(byteArrayOf(9), s.finish())
        assertNull(s.finish())
        assertEquals(0, s.syncLosses)
    }

    @Test
    fun aGapInThePacketIndexDropsThePendingFrameAndWaitsForTheNextStart() {
        val s = OmiAudioSession(OmiCodec.Opus16)
        s.accept(n(1, 0, 1))
        assertNull(s.accept(n(3, 1, 2))) // packet 2 never arrived: frame 1 is dropped, not returned truncated
        assertEquals(1, s.syncLosses)
        assertNull(s.accept(n(4, 1, 5))) // mid-frame fragment while out of sync is ignored
        assertNull(s.accept(n(5, 0, 7)))
        assertContentEquals(byteArrayOf(7), s.finish())
    }

    @Test
    fun aSkippedFragmentIdDropsTheFrame() {
        val s = OmiAudioSession(OmiCodec.Opus16)
        s.accept(n(1, 0, 1))
        assertNull(s.accept(n(2, 2, 3))) // fragment 1 skipped although the packet index is consecutive
        assertEquals(1, s.syncLosses)
        assertNull(s.finish())
    }

    @Test
    fun theSixteenBitPacketIndexWrapsWithoutALoss() {
        val s = OmiAudioSession(OmiCodec.Pcm16)
        s.accept(n(0xFFFF, 0, 1))
        assertContentEquals(byteArrayOf(1), s.accept(n(0, 0, 2)))
        assertEquals(0, s.syncLosses)
    }

    @Test
    fun aPeerThatNeverStartsANewFrameCannotGrowMemoryWithoutBound() {
        val a = OmiFrameAssembler(maxFrameBytes = 100)
        a.accept(n(0, 0, 1))
        var index = 1
        repeat(1_000) { assertNull(a.accept(n(index++ and 0xFFFF, 1 + (it % 200), 7, 7, 7, 7))) }
        // The pending frame was dropped at the cap (and the stream is then out of sync, ignoring non-start fragments).
        assertEquals(1, a.syncLosses)
        assertNull(a.finish())
    }

    @Test
    fun shortNotificationsAreIgnored() {
        val s = OmiAudioSession(OmiCodec.Pcm16)
        assertNull(s.accept(byteArrayOf(1, 0)))
        assertNull(s.finish())
    }

    @Test
    fun batteryAcceptsExactlyOneByteZeroToHundred() {
        assertEquals(87, OmiBattery.parse(byteArrayOf(87)))
        assertEquals(0, OmiBattery.parse(byteArrayOf(0)))
        assertNull(OmiBattery.parse(byteArrayOf(100, 55)))
        assertNull(OmiBattery.parse(byteArrayOf(101)))
        assertNull(OmiBattery.parse(byteArrayOf(0xFF.toByte())))
        assertNull(OmiBattery.parse(byteArrayOf()))
    }

    @Test
    fun theSessionExposesTheCodecItWasOpenedWith() {
        val s = OmiAudioSession(OmiCodec.MuLaw8)
        assertEquals(RecorderAudioCodec.MuLaw, s.recorderCodec)
        assertEquals(8_000, s.sampleRateHz)
        assertEquals(RecorderAudioCodec.RawOpus, OmiCodec.Opus16.toRecorderCodec())
        assertEquals(RecorderAudioCodec.Pcm16, OmiCodec.Pcm8.toRecorderCodec())
    }
}
