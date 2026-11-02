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
    fun finds_row_by_session_and_serial_including_trashed() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 1_700_000_000, "SYNTH-SN-1", trashed = 1)
        assertEquals("rec-a", repo.findDeviceSessionRecording(1_700_000_000, "SYNTH-SN-1")?.id)
        assertNull(repo.findDeviceSessionRecording(1_700_000_000, "SYNTH-SN-2"))
        assertNull(repo.findDeviceSessionRecording(1_700_000_001, "SYNTH-SN-1"))
    }

    @Test
    fun unknown_serial_disables_dedup_so_devices_never_merge() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 42, null)
        repo.insertSession("rec-b", 42, null)
        assertNull(repo.findDeviceSessionRecording(42, null))
        assertEquals(2, repo.getAllRecordings().size)
    }

    @Test
    fun library_session_ids_are_scoped_to_the_serial() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", 1, "SYNTH-SN-1")
        repo.insertSession("rec-b", 2, "SYNTH-SN-2")
        repo.insertSession("rec-c", 3, null)
        assertEquals(setOf(1L), repo.librarySessionIds(listOf(1, 2, 3, 4), "SYNTH-SN-1"))
        assertEquals(emptySet(), repo.librarySessionIds(listOf(3), null))
    }

    @Test
    fun library_session_ids_handle_more_ids_than_the_sqlite_parameter_limit() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-first", 1, "SYNTH-SN-1")
        repo.insertSession("rec-mid", 1_200, "SYNTH-SN-1")
        repo.insertSession("rec-last", 40_000, "SYNTH-SN-1")
        // 1200 exceeds Android's 999 limit (API 26-29); 40000 also exceeds the JDBC driver's 32766, so an
        // unchunked IN list fails on the test host too.
        assertEquals(setOf(1L, 1_200L), repo.librarySessionIds((1L..1_200L).toList(), "SYNTH-SN-1"))
        assertEquals(setOf(1L, 1_200L, 40_000L), repo.librarySessionIds((1L..40_000L).toList(), "SYNTH-SN-1"))
    }

    @Test
    fun insert_recording_with_existing_id_fails_instead_of_replacing() = runTest {
        val (repo, _) = repo()
        repo.insertSession("rec-a", null, null)
        repo.updateTranscript("rec-a", "keep")
        assertFailsWith<Exception> { repo.insertSession("rec-a", null, null) }
        assertEquals("keep", repo.getRecording("rec-a")!!.transcript)
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
        assertFailsWith<Exception> { repo.insertSession("rec-dup2", 42, "SYNTH-SN-1") }
        assertEquals("rec-a", repo.findDeviceSessionRecording(42, "SYNTH-SN-1")?.id)
        assertEquals(1, repo.getAllRecordings().size)
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

    private fun preIndexDb(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        // Simulate a pre-#113 database: no unique index yet, duplicate re-downloads present.
        driver.execute(null, "DROP INDEX recording_device_session", 0)
        return driver
    }

    private fun JdbcSqliteDriver.row(id: String, sessionId: Long, serial: String?, created: Long, trashed: Int = 0, transcript: String? = null) =
        execute(
            null,
            "INSERT INTO recording(id, session_id, title, device_serial, started_at, local_path, transcript, trashed, created_at) " +
                "VALUES ('$id', $sessionId, '$id', ${serial?.let { "'$it'" } ?: "NULL"}, 1, '/x/$id.ogg', " +
                "${transcript?.let { "'$it'" } ?: "NULL"}, $trashed, $created)",
            0,
        )

    @Test
    fun migration_survivor_prefers_untrashed_then_processed_then_oldest() = runTest {
        val driver = preIndexDb()
        driver.row("rec-oldest-trashed", 42, "SYNTH-SN-1", created = 100, trashed = 1, transcript = "t")
        driver.row("rec-plain", 42, "SYNTH-SN-1", created = 150)
        driver.row("rec-processed", 42, "SYNTH-SN-1", created = 200, transcript = "notes")
        // Same session, unknown serial: never merged.
        driver.row("rec-null-1", 42, null, created = 50)
        driver.row("rec-null-2", 42, null, created = 60)
        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)
        val repo = VelumRepository(VelumDatabase(driver), driver)
        assertEquals("rec-processed", repo.findDeviceSessionRecording(42, "SYNTH-SN-1")?.id)
        assertNull(repo.getRecording("rec-oldest-trashed")!!.session_id)
        assertNull(repo.getRecording("rec-plain")!!.session_id)
        assertEquals(42, repo.getRecording("rec-null-1")!!.session_id)
        assertEquals(42, repo.getRecording("rec-null-2")!!.session_id)
        assertEquals(5, repo.getAllRecordingFileRefs().size, "no row may be deleted (trashed included)")
    }

    @Test
    fun migration_v4_to_v5_detaches_existing_duplicates_without_deleting_rows() = runTest {
        val driver = preIndexDb()
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
