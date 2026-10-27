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
