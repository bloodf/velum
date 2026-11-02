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
    private fun continuousStereoPackets(count: Int, frame: Int = 960, leftGain: Int = 1): List<ByteArray> {
        val buf = ByteArray(4000)
        eu.buney.kopus.OpusEncoder(48_000, 2, eu.buney.kopus.OpusApplication.Audio).use { enc ->
            return List(count) { packet ->
                val pcm = ShortArray(frame * 2) {
                    val n = packet * frame + it / 2
                    val v = (sin(2 * PI * 440 * n / 48_000) * 8000).toInt()
                    (if (it % 2 == 0) v * leftGain else v).toShort()
                }
                buf.copyOf(enc.encode(pcm, 0, frame, buf, 0, buf.size))
            }
        }
    }

    private fun stereo48kOgg(packets: Int, frame: Int = 960, leftGain: Int = 1): ByteArray =
        SyntheticOgg.page(listOf(SyntheticOgg.opusHead(2, 48_000)), 0, 2) +
            SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) +
            SyntheticOgg.page(continuousStereoPackets(packets, frame, leftGain), 2, 4)

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

    @Test
    fun right_only_tone_is_downmixed_not_dropped() {
        val pcm = decoder.decodeToPcm(stereo48kOgg(25, leftGain = 0))
        assertTrue(pcm.samples.drop(1_600).maxOf { abs(it.toInt()) } > 1_000, "right channel survives downmix")
    }

    @Test
    fun one_hundred_twenty_ms_packet_fills_buffers_exactly() {
        val src = Files.createTempFile("velum-120ms", ".ogg")
        val dest = Files.createTempFile("velum-120ms", ".pcm")
        try {
            val ogg = stereo48kOgg(3, frame = 5_760)
            Files.write(src, ogg)
            val pcm = decoder.decodeToPcm(ogg)
            assertEquals(3 * 1_920, pcm.samples.size)
            val meta = decoder.decodePathToPcmFile(src.toString(), ModelStoreDir(dest.toString()))
            assertEquals(3L * 1_920 * 2, meta.sizeBytes)
        } finally {
            Files.deleteIfExists(src)
            Files.deleteIfExists(dest)
        }
    }

    @Test
    fun multistream_mapping_is_rejected() {
        for ((ch, family) in listOf(6 to 1, 2 to 255, 1 to 255)) {
            val ogg = SyntheticOgg.page(listOf(SyntheticOgg.opusHead(ch, 48_000, family = family)), 0, 2) +
                SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) +
                SyntheticOgg.page(listOf(SyntheticOgg.audioPacket(false)), 2, 4)
            val e = kotlin.runCatching { decoder.decodeToPcm(ogg) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException && "multistream" in e.message.orEmpty(), "ch=$ch family=$family: $e")
        }
    }
}
