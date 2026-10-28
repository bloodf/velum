package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Simulates a device upgrading from schema v3 (no summary_version) to v4 with
 * an existing recording that already has notes — notes must survive and become
 * an active summary_version row (no data loss).
 */
class SummaryVersionMigrationTest {
    @Test
    fun upgrade_v3_to_v4_preserves_summary_and_backfills_version() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Minimal v3 schema (pre-summary_version) matching migrations/README.
        // JDBC executes one statement per call — split the DDL.
        CREATE_V3_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 3", 0)
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-upgrade', NULL, NULL, 'Old meeting', 'note_pro', 'SN1',
              100, 60, 1000, NULL,
              'hello transcript', '## Prior notes
- keep me', 'ready', 0, 100
            )
            """.trimIndent(),
            0,
        )

        VelumDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
        val db = VelumDatabase(driver)
        val repo = VelumRepository(db, driver)

        val recording = repo.getRecording("rec-upgrade")
        assertNotNull(recording)
        assertEquals("## Prior notes\n- keep me", recording.summary_markdown)
        assertEquals("hello transcript", recording.transcript)

        val versions = repo.getSummaryVersions("rec-upgrade")
        assertEquals(1, versions.size, "v3→v4 must backfill an active version from existing notes")
        assertEquals(1L, versions.single().is_active)
        assertEquals("## Prior notes\n- keep me", versions.single().markdown)
        assertTrue(db.velumQueries.countVersionsForRecording("rec-upgrade").executeAsOne() >= 1L)
    }

    companion object {
        // Columns match databases/3.db dump (before summary_version).
        private val CREATE_V3_DDL = """
            CREATE TABLE folder (
              id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE recording (
              id TEXT NOT NULL PRIMARY KEY,
              session_id INTEGER,
              folder_id TEXT,
              title TEXT NOT NULL,
              source TEXT NOT NULL DEFAULT 'local',
              device_serial TEXT,
              started_at INTEGER NOT NULL,
              duration_seconds INTEGER NOT NULL DEFAULT 0,
              size_bytes INTEGER NOT NULL DEFAULT 0,
              local_path TEXT,
              transcript TEXT,
              summary_markdown TEXT,
              status TEXT NOT NULL DEFAULT 'ready',
              trashed INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE speaker (
              id TEXT NOT NULL PRIMARY KEY,
              recording_id TEXT NOT NULL,
              label TEXT NOT NULL,
              display_name TEXT,
              talk_seconds INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE template (
              id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              category TEXT NOT NULL,
              description TEXT NOT NULL,
              author TEXT NOT NULL DEFAULT 'Velum',
              system_prompt TEXT NOT NULL,
              uses INTEGER NOT NULL DEFAULT 0,
              builtin INTEGER NOT NULL DEFAULT 1,
              created_at INTEGER NOT NULL,
              featured INTEGER NOT NULL DEFAULT 1,
              tags TEXT NOT NULL DEFAULT '',
              lang TEXT NOT NULL DEFAULT 'und',
              preferred_model TEXT,
              preferred_endpoint_profile_id TEXT
            );
            CREATE TABLE autoflow (
              id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1,
              wake_phrase TEXT,
              duration_min_seconds INTEGER NOT NULL DEFAULT 0,
              duration_max_seconds INTEGER NOT NULL DEFAULT 10800,
              source_filter TEXT NOT NULL DEFAULT 'all',
              template_id TEXT,
              auto_label_voice INTEGER NOT NULL DEFAULT 0,
              language TEXT NOT NULL DEFAULT 'auto',
              notify INTEGER NOT NULL DEFAULT 1,
              post_action TEXT,
              model_id TEXT,
              endpoint_profile_id TEXT
            );
            CREATE TABLE memory_item (
              id TEXT NOT NULL PRIMARY KEY,
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE glossary_term (
              id TEXT NOT NULL PRIMARY KEY,
              term TEXT NOT NULL,
              definition TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL
            );
            CREATE TABLE app_meta (
              key TEXT NOT NULL PRIMARY KEY,
              value TEXT NOT NULL
            );
        """.trimIndent()
    }
}
