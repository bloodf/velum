package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #86 AC3: streaming Opus→PCM must match the non-streaming decoder sample-for-sample
 * on a synthetic Ogg Opus fixture (generated in-process; not a Plaud recording).
 */
class GoldenOpusDecodeEqualityTest {
    @Test
    fun streamingPath_matchesInMemoryDecode_sampleEquality() {
        // Fixture: 8 × 20 ms tone packets (160 ms) + 2 silence packets — built via SyntheticOggOpus.
        val packets = buildList {
            repeat(8) { add(SyntheticOggOpus.tonePacket(freqHz = 440.0 + it * 37.0)) }
            repeat(2) { add(SyntheticOggOpus.silencePacket()) }
        }
        val ogg = SyntheticOggOpus.buildOgg(packets)
        assertTrue(PcmWav.looksLikeOgg(ogg))
        assertTrue(ogg.size < 8_192, "golden fixture must stay tiny")

        val decoder = KopusOpusDecoder()
        val reference = decoder.decodeToPcm(ogg)
        assertTrue(reference.samples.isNotEmpty(), "reference PCM must be non-empty")
        assertEquals(SyntheticOggOpus.SAMPLE_RATE_HZ, reference.sampleRateHz)
        assertEquals(1, reference.channels)

        val dir = File.createTempFile("velum-golden-opus", null).apply {
            delete()
            mkdirs()
        }
        try {
            val oggFile = File(dir, "golden.ogg")
            oggFile.writeBytes(ogg)
            val spill = ModelStoreDir(dir.absolutePath).resolve("streamed.s16le")
            val meta = decoder.decodePathToPcmFile(oggFile.absolutePath, spill)
            assertEquals(reference.sampleRateHz, meta.sampleRateHz)
            assertEquals(reference.channels, meta.channels)
            assertEquals(reference.samples.size * 2L, meta.sizeBytes)

            val streamedBytes = File(spill.absolutePath).readBytes()
            assertEquals(reference.samples.size * 2, streamedBytes.size)
            val streamedSamples = shortsFromS16le(streamedBytes)
            assertTrue(
                streamedSamples.contentEquals(reference.samples),
                "streaming PCM must equal in-memory decode sample-for-sample " +
                    "(len=${streamedSamples.size}, ref=${reference.samples.size})",
            )

            // Also exercise decodeToPcmFile (byte[] demux path) against the same golden.
            val spill2 = ModelStoreDir(dir.absolutePath).resolve("from-bytes.s16le")
            val meta2 = decoder.decodeToPcmFile(ogg, spill2)
            assertEquals(meta.sizeBytes, meta2.sizeBytes)
            assertTrue(
                File(spill2.absolutePath).readBytes().contentEquals(streamedBytes),
                "decodeToPcmFile must match decodePathToPcmFile byte-for-byte",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun shortsFromS16le(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = buf.short
        }
        return out
    }
}
