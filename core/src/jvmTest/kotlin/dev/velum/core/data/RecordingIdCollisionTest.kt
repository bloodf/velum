package dev.velum.core.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingIdCollisionTest {
    @Test
    fun newId_usesWideRandomSpace_noCollisionsIn2k() {
        val ids = (0 until 2_000).map {
            RecordingIds.newId(nowEpochSeconds = 1_700_000_000L, random = Random.Default)
        }.toSet()
        assertEquals(2_000, ids.size)
        assertTrue(ids.all { it.matches(Regex("""rec-\d+-\d{10}""")) })
    }

    @Test
    fun insert_withCollidingShortId_failsAndKeepsOriginal() = kotlinx.coroutines.runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val shortId = "rec-1700000000-1234"
        repo.insertRecording(
            id = shortId,
            sessionId = null,
            folderId = null,
            title = "Keep me",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = 1,
            localPath = "/a.wav",
            transcript = "original",
            summaryMarkdown = null,
            status = "ready",
        )
        // Plain INSERT (#113): a colliding id fails instead of clobbering the existing row.
        assertFailsWith<Exception> {
            repo.insertRecording(
                id = shortId,
                sessionId = null,
                folderId = null,
                title = "Clobber",
                source = "phone",
                deviceSerial = null,
                startedAt = 2,
                durationSeconds = 1,
                sizeBytes = 1,
                localPath = "/b.wav",
                transcript = "gone",
                summaryMarkdown = null,
                status = "ready",
            )
        }
        assertEquals("Keep me", repo.getRecording(shortId)?.title)
        assertEquals("original", repo.getRecording(shortId)?.transcript)
    }
}
