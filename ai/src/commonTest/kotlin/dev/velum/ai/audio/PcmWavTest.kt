package dev.velum.ai.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmWavTest {
    @Test
    fun header_rejectsDataSizeThatOverflowsRiffInt() {
        // 36 + dataSize must fit in signed 32-bit RIFF chunk size.
        assertFailsWith<IllegalArgumentException> {
            PcmWav.header(dataSize = Int.MAX_VALUE - 35, sampleRateHz = 16_000)
        }
    }

    @Test
    fun encode_writesRiffWaveHeaderAndPayload() {
        val pcm = ByteArray(8) { it.toByte() }
        val wav = PcmWav.encode(pcm, sampleRateHz = 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertTrue(PcmWav.looksLikeWav(wav))
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        // Sample rate little-endian at offset 24
        assertEquals(16_000, wav[24].toInt() and 0xff or
            ((wav[25].toInt() and 0xff) shl 8) or
            ((wav[26].toInt() and 0xff) shl 16) or
            ((wav[27].toInt() and 0xff) shl 24))
        assertTrue(pcm.contentEquals(wav.copyOfRange(44, wav.size)))
    }

    @Test
    fun contentTypeForFilename_mapsCommonContainers() {
        assertEquals("audio/wav", PcmWav.contentTypeForFilename("clip.wav"))
        assertEquals("audio/ogg", PcmWav.contentTypeForFilename("clip.ogg"))
        assertEquals("audio/mpeg", PcmWav.contentTypeForFilename("clip.mp3"))
        assertEquals("application/octet-stream", PcmWav.contentTypeForFilename("clip.bin"))
    }

    @Test
    fun looksLikeOgg_requiresOggSMagic() {
        assertTrue(PcmWav.looksLikeOgg(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())))
        assertFalse(PcmWav.looksLikeOgg(byteArrayOf(1, 2, 3, 4)))
    }
}
