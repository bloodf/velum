package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** recordingsDirectoryPath() runs in composition, so it must not touch disk; a wiped dir must not break saving. */
class RecordingsDirAfterWipeTest {
    @Test
    fun path_lookup_is_disk_free_and_save_recreates_a_wiped_directory() {
        val base = Files.createTempDirectory("velum-recdir").toFile()
        try {
            installRecordingFilesDir(base.absolutePath)
            val dir = recordingsDirectoryPath()
            assertEquals(File(base, "recordings").absolutePath, dir)
            assertFalse(File(dir).exists(), "path lookup must not create the directory")

            val first = "$dir/a.wav"
            writeBytesToPath(first, byteArrayOf(1, 2))
            assertTrue(File(first).exists())

            // "Wipe all data" / storage purge removes the directory at runtime.
            File(dir).deleteRecursively()
            assertEquals(dir, recordingsDirectoryPath())

            val second = "$dir/b.wav"
            writeBytesToPath(second, byteArrayOf(3, 4))
            assertContentEquals(byteArrayOf(3, 4), File(second).readBytes())
        } finally {
            base.deleteRecursively()
        }
    }
}
