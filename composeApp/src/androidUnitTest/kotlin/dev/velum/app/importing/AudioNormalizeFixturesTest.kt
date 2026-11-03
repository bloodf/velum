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
import kotlinx.coroutines.async
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
    fun wavFastPath_streamsExactPcmAcrossSlicesWithAnOddTail() = runBlocking {
        val src = File(dir, "ramp.wav")
        // 700,001 data bytes: three 256 KiB slices and an odd last byte, which is not a whole sample.
        val pcm = ByteArray(700_001) { (it * 13).toByte() }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        src.writeBytes(header.array() + pcm)
        val out = File(AudioNormalizer().normalizeToWav(src.absolutePath, 16_000).wavPath).readBytes()
        val whole = pcm.size - 1 // the dangling byte is dropped, like any partial frame
        assertEquals(44 + whole, out.size)
        org.junit.Assert.assertArrayEquals(pcm.copyOf(whole), out.copyOfRange(44, out.size))
        assertEquals(whole, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals(36 + whole, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertTrue("no temp file is left beside the output", File(dir, "normalized").list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun aCutOffFmtChunkFailsButNeverTouchesAnEarlierOutputOfTheSameName() = runBlocking {
        val old = File(dir, "normalized/bad_16000.wav").apply { parentFile!!.mkdirs(); writeText("an earlier good import") }
        val bad = File(dir, "bad.wav")
        val b = ByteBuffer.allocate(12 + 8 + 6).order(ByteOrder.LITTLE_ENDIAN) // fmt claims 16 bytes, 6 follow
        b.put("RIFF".toByteArray()).putInt(26).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putShort(0)
        bad.writeBytes(b.array())
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            runBlocking { AudioNormalizer().normalizeToWav(bad.absolutePath, 16_000) }
        }
        assertEquals("an earlier good import", old.readText())
    }

    @Test
    fun aFailedNormalizeLeavesNoPartFileAndKeepsEarlierOutputsOfTheSameAndOtherNames() = runBlocking {
        val other = File(dir, "normalized/keep_16000.wav").apply { parentFile!!.mkdirs(); writeText("an earlier import") }
        val sameName = File(dir, "normalized/bad_16000.wav").apply { writeText("an earlier good import of this name") }
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
        assertTrue("no half-written file may remain", File(dir, "normalized").list()!!.none { it.endsWith(".part") })
        assertEquals("a failed retry must not destroy the earlier good output", "an earlier good import of this name", sameName.readText())
        assertEquals("an earlier import", other.readText())
    }

    @Test
    fun cancelling_mid_copy_of_a_large_wav_throws_and_leaves_no_output() = runBlocking {
        val big = File(dir, "huge.wav")
        val dataBytes = 128L * 1024 * 1024
        java.io.RandomAccessFile(big, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            h.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes.toInt())
            raf.write(h.array())
            raf.setLength(44 + dataBytes) // zero PCM, no disk used for the source
        }
        val normalizedDir = File(dir, "normalized")
        fun mine() = normalizedDir.listFiles().orEmpty().filter { it.name.startsWith("huge_16000") }
        val job = this.async(kotlinx.coroutines.Dispatchers.Default) {
            AudioNormalizer().normalizeToWav(big.absolutePath, 16_000)
        }
        // The copy has started once the output holds bytes; cancel while it is still going.
        kotlinx.coroutines.withTimeout(60_000) { while (mine().none { it.length() > 0L }) kotlinx.coroutines.delay(1) }
        var largest = 0L
        val watcher = Thread { while (!job.isCompleted) { largest = maxOf(largest, mine().maxOfOrNull { it.length() } ?: 0L); Thread.sleep(0, 200_000) } }
        watcher.start()
        job.cancel()
        try {
            job.await()
            org.junit.Assert.fail("the import should have been cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        watcher.join(10_000)
        assertTrue("a cancelled import must not leave its WAV or temp file", mine().isEmpty())
        // It stopped at the next slice instead of copying everything and noticing afterwards.
        assertTrue("the copy ran on after the cancel ($largest of $dataBytes bytes written)", largest < dataBytes / 2)
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
