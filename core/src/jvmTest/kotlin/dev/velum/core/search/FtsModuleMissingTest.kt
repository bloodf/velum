package dev.velum.core.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.VelumDatabase
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Downgrade / bundled-SQLite load failure: velum.db already holds FTS virtual tables, but the open
 * SQLite lacks their module. `CREATE VIRTUAL TABLE IF NOT EXISTS` then succeeds without touching the
 * module, so FTS must be probed, otherwise the first FTS access throws inside VelumRepository.<init>
 * and the app's container (and its open database) is abandoned. Simulated with an unknown module name.
 */
class FtsModuleMissingTest {
    private val dir: File = Files.createTempDirectory("velum-fts-missing").toFile()
    private val dbFile = File(dir, "velum.db")

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun dbWithUnloadableFtsTables() {
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.path}").use { d -> VelumDatabase.Schema.create(d) }
        // One raw connection: writable_schema is per connection.
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA writable_schema = ON")
                for ((name, cols) in listOf(
                    "recording_fts" to "recording_id, title, transcript, summary_markdown",
                    "template_fts" to "template_id, name, description, category, system_prompt, tags",
                )) {
                    st.execute(
                        "INSERT INTO sqlite_master(type, name, tbl_name, rootpage, sql) VALUES " +
                            "('table', '$name', '$name', 0, 'CREATE VIRTUAL TABLE $name USING velum_missing_module($cols)')",
                    )
                }
                st.execute("PRAGMA writable_schema = OFF")
            }
        }
    }

    @Test fun existing_fts_tables_without_their_module_report_unavailable() {
        dbWithUnloadableFtsTables()
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.path}").use { d ->
            val result = FtsIndex.ensureSchema(d)
            assertFalse(result.available)
            assertNotNull(result.failure)
        }
    }

    @Test fun repository_degrades_to_list_search_instead_of_failing_to_open() = runBlocking {
        dbWithUnloadableFtsTables()
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.path}").use { d ->
            val repo = VelumRepository(VelumDatabase(d), d)
            assertFalse(repo.ftsAvailable)
            repo.upsertTemplate("tpl-synthetic", "Synthetic", "meeting", "", "prompt")
            assertTrue(repo.searchTemplateIdsFts("synthetic*").isEmpty())
            assertEquals("Synthetic", repo.getTemplate("tpl-synthetic")?.name)
        }
    }
}
