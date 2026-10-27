package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WipeAndExportTest {
    @Test
    fun exportThenWipeClearsUserRecordings() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "wipe-1",
            sessionId = null,
            folderId = null,
            title = "Demo",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "hello",
            summaryMarkdown = "## Notes\n- a",
            status = "ready",
        )
        val backup = repo.exportLibraryBackupText()
        assertTrue(backup.contains("Demo"))
        assertTrue(backup.contains("hello"))
        repo.wipeAllUserData()
        assertEquals(0L, repo.recordingCount())
    }
}
