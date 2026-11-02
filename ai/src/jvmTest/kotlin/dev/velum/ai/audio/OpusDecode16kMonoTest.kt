package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #95 owner decision: decoded Opus must match the import path PCM contract (16 kHz mono),
 * whatever the stream's coded rate/channels. Fixture: synthetic 48 kHz stereo 440 Hz tone.
 */
class OpusDecode16kMonoTest {
    private val decoder = KopusOpusDecoder()

    /**
     * One encoder over a phase-continuous tone. SyntheticOgg.audioPacket restarts the tone every
     * packet (8.8 cycles per 20 ms), which reads as 400 Hz and can't prove the output rate.
     */
    private fun continuousStereoPackets(count: Int): List<ByteArray> {
        val buf = ByteArray(1275)
        eu.buney.kopus.OpusEncoder(48_000, 2, eu.buney.kopus.OpusApplication.Audio).use { enc ->
            return List(count) { packet ->
                val pcm = ShortArray(960 * 2) {
                    val frame = packet * 960 + it / 2
                    (sin(2 * PI * 440 * frame / 48_000) * 8000).toInt().toShort()
                }
                buf.copyOf(enc.encode(pcm, 0, 960, buf, 0, buf.size))
            }
        }
    }

    private fun stereo48kOgg(packets: Int): ByteArray =
        SyntheticOgg.page(listOf(SyntheticOgg.opusHead(2, 48_000)), 0, 2) +
            SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) +
            SyntheticOgg.page(continuousStereoPackets(packets), 2, 4)

    @Test
    fun streamed_48k_stereo_file_decodes_to_16k_mono_with_matching_duration_and_pitch() {
        val packets = 50 // 50 x 20 ms = 1.0 s
        val src = Files.createTempFile("velum-48k-stereo", ".ogg")
        val dest = Files.createTempFile("velum-16k-mono", ".pcm")
        try {
            Files.write(src, stereo48kOgg(packets))
            val meta = decoder.decodePathToPcmFile(src.toString(), ModelStoreDir(dest.toString()))
            assertEquals(16_000, meta.sampleRateHz)
            assertEquals(1, meta.channels)
            val pcmBytes = Files.readAllBytes(dest)
            assertEquals(pcmBytes.size.toLong(), meta.sizeBytes)

            val durationMs = meta.sizeBytes / 2 * 1000 / meta.sampleRateHz
            assertTrue(abs(durationMs - 1000) <= 20, "duration ${durationMs}ms within one frame of 1000ms")

            val samples = ShortArray(pcmBytes.size / 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            // Skip codec warm-up, then count rising zero crossings: a wrong rate or channel
            // interleave shifts the apparent pitch away from the 440 Hz source tone.
            val steady = samples.copyOfRange(1_600, samples.size)
            assertTrue(steady.maxOf { abs(it.toInt()) } > 1_000, "tone survives downmix")
            var crossings = 0
            for (i in 1 until steady.size) if (steady[i - 1] < 0 && steady[i] >= 0) crossings++
            val hz = crossings * 16_000.0 / steady.size
            assertTrue(abs(hz - 440.0) < 10.0, "pitch $hz Hz ~ 440 Hz")
        } finally {
            Files.deleteIfExists(src)
            Files.deleteIfExists(dest)
        }
    }

    @Test
    fun in_memory_48k_stereo_decodes_to_16k_mono() {
        val pcm = decoder.decodeToPcm(stereo48kOgg(10))
        assertEquals(16_000, pcm.sampleRateHz)
        assertEquals(1, pcm.channels)
        assertEquals(10 * 320, pcm.samples.size)
    }
}
