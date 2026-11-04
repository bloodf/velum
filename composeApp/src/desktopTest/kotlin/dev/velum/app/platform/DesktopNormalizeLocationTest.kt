package dev.velum.app.platform

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #387: desktop's normalized copy lives in app storage, where a delete can reach it, and the picked file is untouched. */
class DesktopNormalizeLocationTest {
    private val home = Files.createTempDirectory("velum-desktop-home").toFile()
    private val picked = Files.createTempDirectory("velum-desktop-picked").toFile()
    private var realHome: String? = null

    @BeforeTest fun redirectHome() {
        realHome = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
    }

    @AfterTest fun restoreHome() {
        System.setProperty("user.home", realHome!!)
        home.deleteRecursively()
        picked.deleteRecursively()
    }

    private fun wav(file: File, sample: Short): File {
        val samples = 800
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort(sample) }
        file.parentFile.mkdirs()
        file.writeBytes(buffer.array())
        return file
    }

    @Test fun a_picked_wav_normalizes_into_app_storage_and_stays_as_it_was() = runBlocking {
        val source = wav(File(picked, "meeting.wav"), 3)
        val before = source.readBytes()
        val out = AudioNormalizer().normalizeToWav(source.path)
        assertTrue(isAppOwnedAudioPath(out.wavPath), "a delete could not reach ${out.wavPath}")
        assertFalse(File(picked, "normalized").exists(), "a folder was left next to the picked file")
        assertContentEquals(before, source.readBytes(), "the picked file changed")
        assertFalse(isAppOwnedAudioPath(source.path), "the picked file counts as the app's")
        assertTrue(deleteFileIfPresent(out.wavPath))
        assertTrue(source.exists(), "deleting the normalized copy took the picked file")
    }

    @Test fun a_picked_ogg_is_copied_into_app_storage() = runBlocking {
        val source = File(picked, "note.ogg").apply { writeBytes(ByteArray(32) { 5 }) }
        val out = AudioNormalizer().normalizeToWav(source.path)
        assertTrue(isAppOwnedAudioPath(out.wavPath))
        assertFalse(File(picked, "normalized").exists())
        assertContentEquals(ByteArray(32) { 5 }, source.readBytes())
    }

    @Test fun two_picked_files_of_one_name_do_not_overwrite_each_other() = runBlocking {
        val first = AudioNormalizer().normalizeToWav(wav(File(picked, "a/same.wav"), 1).path)
        val second = AudioNormalizer().normalizeToWav(wav(File(picked, "b/same.wav"), 2).path)
        assertNotEquals(first.wavPath, second.wavPath)
        assertEquals(true, File(first.wavPath).exists() && File(second.wavPath).exists())
    }
}
