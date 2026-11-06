package dev.velum.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.Recording
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** #208: a failed index write must not drop the device-session row (its audio is already on disk). */
class DeviceSessionIndexFailureTest {
    private fun recording(id: String) = Recording(
        id = id, session_id = 7, folder_id = null, title = "Synthetic", source = "note_pro",
        device_serial = "SN1", started_at = 1, duration_seconds = 0, size_bytes = 1, local_path = "/x",
        transcript = null, summary_markdown = null, status = "recording", trashed = 0, created_at = 1,
        processing_warning = null,
    )

    @Test
    fun indexFailure_keepsTheRow_andFlagsTheIndexStale() = runBlocking {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(real)
        var failIndex = false
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (failIndex && sql.contains("INSERT INTO recording_fts")) throw IllegalStateException("index write failed")
                return real.execute(identifier, sql, parameters, binders)
            }
        }
        try {
            val repo = VelumRepository(VelumDatabase(driver), driver)
            assertNull(repo.getMeta(VelumRepository.META_FTS_DIRTY))
            failIndex = true
            repo.insertDeviceSessionRecording(
                Recording(
                    id = "rec-dev-1", session_id = 7, folder_id = null, title = "Synthetic", source = "note_pro",
                    device_serial = "SN1", started_at = 1, duration_seconds = 0, size_bytes = 1, local_path = "/x",
                    transcript = null, summary_markdown = null, status = "queued", trashed = 0, created_at = 1,
                    processing_warning = null,
                ),
            )
            assertNotNull(repo.getRecording("rec-dev-1"))
            assertEquals("1", repo.getMeta(VelumRepository.META_FTS_DIRTY))
        } finally {
            real.close()
        }
    }

    /** Text the user cannot re-create (live transcript on Stop, a finished transcript) must survive a failed index write. */
    @Test
    fun failedIndexWrite_neverRollsBackTheRowUpdate_andFlagsTheIndex() = runBlocking {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(real)
        var failIndex = false
        var failures = 0
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (failIndex && (sql.contains("recording_fts") || sql.contains("template_fts"))) throw IllegalStateException("index write failed")
                return real.execute(identifier, sql, parameters, binders)
            }
        }
        try {
            val repo = VelumRepository(VelumDatabase(driver), driver)
            repo.onIndexFailure = { failures++ }
            repo.insertDeviceSessionRecording(recording("rec-1"))
            assertNull(repo.getMeta(VelumRepository.META_FTS_DIRTY))
            failIndex = true

            repo.updateTranscript("rec-1", "live text")
            assertEquals("live text", repo.getRecording("rec-1")?.transcript)
            assertEquals("1", repo.getMeta(VelumRepository.META_FTS_DIRTY))

            repo.updateSummary("rec-1", "## summary")
            assertEquals("## summary", repo.getRecording("rec-1")?.summary_markdown)
            repo.updateRecordingTitle("rec-1", "New title")
            assertEquals("New title", repo.getRecording("rec-1")?.title)
            repo.upsertTemplate("tpl-1", "Name", "meeting", "", "prompt")
            assertEquals("Name", repo.getTemplate("tpl-1")?.name)
            repo.deleteTemplate("tpl-1")
            assertNull(repo.getTemplate("tpl-1"))
            repo.deleteRecordingHard("rec-1")
            assertNull(repo.getRecording("rec-1"))
            assertEquals(6, failures)
        } finally {
            real.close()
        }
    }

    /** Generated summaries, version switches/deletes, processing checkpoints and a wipe all commit even if the index fails (the wipe then throws once its rows are gone). */
    @Test
    fun summaryVersions_checkpoints_andWipe_survivedAFailingIndex() = runBlocking {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(real)
        var failIndex = false
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (failIndex && (sql.contains("recording_fts") || sql.contains("template_fts"))) throw IllegalStateException("index write failed")
                return real.execute(identifier, sql, parameters, binders)
            }
        }
        try {
            val repo = VelumRepository(VelumDatabase(driver), driver)
            repo.insertDeviceSessionRecording(recording("rec-1"))
            failIndex = true

            val v1 = repo.saveSummaryVersion("rec-1", "## first", null, "")
            val v2 = repo.saveSummaryVersion("rec-1", "## second", null, "")
            assertEquals("## second", repo.getRecording("rec-1")?.summary_markdown)
            repo.activateSummaryVersion("rec-1", v1)
            assertEquals("## first", repo.getRecording("rec-1")?.summary_markdown)
            repo.deleteSummaryVersion("rec-1", v1)
            assertEquals("## second", repo.getRecording("rec-1")?.summary_markdown)
            assertEquals(listOf(v2), repo.getSummaryVersions("rec-1").map { it.id }.filter { it == v2 })
            repo.updateProcessingCheckpoint("rec-1", "ready", transcript = "final text")
            assertEquals("final text", repo.getRecording("rec-1")?.transcript)
            assertEquals("1", repo.getMeta(VelumRepository.META_FTS_DIRTY))

            // The rows are deleted regardless; the wipe then says the index could not be cleared (#423).
            assertFailsWith<IllegalStateException> { repo.wipeAllUserData() }
            assertNull(repo.getRecording("rec-1"))
        } finally {
            real.close()
        }
    }
}
