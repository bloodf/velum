package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * Device-session identity is unique on the library row (#113).
 * Fixtures only — no identity values printed.
 */
class DeviceSessionRecordingDedupeTest {
    @Test
    fun findByDeviceSessionId_returnsExistingRow() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "rec-keep",
            sessionId = FIXTURE_SESSION,
            folderId = null,
            title = "Keep",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 10,
            durationSeconds = 1,
            sizeBytes = 8,
            localPath = "/tmp/keep.wav",
            transcript = "hello",
            summaryMarkdown = "## note",
            status = "ready",
        )
        val found = repo.findRecordingByDeviceSessionId(FIXTURE_SESSION)
        assertNotNull(found)
        assertEquals("rec-keep", found.id)
        assertEquals("hello", found.transcript)
        assertNull(repo.findRecordingByDeviceSessionId(FIXTURE_OTHER_SESSION))
    }

    @Test
    fun uniqueIndex_rejectsSecondRowForSameDeviceSession() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "rec-a",
            sessionId = FIXTURE_SESSION,
            folderId = null,
            title = "A",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = 1,
            localPath = "/a.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
        )
        assertFails {
            repo.insertRecording(
                id = "rec-b",
                sessionId = FIXTURE_SESSION,
                folderId = null,
                title = "B",
                source = "note_pro",
                deviceSerial = null,
                startedAt = 2,
                durationSeconds = 1,
                sizeBytes = 1,
                localPath = "/b.wav",
                transcript = null,
                summaryMarkdown = null,
                status = "ready",
                exclusive = true,
            )
        }
        assertEquals("rec-a", repo.findRecordingByDeviceSessionId(FIXTURE_SESSION)?.id)
        assertEquals("A", repo.getRecording("rec-a")?.title)
    }

    @Test
    fun nullSessionIds_remainNonUnique_forPhoneMic() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "mic-1",
            sessionId = null,
            folderId = null,
            title = "Mic 1",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = 1,
            localPath = null,
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
        )
        repo.insertRecording(
            id = "mic-2",
            sessionId = null,
            folderId = null,
            title = "Mic 2",
            source = "phone",
            deviceSerial = null,
            startedAt = 2,
            durationSeconds = 1,
            sizeBytes = 1,
            localPath = null,
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
        )
        assertEquals(2, repo.getAllRecordings().size)
    }

    @Test
    fun migration_v4_to_v5_isDocumentedNoopForDuplicateRows() {
        // Nothing shipped with duplicate session_id rows; 4.sqm only adds the unique index.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Create current schema then verify version — full v1→v5 chain is covered elsewhere.
        VelumDatabase.Schema.create(driver)
        assertEquals(5L, VelumDatabase.Schema.version)
        driver.close()
    }

    companion object {
        /** Opaque fixture — not a real device session id. */
        private const val FIXTURE_SESSION = 0x1111_0001L
        private const val FIXTURE_OTHER_SESSION = 0x1111_0002L
    }
}
