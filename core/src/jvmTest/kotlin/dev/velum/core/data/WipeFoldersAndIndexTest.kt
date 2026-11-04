package dev.velum.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #423: folder names do not survive a wipe, and a search index that could not be cleared is reported. Synthetic data. */
class WipeFoldersAndIndexTest {
    @Test fun a_user_folder_name_does_not_survive_a_wipe_but_the_seed_folders_return() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        handle.database.velumQueries.insertFolder("folder-secret", "Quarterly layoffs plan", 1)
        assertTrue(handle.database.velumQueries.selectAllFolders().executeAsList().any { it.name == "Quarterly layoffs plan" })

        repo.wipeAllUserData()

        val names = handle.database.velumQueries.selectAllFolders().executeAsList().map { it.name }
        assertFalse("Quarterly layoffs plan" in names, "user folder survived: $names")
        assertTrue("Inbox" in names, "the default folders are seeded again: $names")
    }

    /** [armed] is flipped on after construction: the repository itself writes the index while it opens. */
    private class Failing(val real: SqlDriver, val on: (String) -> Boolean) : SqlDriver by real {
        var armed = false
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) =
            if (armed && on(sql)) throw java.io.IOException("index write failed") else real.execute(identifier, sql, parameters, binders)
    }

    private fun repoFailing(on: (String) -> Boolean): Pair<VelumRepository, Failing> {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(real)
        val failing = Failing(real, on)
        return VelumRepository(VelumDatabase(failing), failing) to failing
    }

    @Test fun a_failed_index_delete_makes_the_wipe_report_failure() = runTest {
        val (repo, driver) = repoFailing { it.startsWith("DELETE FROM recording_fts WHERE") }
        repo.insertRecording(
            id = "rec-1", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null, startedAt = 1,
            durationSeconds = 0, sizeBytes = 0, localPath = null, transcript = "words", summaryMarkdown = null, status = "ready",
        )
        driver.armed = true
        assertFailsWith<IllegalStateException>("index text may remain: the wipe must say so") { repo.wipeAllUserData() }
        assertEquals(emptyList(), repo.getAllRecordings().map { it.id }, "the rows are gone before the throw")
    }

    @Test fun a_failed_index_rebuild_makes_the_wipe_report_failure() = runTest {
        val (repo, driver) = repoFailing { it == "DELETE FROM recording_fts" }
        driver.armed = true
        assertFailsWith<IllegalStateException> { repo.wipeAllUserData() }
    }

    @Test fun a_wipe_with_a_working_index_does_not_throw() = runTest {
        val (repo, _) = repoFailing { true } // never armed: nothing fails
        repo.wipeAllUserData()
    }
}
