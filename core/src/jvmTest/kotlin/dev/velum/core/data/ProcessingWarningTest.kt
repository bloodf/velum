package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import dev.velum.core.model.ProcessingWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Stable-code warning column added in schema v6 (migration 5.sqm). */
class ProcessingWarningTest {
    private fun repo(): VelumRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        return VelumRepository(VelumDatabase(driver), driver)
    }

    private suspend fun VelumRepository.insert(id: String) = insertRecording(
        id = id, sessionId = 7, folderId = null, title = "t", source = "note_pro",
        deviceSerial = "SYNTH-SN-1", startedAt = 1, durationSeconds = 0, sizeBytes = 0,
        localPath = "/x/$id.ogg", transcript = null, summaryMarkdown = null, status = "ready",
    )

    @Test
    fun new_recordings_have_no_warning_and_it_can_be_set_and_cleared() = runTest {
        val repo = repo()
        repo.insert("rec-a")
        assertNull(repo.getRecording("rec-a")!!.processing_warning)
        repo.setProcessingWarning("rec-a", ProcessingWarning.TRUNCATED_AUDIO)
        assertEquals("truncated_audio", repo.getRecording("rec-a")!!.processing_warning)
        repo.setProcessingWarning("rec-a", null)
        assertNull(repo.getRecording("rec-a")!!.processing_warning)
    }

    @Test
    fun redownload_clears_a_stale_warning() = runTest {
        val repo = repo()
        repo.insert("rec-a")
        repo.setProcessingWarning("rec-a", ProcessingWarning.TRUNCATED_AUDIO)
        repo.replaceDeviceSessionAudio("rec-a", "/x/new.ogg", 99)
        assertNull(repo.getRecording("rec-a")!!.processing_warning)
        assertEquals("/x/new.ogg", repo.getRecording("rec-a")!!.local_path)
    }

    @Test
    fun migration_v5_to_v6_adds_nullable_column_and_keeps_rows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        // Shape a v5 database: the column does not exist yet.
        driver.execute(null, "ALTER TABLE recording DROP COLUMN processing_warning", 0)
        driver.execute(
            null,
            "INSERT INTO recording(id, title, started_at, created_at) VALUES ('rec-old', 'old', 1, 1)",
            0,
        )
        VelumDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = 6)
        val repo = VelumRepository(VelumDatabase(driver), driver)
        val row = repo.getRecording("rec-old")!!
        assertEquals("old", row.title)
        assertNull(row.processing_warning)
        repo.setProcessingWarning("rec-old", ProcessingWarning.TRUNCATED_AUDIO)
        assertEquals("truncated_audio", repo.getRecording("rec-old")!!.processing_warning)
    }
}
