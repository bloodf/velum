package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WipeDeletesFilesBeforeDbTest {
    @Test
    fun wipe_deletes_files_before_db_rows_are_gone() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "wipe-order-1",
            sessionId = null,
            folderId = null,
            title = "Wav",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = 10,
            localPath = "/tmp/velum-wipe-order-1.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
        )
        val events = mutableListOf<String>()
        repo.wipeAllUserData { path ->
            val stillInDb = handle.database.velumQueries
                .selectRecordingById("wipe-order-1")
                .executeAsOneOrNull() != null
            events += if (stillInDb) "delete-while-row-present:$path" else "delete-after-row-gone:$path"
            true
        }
        assertTrue(
            events.any { it.startsWith("delete-while-row-present:") },
            "first delete pass must run before DB wipe; events=$events",
        )
        // Safety second pass after DB is allowed.
        assertTrue(
            events.any { it.startsWith("delete-after-row-gone:") },
            "safety delete after DB wipe expected; events=$events",
        )
        assertEquals(2, events.size)
    }
}
