package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ConditionalStatusTest {
    private suspend fun VelumRepository.insert(id: String, status: String) = insertRecording(
        id = id, sessionId = null, folderId = null, title = "T", source = "phone", deviceSerial = null,
        startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null, transcript = null,
        summaryMarkdown = null, status = status,
    )

    @Test fun queuedIsWrittenOnlyWhileTheRecordingIsActive() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insert("active", "generating"); repo.insert("done", "ready"); repo.insert("broken", "failed")
        assertTrue(repo.updateStatusIfActive("active", "queued"))
        assertEquals("queued", repo.getRecording("active")!!.status)
        assertFalse(repo.updateStatusIfActive("done", "queued"))
        assertFalse(repo.updateStatusIfActive("broken", "queued"))
        assertFalse(repo.updateStatusIfActive("missing", "queued"))
        assertEquals("ready", repo.getRecording("done")!!.status)
        assertEquals("failed", repo.getRecording("broken")!!.status)
    }

    @Test fun updateStatusIfEqualsMovesOnlyFromTheExpectedStatus() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insert("a", "queued"); repo.insert("b", "ready")
        assertTrue(repo.updateStatusIfEquals("a", "queued", "generating"))
        assertEquals("generating", repo.getRecording("a")!!.status)
        assertFalse(repo.updateStatusIfEquals("a", "queued", "generating"))
        assertFalse(repo.updateStatusIfEquals("b", "queued", "generating"))
        assertEquals("ready", repo.getRecording("b")!!.status)
    }
}
