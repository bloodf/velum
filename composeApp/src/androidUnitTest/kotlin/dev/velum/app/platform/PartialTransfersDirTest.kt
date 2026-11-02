package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** recordingsDirectoryPath() no longer creates anything, so the partial store must create its own directory. */
class PartialTransfersDirTest {
    @Test
    fun partial_store_creates_its_directory_on_a_fresh_install_and_after_a_wipe() {
        val base = Files.createTempDirectory("velum-partials").toFile()
        val previous = runCatching { File(recordingsDirectoryPath()).parent }.getOrNull()
        try {
            installRecordingFilesDir(base.absolutePath)
            val dir = File(base, "downloads")
            assertFalse(File(base, "recordings").exists())
            assertEquals(0, sweepStalePartialTransfers(System.currentTimeMillis()), "nothing to sweep is fine")
            deleteAllPartialTransfers() // nothing to delete is fine

            val first = assertNotNull(openPartialTransfer(1L, "SYNTH-SN-1"))
            first.write(ByteArray(5))
            assertEquals(5L, first.length())
            first.close()
            assertTrue(dir.isDirectory)

            deleteAllPartialTransfers()
            assertFalse(dir.exists())

            val second = assertNotNull(openPartialTransfer(1L, "SYNTH-SN-1"))
            second.write(ByteArray(3))
            assertEquals(3L, second.length())
            second.close()
        } finally {
            installRecordingFilesDir(previous)
            base.deleteRecursively()
        }
    }
}
