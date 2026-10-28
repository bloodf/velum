package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round-3: migrate a realistic v1 library (recording + notes + template + autoflow
 * + speaker + glossary) through every shipped step (1→2→3→4) and assert no data loss.
 */
class ChainMigrationRealisticDataTest {
    @Test
    fun upgrade_v1_to_v4_preserves_library_and_backfills_versions() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CREATE_V1_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 1", 0)

        driver.execute(
            null,
            """
            INSERT INTO folder(id, name, created_at) VALUES ('fld-1', 'Work', 50)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO template(
              id, name, category, description, author, system_prompt, uses, builtin, created_at
            ) VALUES (
              'tpl-meeting', 'Meeting notes', 'work', 'Classic notes', 'Velum',
              'Summarize the meeting.', 3, 0, 60
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO autoflow(
              id, name, enabled, wake_phrase, duration_min_seconds, duration_max_seconds,
              source_filter, template_id, auto_label_voice, language, notify, post_action
            ) VALUES (
              'af-1', 'Standup', 1, 'standup', 30, 1800,
              'all', 'tpl-meeting', 1, 'pt-BR', 1, NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-v1', 42, 'fld-1', 'Sprint planning', 'note_pro', 'SN-OLD',
              100, 600, 2048, '/data/rec-v1.opus',
              'Alice: lets plan the sprint
Bob: ok', '## Sprint planning
- Keep the backlog groomed', 'ready', 0, 100
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO speaker(id, recording_id, label, display_name, talk_seconds)
            VALUES ('spk-a', 'rec-v1', 'S1', 'Alice', 120)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO glossary_term(id, term, definition, created_at)
            VALUES ('g1', 'Velum', 'Local-first notes app', 70)
            """.trimIndent(),
            0,
        )

        VelumDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 4)
        // Mark seed current so maybeSeed does not wipe/replace library rows under test.
        driver.execute(
            null,
            "INSERT OR REPLACE INTO app_meta(key, value) VALUES ('demo_seed_version', '10')",
            0,
        )
        val db = VelumDatabase(driver)
        val repo = VelumRepository(db, driver)

        val recording = repo.getRecording("rec-v1")
        assertNotNull(recording)
        assertEquals("Sprint planning", recording.title)
        assertEquals("Alice: lets plan the sprint\nBob: ok", recording.transcript)
        assertEquals("## Sprint planning\n- Keep the backlog groomed", recording.summary_markdown)
        assertEquals("fld-1", recording.folder_id)
        assertEquals("/data/rec-v1.opus", recording.local_path)

        val versions = repo.getSummaryVersions("rec-v1")
        assertEquals(1, versions.size, "v1→v4 must backfill active summary_version")
        assertEquals(1L, versions.single().is_active)

        val template = db.velumQueries.selectTemplateById("tpl-meeting").executeAsOne()
        assertEquals("Meeting notes", template.name)
        assertEquals(1L, template.featured)
        assertEquals("", template.tags)
        assertEquals("und", template.lang)

        val flow = db.velumQueries.selectAutoflows().executeAsList().single { it.id == "af-1" }
        assertEquals("Standup", flow.name)
        assertEquals("tpl-meeting", flow.template_id)
        assertEquals(null, flow.model_id)
        assertEquals(null, flow.endpoint_profile_id)

        val speakers = repo.getSpeakers("rec-v1")
        assertEquals(1, speakers.size)
        assertEquals("Alice", speakers.single().display_name)

        val glossary = db.velumQueries.selectGlossary().executeAsList()
        assertTrue(glossary.any { it.term == "Velum" })

        assertEquals(4, VelumDatabase.Schema.version)
    }

    companion object {
        /** Pre-featured template / pre-model autoflow — matches databases/1.db. */
        private val CREATE_V1_DDL = """
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
              created_at INTEGER NOT NULL
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
              post_action TEXT
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
