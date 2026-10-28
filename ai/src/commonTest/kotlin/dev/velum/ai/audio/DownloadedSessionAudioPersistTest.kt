package dev.velum.ai.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedSessionAudioPersistTest {
    @Test
    fun prepare_ogg_keepsContainerAndExtension() {
        val ogg = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
            0, 0, 0, 0,
        )
        val prepared = DownloadedSessionAudioPersist.prepare(ogg)
        assertEquals("ogg", prepared.extension)
        assertTrue(prepared.bytes.contentEquals(ogg))
    }

    @Test
    fun prepare_wav_keepsContainerAndExtension() {
        val wav = PcmWav.encode(ByteArray(32) { 1 }, sampleRateHz = 16_000)
        val prepared = DownloadedSessionAudioPersist.prepare(wav)
        assertEquals("wav", prepared.extension)
        assertTrue(prepared.bytes.contentEquals(wav))
    }

    @Test
    fun prepare_rawPcm_wrapsAsWav_neverBin() {
        val pcm = ByteArray(64) { (it % 40).toByte() }
        val prepared = DownloadedSessionAudioPersist.prepare(
            pcm,
            sampleRateHz = 16_000,
            channels = 1,
        )
        assertEquals("wav", prepared.extension)
        assertFalse(prepared.extension.equals("bin", ignoreCase = true))
        assertTrue(PcmWav.looksLikeWav(prepared.bytes))
        assertEquals(44 + pcm.size, prepared.bytes.size)
        assertTrue(prepared.bytes.copyOfRange(44, prepared.bytes.size).contentEquals(pcm))
    }

    @Test
    fun prepare_rawOpusLikeBytes_stillSelfDescribingWav() {
        // Older Note RawOpusSegments / ADPCM historically became `.bin` and were fed to RP as
        // "raw PCM". Persist must still emit a WAV so streaming decode accepts the path.
        val opaque = ByteArray(128) { (0xA0 + (it % 16)).toByte() }
        val prepared = DownloadedSessionAudioPersist.prepare(opaque)
        assertEquals("wav", prepared.extension)
        assertTrue(PcmWav.looksLikeWav(prepared.bytes))
    }
}
