package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopNormalizeLocationTest {
    @Test
    fun ogg_copy_preserves_source_and_uses_app_storage() = runBlocking {
        val root = Files.createTempDirectory("velum-desktop-normalize").toFile()
        val oldHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", File(root, "home").path)
            val bytes = ByteArray(32) { it.toByte() }
            val source = File(root, "picked/note.ogg").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val output = File(AudioNormalizer().normalizeToWav(source.path).wavPath)

            assertTrue(isAppOwnedAudioPath(output.path))
            assertNotEquals(source.canonicalFile, output.canonicalFile)
            assertContentEquals(bytes, output.readBytes())
            assertContentEquals(bytes, source.readBytes())
            assertFalse(File(source.parentFile, "normalized").exists())
            assertTrue(deleteFileIfPresent(output.path))
            assertContentEquals(bytes, source.readBytes())
        } finally {
            if (oldHome == null) System.clearProperty("user.home") else System.setProperty("user.home", oldHome)
            root.deleteRecursively()
        }
    }

    @Test
    fun same_name_sources_get_distinct_copies_even_with_identical_content() = runBlocking {
        val root = Files.createTempDirectory("velum-desktop-normalize").toFile()
        val oldHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", File(root, "home").path)
            val bytes = ByteArray(32) { it.toByte() }
            val first = File(root, "a/same.ogg").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val second = File(root, "b/same.ogg").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val normalizer = AudioNormalizer()
            val firstCopy = File(normalizer.normalizeToWav(first.path).wavPath)
            val secondCopy = File(normalizer.normalizeToWav(second.path).wavPath)

            assertNotEquals(firstCopy.canonicalFile, secondCopy.canonicalFile)
            assertContentEquals(bytes, firstCopy.readBytes())
            assertContentEquals(bytes, secondCopy.readBytes())
            assertTrue(deleteFileIfPresent(firstCopy.path))
            assertContentEquals(bytes, secondCopy.readBytes())
            assertContentEquals(bytes, first.readBytes())
            assertContentEquals(bytes, second.readBytes())
        } finally {
            if (oldHome == null) System.clearProperty("user.home") else System.setProperty("user.home", oldHome)
            root.deleteRecursively()
        }
    }
}
