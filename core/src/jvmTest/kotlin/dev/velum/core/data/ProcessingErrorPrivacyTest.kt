package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Processing failure text (may echo provider URLs or prompt fragments) must not outlive its recording. */
class ProcessingErrorPrivacyTest {
    private suspend fun VelumRepository.seed(id: String) = insertRecording(
        id = id, sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
        startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null,
        transcript = "x", summaryMarkdown = null, status = "failed",
    )

    @Test
    fun nullClearsRow_notEmptyString() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.setProcessingError("a", "boom https://example.test/v1?k=1")
        repo.setProcessingError("a", null)
        assertNull(repo.getProcessingError("a"))
        assertNull(handle.database.velumQueries.getMeta("processing_error:a").executeAsOneOrNull(), "row must be deleted")
    }

    @Test
    fun hardDelete_removesThatRecordingsError_only() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.seed("a"); repo.seed("b")
        repo.setProcessingError("a", "secret a")
        repo.setProcessingError("b", "secret b")
        repo.deleteRecordingHard("a")
        assertNull(handle.database.velumQueries.getMeta("processing_error:a").executeAsOneOrNull())
        assertEquals("secret b", repo.getProcessingError("b"))
    }

    @Test
    fun wipeAllUserData_removesEveryProcessingError() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.seed("a")
        repo.setProcessingError("a", "secret a")
        repo.setMeta("processing_error:orphan", "secret orphan")
        repo.setMeta("keep_me", "1")
        repo.wipeAllUserData()
        assertNull(repo.getProcessingError("a"))
        assertNull(repo.getProcessingError("orphan"))
        assertEquals("1", repo.getMeta("keep_me"))
    }

    @Test
    fun aWorkerRunningOnAfterAWipe_leavesNoKeys() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.seed("a")
        repo.wipeAllUserData()
        repo.setProcessingError("a", "secret a")
        repo.setUnpromotedRun("a", "work-1")
        assertNull(repo.getProcessingError("a"), "error text outlived the wipe")
        assertNull(repo.unpromotedRunOwner("a"), "a run flag outlived the wipe")
    }
}
