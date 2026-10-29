package dev.velum.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Schema v4 → v5 (#113): duplicate device-session rows must be collapsed before
 * the unique index is created, or upgrade crashes for anyone who re-downloaded.
 *
 * Fixtures only — identity values are never printed.
 */
class DeviceSessionDedupeMigrationTest {
    @Test
    fun upgrade_v4_to_v5_dedupesDuplicates_repointsChildren_keepsTranscriptOnDuplicate() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CREATE_V4_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 4", 0)

        // Older row: no transcript/summary, has a speaker + summary_version child.
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-keep-old', $FIXTURE_SESSION, 'fld-1', 'Old', 'note_pro', NULL,
              10, 0, 10, '/old.wav',
              NULL, NULL, 'ready', 0, 10
            )
            """.trimIndent(),
            0,
        )
        // Newer duplicate: holds the only transcript + summary — must survive.
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-dup-notes', $FIXTURE_SESSION, NULL, 'Dup', 'note_pro', NULL,
              20, 0, 20, '/dup.wav',
              'only-on-duplicate', '## only-on-duplicate', 'ready', 0, 20
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO speaker(id, recording_id, label, display_name, talk_seconds)
            VALUES ('spk-on-old', 'rec-keep-old', 'A', 'Alice', 5)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO speaker(id, recording_id, label, display_name, talk_seconds)
            VALUES ('spk-on-dup', 'rec-dup-notes', 'B', 'Bob', 3)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO summary_version(
              id, recording_id, template_id, template_name, markdown, created_at, is_active
            ) VALUES (
              'ver-on-old', 'rec-keep-old', NULL, 't', '## old ver', 10, 1
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO summary_version(
              id, recording_id, template_id, template_name, markdown, created_at, is_active
            ) VALUES (
              'ver-on-dup', 'rec-dup-notes', NULL, 't', '## dup ver', 20, 0
            )
            """.trimIndent(),
            0,
        )

        // Must not throw (unique index after dedupe).
        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)
        assertEquals(5L, VelumDatabase.Schema.version)

        val db = VelumDatabase(driver)
        val repo = VelumRepository(db, driver)
        val rows = repo.getAllRecordings().filter { it.session_id == FIXTURE_SESSION }
        assertEquals(1, rows.size, "exactly one row per device session after migration")

        // Keeper prefers non-trashed with transcript/summary → rec-dup-notes wins.
        val kept = rows.single()
        assertEquals("rec-dup-notes", kept.id)
        assertEquals("only-on-duplicate", kept.transcript)
        assertEquals("## only-on-duplicate", kept.summary_markdown)

        // Children that lived on the dropped row are re-pointed to the keeper.
        val speakers = repo.getSpeakers("rec-dup-notes")
        assertEquals(2, speakers.size, "both speakers must point at the kept row")
        assertTrue(speakers.any { it.id == "spk-on-old" })
        assertTrue(speakers.any { it.id == "spk-on-dup" })

        val versions = repo.getSummaryVersions("rec-dup-notes")
        assertEquals(2, versions.size, "both summary versions must point at the kept row")
        assertTrue(versions.any { it.id == "ver-on-old" })
        assertTrue(versions.any { it.id == "ver-on-dup" })

        assertNull(repo.getRecording("rec-keep-old"))

        // Index rejects a second live row for the same session.
        assertFails {
            repo.insertRecording(
                id = "rec-again",
                sessionId = FIXTURE_SESSION,
                folderId = null,
                title = "X",
                source = "note_pro",
                deviceSerial = null,
                startedAt = 30,
                durationSeconds = 0,
                sizeBytes = 0,
                localPath = null,
                transcript = null,
                summaryMarkdown = null,
                status = "ready",
                exclusive = true,
            )
        }
        driver.close()
    }

    @Test
    fun upgrade_v4_to_v5_mergesNotesOntoLiveKeeperFromTrashedDuplicate() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CREATE_V4_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 4", 0)

        // Live oldest has no notes; trashed duplicate holds transcript/summary.
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              'rec-live', $FIXTURE_SESSION_B, NULL, 'Live', 'note_pro', NULL,
              1, 0, 1, '/live.wav', NULL, NULL, 'ready', 0, 1
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
              'rec-trash-notes', $FIXTURE_SESSION_B, NULL, 'Trash', 'note_pro', NULL,
              2, 0, 2, '/trash.wav', 'from-trash', '## from-trash', 'ready', 1, 2
            )
            """.trimIndent(),
            0,
        )

        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val db = VelumDatabase(driver)
        val repo = VelumRepository(db, driver)
        // getAllRecordings filters trashed=0; keeper should be live with merged notes.
        val kept = repo.getAllRecordings().single { it.session_id == FIXTURE_SESSION_B }
        assertEquals("rec-live", kept.id)
        assertEquals("from-trash", kept.transcript)
        assertEquals("## from-trash", kept.summary_markdown)
        assertNull(repo.getRecording("rec-trash-notes"))
        driver.close()
    }

    companion object {
        private const val FIXTURE_SESSION = 0x4444_0001L
        private const val FIXTURE_SESSION_B = 0x4444_0002L

        /** Schema at version 4 (summary_version present, no device-session unique index). */
        private val CREATE_V4_DDL = """
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
            CREATE TABLE summary_version (
              id TEXT NOT NULL PRIMARY KEY,
              recording_id TEXT NOT NULL,
              template_id TEXT,
              template_name TEXT NOT NULL DEFAULT '',
              markdown TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              is_active INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
    }
}
