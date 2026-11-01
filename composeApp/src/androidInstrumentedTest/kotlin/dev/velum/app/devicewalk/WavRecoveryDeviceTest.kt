package dev.velum.app.devicewalk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.platform.WavWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Arrays
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android filesystem operations, confined to synthetic cache fixtures; no microphone. */
@RunWith(AndroidJUnit4::class)
class WavRecoveryDeviceTest {
    private lateinit var root: File
    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(context.cacheDir.toPath(), "wav-recovery-sdk-").toFile()
    }
    @After fun tearDown() { root.listFiles()?.forEach { it.delete() }; root.delete() }
    @Test fun publishes_without_aliasing_or_deleting_the_source() {
        val source = wave("rec-sdk.wav.partial", 1); val original = read(source)
        val result = WavWriter.recoverOrFinalize(source.absolutePath, 16_000)
        assertNotNull(result)
        val target = File(result!!.absolutePath); val published = read(target)
        assertArrayEquals(original, read(source)); assertArrayEquals(original, published)
        RandomAccessFile(source, "rw").use { it.seek(it.length()); it.write(byteArrayOf(3, 4)) }
        assertArrayEquals(published, read(target)); assertNull(WavWriter.recoverOrFinalize(source.absolutePath, 16_000))
    }
    @Test fun invalid_partial_cannot_destroy_a_valid_final_file() {
        val target = wave("rec-sdk.wav", 1); val original = read(target)
        val source = File(root, "rec-sdk.wav.partial")
        RandomAccessFile(source, "rw").use { it.write(byteArrayOf(1, 2, 3)) }
        assertNull(WavWriter.recoverOrFinalize(source.absolutePath, 16_000))
        assertArrayEquals(original, read(target)); assertTrue(source.exists())
    }
    @Test fun different_valid_partial_is_a_non_destructive_conflict() {
        val target = wave("rec-sdk.wav", 1); val source = wave("rec-sdk.wav.partial", 2)
        val a = read(target); val b = read(source)
        assertNull(WavWriter.recoverOrFinalize(source.absolutePath, 16_000))
        assertArrayEquals(a, read(target)); assertArrayEquals(b, read(source))
    }
    @Test fun stale_size_fields_are_repaired_without_changing_pcm() {
        val source = wave("rec-sdk.wav", 1); val expected = read(source)
        RandomAccessFile(source, "rw").use { it.seek(4); it.write(byteArrayOf(36, 0, 0, 0)); it.seek(40); it.write(byteArrayOf(0, 0, 0, 0)) }
        assertNotNull(WavWriter.recoverOrFinalize(source.absolutePath, 16_000))
        assertTrue(Arrays.equals(expected, read(source)))
    }
    private fun wave(name: String, sample: Byte): File = File(root, name).also {
        val writer = WavWriter(it.absolutePath, 16_000); writer.writePcm(ByteArray(3200) { sample }); writer.finish()
    }
    private fun read(file: File): ByteArray = RandomAccessFile(file, "r").use {
        val result = ByteArray(it.length().toInt()); it.readFully(result); result
    }
}
