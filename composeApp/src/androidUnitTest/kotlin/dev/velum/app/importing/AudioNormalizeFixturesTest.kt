package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.ImportAudioFormats
import dev.velum.app.platform.sha256File
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fixture-based normalize + hash dedupe. Builds minimal WAV (PCM16) and verifies
 * MediaCodec/MediaExtractor path (WAV passthrough/resample) plus SHA-256 stability.
 * Other containers (mp3/m4a/…) need real encoded bytes; extension registry is covered
 * in [ImportAudioFormatsTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioNormalizeFixturesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dir = File(context.cacheDir, "import_fixtures").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun wavFixture_normalizesToPcm16k() = runBlocking {
        val wav = writeSilentWav(File(dir, "tone.wav"), sampleRate = 16_000, durationMs = 200)
        assertTrue(ImportAudioFormats.isSupportedFileName(wav.name))
        val hash1 = sha256File(wav.absolutePath)
        val normalized = AudioNormalizer().normalizeToWav(wav.absolutePath, targetSampleRateHz = 16_000)
        assertEquals(16_000, normalized.sampleRateHz)
        assertTrue(normalized.durationMs >= 100)
        assertTrue(File(normalized.wavPath).exists())
        assertEquals(hash1, sha256File(wav.absolutePath))
        assertTrue(normalized.contentSha256.isNotBlank())
    }

    @Test
    fun wavFastPath_streamsExactPcmAndPatchesTheHeader() = runBlocking {
        val src = File(dir, "ramp.wav")
        val pcm = ByteArray(16_000 * 2 * 3) { (it * 13).toByte() } // 3 s, spans many 256 KiB slices? no: one slice
        writeSilentWav(src, sampleRate = 16_000, durationMs = 100)
        // Replace the data with the ramp, keeping a valid header.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        src.writeBytes(header.array() + pcm)
        val out = File(AudioNormalizer().normalizeToWav(src.absolutePath, 16_000).wavPath).readBytes()
        assertEquals(44 + pcm.size, out.size)
        org.junit.Assert.assertArrayEquals(pcm, out.copyOfRange(44, out.size))
        assertEquals(pcm.size, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test
    fun failureAfterTheWriterStartedDeletesTheOutput_andPreviousOutputsAreNotTouchedByOtherNames() = runBlocking {
        val other = File(dir, "normalized/keep_16000.wav").apply { parentFile!!.mkdirs(); writeText("an earlier import") }
        val bad = File(dir, "bad.wav")
        // PCM16 stereo header with a data chunk: not the fast path, so the (unavailable under Robolectric) decoder runs.
        val b = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(44).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(8)
        bad.writeBytes(b.array())
        try {
            AudioNormalizer().normalizeToWav(bad.absolutePath, 16_000)
            org.junit.Assert.fail("the decoder cannot decode this under a unit test")
        } catch (_: Throwable) {
        }
        assertTrue("a half-written WAV must not remain", !File(dir, "normalized/bad_16000.wav").exists())
        assertEquals("an earlier import", other.readText())
    }

    @Test
    fun extensionMatrix_matchesRequirement() {
        val required = listOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp", "webm", "mp4")
        required.forEach { ext ->
            assertTrue(ImportAudioFormats.isSupportedFileName("clip.$ext"))
        }
    }

    @Test
    fun sameBytes_sameSha256() {
        val a = writeSilentWav(File(dir, "a.wav"), 16_000, 100)
        val b = writeSilentWav(File(dir, "b.wav"), 16_000, 100)
        assertEquals(sha256File(a.absolutePath), sha256File(b.absolutePath))
    }

    private fun writeSilentWav(file: File, sampleRate: Int, durationMs: Int): File {
        val samples = (sampleRate * durationMs / 1000).coerceAtLeast(1)
        val dataSize = samples * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        repeat(samples) { buffer.putShort(0) }
        file.writeBytes(buffer.array())
        return file
    }
}
