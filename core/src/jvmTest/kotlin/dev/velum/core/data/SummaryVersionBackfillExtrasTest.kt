package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round-2 extras: trashed rows must backfill versions; version ids must not collide
 * across many recordings in the same second (INSERT OR REPLACE safety).
 */
class SummaryVersionBackfillExtrasTest {
    @Test
    fun backfill_includes_trashed_recordings_with_notes() = runTest {
        val driver = openV3()
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-trashed', NULL, NULL, 'Trashed', 'note_pro', 'SN1',
              100, 60, 1000, NULL,
              't', '## Trashed notes', 'ready', 1, 100
            )
            """.trimIndent(),
            0,
        )

        VelumDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
        val db = VelumDatabase(driver)
        VelumRepository(db, driver)

        val versions = db.velumQueries.selectVersionsForRecording("rec-trashed").executeAsList()
        assertEquals(1, versions.size, "trashed notes must get a summary_version on upgrade")
        assertEquals("## Trashed notes", versions.single().markdown)
    }

    @Test
    fun backfill_many_recordings_keeps_distinct_version_ids() = runTest {
        val driver = openV3()
        // 80 rows in one second — old 4-digit id space collided often.
        repeat(80) { i ->
            driver.execute(
                null,
                """
                INSERT INTO recording(
                  id, session_id, folder_id, title, source, device_serial,
                  started_at, duration_seconds, size_bytes, local_path,
                  transcript, summary_markdown, status, trashed, created_at
                ) VALUES (
                  'rec-$i', NULL, NULL, 'R$i', 'phone', NULL,
                  100, 1, 1, NULL,
                  't', '## N$i', 'ready', 0, 100
                )
                """.trimIndent(),
                0,
            )
        }

        VelumDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
        val db = VelumDatabase(driver)
        VelumRepository(db, driver)

        val ids = (0 until 80).flatMap { i ->
            db.velumQueries.selectVersionsForRecording("rec-$i").executeAsList().map { it.id }
        }
        assertEquals(80, ids.size)
        assertEquals(80, ids.toSet().size, "version ids must be unique across recordings")
        assertTrue(ids.all { it.startsWith("sv-rec-") })
    }

    private fun openV3(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CREATE_V3_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 3", 0)
        return driver
    }

    companion object {
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
