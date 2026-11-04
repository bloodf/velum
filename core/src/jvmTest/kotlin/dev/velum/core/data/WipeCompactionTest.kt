package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #423: deleted text must not stay readable in the database files after a wipe. Synthetic marker only. */
class WipeCompactionTest {
    private val marker = "zq7wipemarker-synthetic"

    private fun filesContaining(dir: File): List<String> =
        dir.listFiles().orEmpty().filter { f -> f.isFile && String(f.readBytes(), Charsets.ISO_8859_1).contains(marker) }.map { it.name }

    /**
     * A file-backed JdbcSqliteDriver gives each statement its own connection, so the raw BEGIN/COMMIT the FTS upgrade
     * path uses cannot work here (the app's drivers are single-connection). Create the current shape up front.
     */
    private fun presetTemplateFts(driver: app.cash.sqldelight.db.SqlDriver) {
        driver.execute(
            null,
            "CREATE VIRTUAL TABLE IF NOT EXISTS template_fts USING fts5(template_id UNINDEXED, name, description, category, system_prompt, tags, tokenize = 'porter unicode61')",
            0,
        )
    }

    private fun withDb(wal: Boolean, block: suspend (VelumRepository, File) -> Unit) {
        val dir = Files.createTempDirectory("velum-wipe").toFile()
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "velum.db").path}")
            VelumDatabase.Schema.create(driver)
            presetTemplateFts(driver)
            if (wal) driver.execute(null, "PRAGMA journal_mode=WAL", 0)
            val repo = VelumRepository(VelumDatabase(driver), driver)
            runBlocking { block(repo, dir) }
            driver.close()
        } finally { dir.deleteRecursively() }
    }

    private suspend fun VelumRepository.seed() {
        assertTrue(ftsAvailable, "FTS unavailable: $ftsFailure")
        repeat(40) { i ->
            insertRecording(
                id = "rec-$i", sessionId = null, folderId = null, title = "title $marker $i", source = "phone",
                deviceSerial = null, startedAt = 1, durationSeconds = 0, sizeBytes = 0, localPath = null,
                transcript = "transcript $marker $i ".repeat(20), summaryMarkdown = "summary $marker $i", status = "ready",
            )
        }
    }

    private fun check(wal: Boolean, compact: Boolean) = withDb(wal) { repo, dir ->
        repo.seed()
        assertTrue(filesContaining(dir).isNotEmpty(), "the marker must be on disk before the wipe")
        repo.wipeAllUserData()
        if (compact) assertTrue(repo.compactAfterWipe())
        val left = filesContaining(dir)
        if (compact) assertEquals(emptyList(), left, "marker left in $left (wal=$wal)")
        else assertTrue(left.isNotEmpty(), "control: without compaction the marker stays (wal=$wal)")
    }

    @Test fun rollback_journal_marker_is_gone_after_wipe_and_compaction() = check(wal = false, compact = true)
    @Test fun wal_marker_is_gone_after_wipe_and_compaction() = check(wal = true, compact = true)
    @Test fun control_rollback_journal_keeps_the_marker_without_compaction() = check(wal = false, compact = false)
    @Test fun control_wal_keeps_the_marker_without_compaction() = check(wal = true, compact = false)

    @Test fun a_failing_compaction_reports_false_and_throws_nothing() {
        val dir = Files.createTempDirectory("velum-wipe").toFile()
        try {
            val real = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "velum.db").path}")
            VelumDatabase.Schema.create(real)
            presetTemplateFts(real)
            // Like a full disk or a locked database: VACUUM throws.
            val failing = object : app.cash.sqldelight.db.SqlDriver by real {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?) =
                    if (sql == "VACUUM") throw java.io.IOException("disk full") else real.execute(identifier, sql, parameters, binders)
            }
            val repo = VelumRepository(VelumDatabase(failing), failing)
            assertFalse(runBlocking { repo.compactAfterWipe() })
            real.close()
        } finally { dir.deleteRecursively() }
    }
}
