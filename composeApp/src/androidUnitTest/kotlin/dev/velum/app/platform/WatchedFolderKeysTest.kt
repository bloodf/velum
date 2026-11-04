package dev.velum.app.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #318: an imported watched file is not copied again, and a failed import is. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WatchedFolderKeysTest {
    private val bytes = ByteArray(2_000) { it.toByte() }
    private var opened = 0

    private fun source(uri: String, name: String, modified: Long = 10L) =
        WatchedSource(uri, name, modified, bytes.size.toLong()) { opened++; ByteArrayInputStream(bytes) }

    @Test fun a_second_scan_copies_nothing_once_the_files_are_marked_imported() {
        val root = Files.createTempDirectory("velum-watched-keys").toFile()
        val sources = listOf(source("content://a", "a.wav"), source("content://b", "b.wav"))
        val first = copyUnimported(sources, emptySet(), root)
        assertEquals(2, first.size); assertEquals(2, opened)
        assertTrue(first.all { it.fileKey.isNotEmpty() })
        // Only a.wav was imported (b.wav's import failed): b is copied again, a is not read.
        opened = 0
        File(root, "x").mkdirs()
        val second = copyUnimported(sources, setOf(first.first { it.displayName == "a.wav" }.fileKey), File(root, "x"))
        assertEquals(listOf("b.wav"), second.map { it.displayName }); assertEquals(1, opened)
        opened = 0
        assertTrue(copyUnimported(sources, first.map { it.fileKey }.toSet(), File(root, "y")).isEmpty())
        assertEquals("a fully imported folder must not be read at all", 0, opened)
    }

    @Test fun a_changed_file_is_a_new_file() {
        val old = WatchedFolderStore.fileKey("content://a", 10L, 100L)
        assertTrue(old != WatchedFolderStore.fileKey("content://a", 11L, 100L))
        assertTrue(old != WatchedFolderStore.fileKey("content://a", 10L, 101L))
    }

    @Test fun keys_live_in_their_own_prefs_file_and_clear_forgets_them() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WatchedFolderStore.markImported(app, "k1")
        assertTrue("k1" in WatchedFolderStore.importedKeys(app))
        assertTrue("k1" !in app.getSharedPreferences("velum_watched_folders", 0).getStringSet("imported_files", emptySet()).orEmpty())
        WatchedFolderStore.clear(app)
        assertTrue(WatchedFolderStore.importedKeys(app).isEmpty())
    }

    @Test fun backup_and_device_transfer_exclude_the_imported_keys() {
        val xml = listOf("data_extraction_rules.xml", "backup_rules.xml").map { name ->
            listOf(File("src/androidMain/res/xml/$name"), File("composeApp/src/androidMain/res/xml/$name")).first { it.isFile }.readText()
        }
        xml.forEach { assertTrue(it.contains("velum_watched_imported.xml")) }
    }
}
