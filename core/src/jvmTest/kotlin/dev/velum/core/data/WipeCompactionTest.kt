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
    // FTS5 stores stemmed tokens split at punctuation, so a hyphenated marker never appears in the index. These are single
    // lowercase tokens that porter leaves alone, each with its own first letters so prefix compression cannot hide them.
    private val titleWord = "qxtitlezork"
    private val summaryWord = "wvsummaryjork"
    private val transcriptWord = "mnscriptfork"
    private val templateWord = "ztemplatebork"
    private val markers = listOf(titleWord, summaryWord, transcriptWord, templateWord)

    /** Which markers can still be read in which database file. */
    private fun leftIn(dir: File): List<String> = dir.listFiles().orEmpty().filter { it.isFile }.flatMap { f ->
        val text = String(f.readBytes(), Charsets.ISO_8859_1)
        markers.filter { text.contains(it) }.map { "${f.name}:$it" }
    }

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
                id = "rec-$i", sessionId = null, folderId = null, title = "title $titleWord $i", source = "phone",
                deviceSerial = null, startedAt = 1, durationSeconds = 0, sizeBytes = 0, localPath = null,
                transcript = "transcript $transcriptWord $i ".repeat(20), summaryMarkdown = "summary $summaryWord $i", status = "ready",
            )
        }
        upsertTemplate(id = "tpl-secret", name = "name $templateWord", category = "General", description = "d", systemPrompt = "p")
    }

    /** The FTS shadow tables only, read straight from SQLite: proves the words are (or are not) in the index itself. */
    private fun ftsHas(dir: File, word: String): Boolean = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "velum.db").path}").use { d ->
        d.executeQuery(null, "SELECT COUNT(*) FROM recording_fts_data WHERE instr(CAST(block AS TEXT), '$word') > 0", { c ->
            c.next(); app.cash.sqldelight.db.QueryResult.Value((c.getLong(0) ?: 0L) > 0L)
        }, 0).value
    }

    private fun check(wal: Boolean, compact: Boolean) = withDb(wal) { repo, dir ->
        repo.seed()
        val before = leftIn(dir)
        for (m in markers) assertTrue(before.any { it.endsWith(":$m") }, "marker $m must be on disk before the wipe: $before")
        assertTrue(repo.wipeAllUserData())
        if (compact) assertTrue(repo.compactAfterWipe())
        val left = leftIn(dir)
        if (compact) assertEquals(emptyList(), left, "markers left (wal=$wal)")
        else assertTrue(left.isNotEmpty(), "control: without compaction the markers stay (wal=$wal)")
    }

    @Test fun rollback_journal_markers_are_gone_after_wipe_and_compaction() = check(wal = false, compact = true)
    @Test fun wal_markers_are_gone_after_wipe_and_compaction() = check(wal = true, compact = true)
    @Test fun control_rollback_journal_keeps_the_markers_without_compaction() = check(wal = false, compact = false)
    @Test fun control_wal_keeps_the_markers_without_compaction() = check(wal = true, compact = false)

    @Test fun a_plain_vacuum_leaves_deleted_words_in_the_fts_index_and_the_merge_removes_them() = withDb(wal = false) { repo, dir ->
        repo.seed()
        assertTrue(ftsHas(dir, titleWord) && ftsHas(dir, summaryWord) && ftsHas(dir, transcriptWord), "words are in the FTS data")
        assertTrue(repo.wipeAllUserData())
        JdbcSqliteDriver("jdbc:sqlite:${File(dir, "velum.db").path}").use { it.execute(null, "VACUUM", 0) }
        assertTrue(listOf(titleWord, summaryWord, transcriptWord).any { ftsHas(dir, it) }, "VACUUM alone keeps FTS words (the bug)")
        assertTrue(repo.compactAfterWipe())
        assertEquals(emptyList(), leftIn(dir))
    }

    @Test fun a_checkpoint_that_could_not_empty_the_wal_reports_false() = withDb(wal = true) { repo, dir ->
        repo.seed()
        assertTrue(repo.wipeAllUserData())
        // A second reader keeps its snapshot open, so the truncating checkpoint comes back busy and the WAL keeps old pages.
        val other = java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "velum.db").path}")
        try {
            other.autoCommit = false
            val rs = other.createStatement().executeQuery("SELECT COUNT(*) FROM recording")
            rs.next()
            assertFalse(repo.compactAfterWipe(), "busy checkpoint must not be reported as done")
            rs.close()
        } finally {
            other.rollback(); other.close()
        }
        assertTrue(repo.compactAfterWipe(), "once the reader is gone the same call succeeds")
    }

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
