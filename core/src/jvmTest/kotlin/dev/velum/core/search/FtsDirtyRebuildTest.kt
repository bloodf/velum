package dev.velum.core.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.VelumDatabase
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A launch without FTS (bundled SQLite failed to load) changes rows without touching the index. The
 * tables are non-empty, so the empty-side rebuild would not repair them: the fts_dirty flag must.
 * The missing module is simulated by failing the FTS DDL, which keeps the indexed rows.
 */
class FtsDirtyRebuildTest {
    // Shared-cache memory DB: the SQLDelight JDBC driver runs BEGIN/COMMIT on separate pooled connections
    // for file URLs, which breaks FtsIndex.inTransaction. The anchor connection keeps the database alive
    // across the three simulated launches.
    private val url = "jdbc:sqlite:file:velum_fts_dirty?mode=memory&cache=shared"
    private val anchor = DriverManager.getConnection(url)

    @AfterTest fun cleanup() { anchor.close() }

    /** Makes FtsIndex.ensureSchema fail like a SQLite without the fts5 module: its CREATE VIRTUAL TABLE throws. */
    private fun withoutFts(d: SqlDriver): SqlDriver = object : SqlDriver by d {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.contains("CREATE VIRTUAL TABLE")) throw IllegalStateException("no such module: fts5")
            return d.execute(identifier, sql, parameters, binders)
        }
    }

    private suspend fun VelumRepository.addRecording(id: String, transcript: String) = insertRecording(
        id = id, sessionId = null, folderId = null, title = "Synthetic", source = "phone",
        deviceSerial = null, startedAt = 1, durationSeconds = 0, sizeBytes = 0, localPath = null,
        transcript = transcript, summaryMarkdown = null, status = "ready",
    )

    @Test fun changes_made_without_fts_are_indexed_on_the_next_fts_launch() = runBlocking {
        JdbcSqliteDriver(url).use { d ->
            VelumDatabase.Schema.create(d)
            val repo = VelumRepository(VelumDatabase(d), d)
            assertTrue(repo.ftsAvailable)
            assertNull(repo.getMeta(VelumRepository.META_FTS_DIRTY))
            repo.addRecording("rec-old", "oldword")
        }

        JdbcSqliteDriver(url).use { real ->
            val d = withoutFts(real)
            val repo = VelumRepository(VelumDatabase(d), d)
            assertFalse(repo.ftsAvailable)
            assertEquals("1", repo.getMeta(VelumRepository.META_FTS_DIRTY))
            repo.addRecording("rec-new", "newword")
            repo.deleteRecordingHard("rec-old")
        }

        JdbcSqliteDriver(url).use { d ->
            val repo = VelumRepository(VelumDatabase(d), d)
            assertTrue(repo.ftsAvailable)
            assertEquals(listOf("rec-new"), repo.searchRecordingIdsFts("newword*"))
            assertEquals(emptyList(), repo.searchRecordingIdsFts("oldword*"))
            assertNull(repo.getMeta(VelumRepository.META_FTS_DIRTY))
        }
    }
}
