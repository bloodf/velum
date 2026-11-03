package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    private fun oggWith(head: ByteArray): ByteArray =
        SyntheticOgg.page(listOf(head), 0, 2) +
            SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) +
            SyntheticOgg.page(listOf(SyntheticOgg.audioPacket(false)), 2, 4)

    private fun family1(channels: Int, streams: Int, coupled: Int, vararg map: Int) =
        SyntheticOgg.opusHead(
            channels, 48_000, family = 1,
            mapping = byteArrayOf(streams.toByte(), coupled.toByte()) + ByteArray(map.size) { map[it].toByte() },
        )

    @Test
    fun single_stream_family1_decodes() {
        assertEquals(320, decoder.decodeToPcm(oggWith(family1(1, 1, 0, 0))).samples.size)
        assertEquals(320, decoder.decodeToPcm(oggWith(family1(2, 1, 1, 0, 1))).samples.size)
    }

    @Test
    fun multistream_mapping_is_rejected() {
        val heads = listOf(
            "6ch family 1" to SyntheticOgg.opusHead(6, 48_000, family = 1),
            "family 255 stereo" to SyntheticOgg.opusHead(2, 48_000, family = 255),
            "family 255 mono" to SyntheticOgg.opusHead(1, 48_000, family = 255),
            "two uncoupled mono streams" to family1(2, 2, 0, 0, 1),
            "19-byte family 1 head" to SyntheticOgg.opusHead(2, 48_000, family = 1),
        )
        for ((name, head) in heads) {
            val e = kotlin.runCatching { decoder.decodeToPcm(oggWith(head)) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException && "multistream" in e.message.orEmpty(), "$name: $e")
        }
    }

    @Test
    fun voip_stereo_silk_packets_decode_to_16k_mono() {
        val buf = ByteArray(4000)
        val packets = eu.buney.kopus.OpusEncoder(48_000, 2, eu.buney.kopus.OpusApplication.Voip).use { enc ->
            List(10) { p ->
                val pcm = ShortArray(960 * 2) { (sin(2 * PI * 300 * (p * 960 + it / 2) / 48_000) * 8000).toInt().toShort() }
                buf.copyOf(enc.encode(pcm, 0, 960, buf, 0, buf.size))
            }
        }
        val ogg = SyntheticOgg.page(listOf(SyntheticOgg.opusHead(2, 48_000)), 0, 2) +
            SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) + SyntheticOgg.page(packets, 2, 4)
        val pcm = decoder.decodeToPcm(ogg)
        assertEquals(16_000, pcm.sampleRateHz)
        assertEquals(1, pcm.channels)
        assertEquals(10 * 320, pcm.samples.size)
        assertTrue(pcm.samples.drop(800).maxOf { abs(it.toInt()) } > 1_000, "voice tone survives")
    }

    private fun oggWithPreSkip(packets: List<ByteArray>, preSkip: Int): ByteArray =
        SyntheticOgg.page(listOf(SyntheticOgg.opusHead(2, 48_000, preSkip = preSkip)), 0, 2) +
            SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1) +
            SyntheticOgg.page(packets, 2, 4, granule = packets.size * 960L)

    private fun decodeFile(ogg: ByteArray): ShortArray {
        val src = Files.createTempFile("velum-preskip", ".ogg")
        val dest = Files.createTempFile("velum-preskip", ".pcm")
        try {
            Files.write(src, ogg)
            val meta = decoder.decodePathToPcmFile(src.toString(), ModelStoreDir(dest.toString()))
            val bytes = Files.readAllBytes(dest)
            assertEquals(meta.sizeBytes, bytes.size.toLong())
            return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        } finally {
            Files.deleteIfExists(src)
            Files.deleteIfExists(dest)
        }
    }

    /** Both decode paths must drop exactly the head: withSkip[i] == noSkip[i + skip16k], same length. */
    private fun assertDropsHead(preSkip48k: Int, packets: Int = 10) {
        val pk = continuousStereoPackets(packets)
        val skip = preSkip48k / 3
        val none = decoder.decodeToPcm(oggWithPreSkip(pk, 0)).samples
        val inMemory = decoder.decodeToPcm(oggWithPreSkip(pk, preSkip48k)).samples
        val streamed = decodeFile(oggWithPreSkip(pk, preSkip48k))
        assertEquals(packets * 320, none.size)
        val expected = none.copyOfRange(minOf(skip, none.size), none.size)
        assertContentEquals(expected, inMemory, "in-memory drops the head, not the tail")
        assertContentEquals(expected, streamed, "streaming drops the head, not the tail")
    }

    @Test
    fun pre_skip_warmup_is_dropped_from_the_head_at_the_output_rate() = assertDropsHead(312) // 104 samples

    @Test
    fun pre_skip_longer_than_first_packet_spans_packets() = assertDropsHead(3_000) // 1000 samples, 3.1 packets

    @Test
    fun pre_skip_exactly_one_packet_drops_whole_packet_cleanly() = assertDropsHead(960) // drop == n path

    @Test
    fun pre_skip_larger_than_the_stream_yields_empty_pcm() {
        // A bogus pre-skip silently swallows the audio (up to 65535/48000 = 1.37 s); unconfirmed on real files (#7).
        assertDropsHead(9_600 * 2, packets = 10)
        assertEquals(0, decoder.decodeToPcm(oggWithPreSkip(continuousStereoPackets(10), 65_535)).samples.size)
    }
}
