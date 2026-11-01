package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** #113: one library row per (device serial, session id). Synthetic serials only. */
class DeviceSessionRecordingTest {
    private fun repo(): Pair<VelumRepository, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        return VelumRepository(VelumDatabase(driver), driver) to driver
    }

    private suspend fun VelumRepository.insertSession(id: String, sessionId: Long?, serial: String?, trashed: Long = 0) =
        insertRecording(
            id = id, sessionId = sessionId, folderId = null, title = "t", source = "note_pro",
            deviceSerial = serial, startedAt = 1, durationSeconds = 0, sizeBytes = 0,
            localPath = "/x/$id.ogg", transcript = null, summaryMarkdown = null, status = "ready", trashed = trashed,
        )

    @Test
    fun finds_row_by_session_and_serial_including_trashed_and_null_serial() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 1_700_000_000, "SYNTH-SN-1", trashed = 1)
        repo.insertSession("rec-b", 1_700_000_000, null)
        assertEquals("rec-a", repo.findDeviceSessionRecording(1_700_000_000, "SYNTH-SN-1")?.id)
        assertEquals("rec-b", repo.findDeviceSessionRecording(1_700_000_000, null)?.id)
        assertNull(repo.findDeviceSessionRecording(1_700_000_000, "SYNTH-SN-2"))
        assertNull(repo.findDeviceSessionRecording(1_700_000_001, "SYNTH-SN-1"))
    }

    @Test
    fun unique_index_rejects_second_row_for_same_session_and_does_not_replace_the_first() = runTest {
        val (repo, driver) = repo()
        repo.insertSession("rec-a", 42, "SYNTH-SN-1")
        assertFailsWith<Exception> {
            driver.execute(
                null,
                "INSERT INTO recording(id, session_id, title, device_serial, started_at, created_at) VALUES ('rec-dup', 42, 't', 'SYNTH-SN-1', 1, 1)",
                0,
            )
        }
        assertFailsWith<Exception> {
            driver.execute(null, "INSERT INTO recording(id, session_id, title, started_at, created_at) VALUES ('rec-n1', 7, 't', 1, 1)", 0)
            driver.execute(null, "INSERT INTO recording(id, session_id, title, started_at, created_at) VALUES ('rec-n2', 7, 't', 1, 1)", 0)
        }
        assertEquals("rec-a", repo.findDeviceSessionRecording(42, "SYNTH-SN-1")?.id)
        // Local recordings have no session id and stay unconstrained.
        repo.insertSession("rec-l1", null, null)
        repo.insertSession("rec-l2", null, null)
    }

    @Test
    fun replace_audio_keeps_user_edits_and_restores_from_trash() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 42, "SYNTH-SN-1", trashed = 1)
        repo.updateRecordingTitle("rec-a", "My edited title")
        repo.updateTranscript("rec-a", "kept transcript")
        repo.updateSummary("rec-a", "kept summary")
        repo.replaceDeviceSessionAudio("rec-a", "/x/rec-a.wav", 1234)
        val row = repo.getRecording("rec-a")!!
        assertEquals("My edited title", row.title)
        assertEquals("kept transcript", row.transcript)
        assertEquals("kept summary", row.summary_markdown)
        assertEquals("/x/rec-a.wav", row.local_path)
        assertEquals(1234, row.size_bytes)
        assertEquals(0, row.trashed)
    }

    @Test
    fun file_refs_cover_trashed_rows() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 1, "SYNTH-SN-1", trashed = 1)
        repo.insertSession("rec-b", null, null)
        assertEquals(
            setOf("rec-a" to "/x/rec-a.ogg", "rec-b" to "/x/rec-b.ogg"),
            repo.getAllRecordingFileRefs().toSet(),
        )
    }

    @Test
    fun migration_v4_to_v5_detaches_existing_duplicates_without_deleting_rows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        // Simulate a pre-#113 database: no unique index yet, duplicate re-downloads present.
        driver.execute(null, "DROP INDEX recording_device_session", 0)
        for ((id, created) in listOf("rec-new" to 300L, "rec-old" to 100L, "rec-mid" to 200L)) {
            driver.execute(
                null,
                "INSERT INTO recording(id, session_id, title, device_serial, started_at, local_path, created_at) " +
                    "VALUES ('$id', 42, '$id', 'SYNTH-SN-1', 1, '/x/$id.ogg', $created)",
                0,
            )
        }
        driver.execute(null, "INSERT INTO recording(id, session_id, title, started_at, created_at) VALUES ('rec-solo', 43, 's', 1, 1)", 0)
        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)
        val repo = VelumRepository(VelumDatabase(driver), driver)
        assertEquals(4, repo.getAllRecordings().size, "no row may be deleted")
        assertEquals("rec-old", repo.findDeviceSessionRecording(42, "SYNTH-SN-1")?.id, "oldest row keeps the session")
        assertNull(repo.getRecording("rec-new")!!.session_id)
        assertNull(repo.getRecording("rec-mid")!!.session_id)
        assertEquals("/x/rec-new.ogg", repo.getRecording("rec-new")!!.local_path, "detached rows keep their audio")
        assertEquals(43, repo.getRecording("rec-solo")!!.session_id)
        assertFailsWith<Exception> {
            driver.execute(null, "INSERT INTO recording(id, session_id, title, device_serial, started_at, created_at) VALUES ('rec-x', 42, 't', 'SYNTH-SN-1', 1, 1)", 0)
        }
    }
}
