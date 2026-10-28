package dev.velum.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipSilenceScannerTest {
    @Test
    fun shortSilence_isNotSkipped() {
        val rate = 1_000
        // 200 ms silence then tone — below 400 ms threshold
        val pcm = silence(rate, 200) + tone(rate, 100)
        val result = SkipSilenceScanner.findSkipPastSilence(
            pcm16LeMono = pcm,
            sampleRateHz = rate,
            fromMs = 0L,
            minSilenceMs = 400L,
        )
        assertNull(result)
    }

    @Test
    fun longSilence_skipsToFirstAudible() {
        val rate = 1_000
        // 500 ms silence + 200 ms tone
        val pcm = silence(rate, 500) + tone(rate, 200)
        val result = SkipSilenceScanner.findSkipPastSilence(
            pcm16LeMono = pcm,
            sampleRateHz = rate,
            fromMs = 0L,
            minSilenceMs = 400L,
        )
        assertNotNull(result)
        assertEquals(500L, result.seekToMs)
    }

    @Test
    fun midFile_skipsUpcomingSilence() {
        val rate = 1_000
        val pcm = tone(rate, 100) + silence(rate, 450) + tone(rate, 50)
        // From 100 ms we sit at start of silence
        val result = SkipSilenceScanner.findSkipPastSilence(
            pcm16LeMono = pcm,
            sampleRateHz = rate,
            fromMs = 100L,
            minSilenceMs = 400L,
        )
        assertNotNull(result)
        assertEquals(550L, result.seekToMs)
    }

    @Test
    fun audibleRegion_returnsNull() {
        val rate = 1_000
        val pcm = tone(rate, 800)
        assertNull(
            SkipSilenceScanner.findSkipPastSilence(
                pcm16LeMono = pcm,
                sampleRateHz = rate,
                fromMs = 0L,
            ),
        )
    }

    @Test
    fun isSilent_respectsEnergyThreshold() {
        val quiet = shortLe(100)
        val loud = shortLe(2000)
        assertTrue(SkipSilenceScanner.isSilent(quiet, 0, energyThreshold = 512))
        assertTrue(!SkipSilenceScanner.isSilent(loud, 0, energyThreshold = 512))
    }

    private fun silence(sampleRateHz: Int, durationMs: Int): ByteArray {
        val samples = sampleRateHz * durationMs / 1000
        return ByteArray(samples * 2)
    }

    private fun tone(sampleRateHz: Int, durationMs: Int, amplitude: Int = 4000): ByteArray {
        val samples = sampleRateHz * durationMs / 1000
        val out = ByteArray(samples * 2)
        var i = 0
        while (i < samples) {
            val v = amplitude
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    private fun shortLe(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}
