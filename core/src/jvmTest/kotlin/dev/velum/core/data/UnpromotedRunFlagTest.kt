package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class UnpromotedRunFlagTest {
    private fun repo() = VelumRepository(createDatabase(DriverFactory()))

    private suspend fun VelumRepository.insert(id: String) = insertRecording(
        id = id, sessionId = null, folderId = null, title = "T", source = "phone", deviceSerial = null,
        startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null, transcript = null,
        summaryMarkdown = null, status = "queued",
    )

    @Test fun flag_holds_the_owner_per_recording_and_clears() = runBlocking {
        val repo = repo()
        assertNull(repo.unpromotedRunOwner("a"))
        repo.setUnpromotedRun("a", "work-1")
        assertEquals("work-1", repo.unpromotedRunOwner("a"))
        assertNull(repo.unpromotedRunOwner("b"))
        repo.setUnpromotedRun("a", "work-2") // a REPLACE run takes the flag over
        assertEquals("work-2", repo.unpromotedRunOwner("a"))
        repo.setUnpromotedRun("a", null)
        assertNull(repo.unpromotedRunOwner("a"))
    }

    @Test fun wipe_and_hard_delete_clear_it() = runBlocking {
        val repo = repo()
        repo.insert("a"); repo.insert("b")
        repo.setUnpromotedRun("a", "w"); repo.setUnpromotedRun("b", "w")
        repo.deleteRecordingHard("a")
        assertNull(repo.unpromotedRunOwner("a"))
        repo.wipeAllUserData()
        assertNull(repo.unpromotedRunOwner("b"))
    }
}
