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
    fun upgrade_v4_to_v5_dedupesDuplicates_keepsTranscriptSourceSpeakers_oneActiveVersion() =
        runBlocking {
            val driver = openV4()

            // Older row: no transcript; speakers must be dropped (not the transcript source).
            insertRecording(
                driver,
                id = "rec-keep-old",
                sessionId = FIXTURE_SESSION,
                folderId = "fld-1",
                title = "Old",
                path = "/old.wav",
                transcript = null,
                summary = null,
                trashed = 0,
                createdAt = 10,
            )
            // Newer duplicate: only transcript — becomes keeper + transcript source.
            insertRecording(
                driver,
                id = "rec-dup-notes",
                sessionId = FIXTURE_SESSION,
                folderId = null,
                title = "Dup",
                path = "/dup.wav",
                transcript = "only-on-duplicate",
                summary = "## only-on-duplicate",
                trashed = 0,
                createdAt = 20,
            )
            insertSpeaker(driver, "spk-on-old", "rec-keep-old", "A", "Alice")
            insertSpeaker(driver, "spk-on-dup", "rec-dup-notes", "B", "Bob")
            // Both versions active before re-point — must collapse to one.
            insertVersion(driver, "ver-on-old", "rec-keep-old", "## old ver", createdAt = 10, active = 1)
            insertVersion(driver, "ver-on-dup", "rec-dup-notes", "## dup ver", createdAt = 20, active = 1)

            VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)
            assertEquals(5L, VelumDatabase.Schema.version)

            val db = VelumDatabase(driver)
            val repo = VelumRepository(db, driver)
            val rows = repo.getAllRecordings().filter { it.session_id == FIXTURE_SESSION }
            assertEquals(1, rows.size)
            val kept = rows.single()
            assertEquals("rec-dup-notes", kept.id)
            assertEquals("only-on-duplicate", kept.transcript)

            // Speakers only from the transcript source (the keeper itself here).
            val speakers = repo.getSpeakers("rec-dup-notes")
            assertEquals(1, speakers.size)
            assertEquals("spk-on-dup", speakers.single().id)
            assertNull(repo.getSpeakers("rec-keep-old").singleOrNull())

            val versions = repo.getSummaryVersions("rec-dup-notes")
            assertEquals(2, versions.size)
            assertEquals(1, versions.count { it.is_active == 1L }, "exactly one active version")
            // Keeper had its own active row (ver-on-dup) — prefer that over re-pointed ones.
            assertEquals("ver-on-dup", versions.single { it.is_active == 1L }.id)

            assertNull(repo.getRecording("rec-keep-old"))
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
        val driver = openV4()
        insertRecording(
            driver,
            id = "rec-live",
            sessionId = FIXTURE_SESSION_B,
            folderId = null,
            title = "Live",
            path = "/live.wav",
            transcript = null,
            summary = null,
            trashed = 0,
            createdAt = 1,
        )
        insertRecording(
            driver,
            id = "rec-trash-notes",
            sessionId = FIXTURE_SESSION_B,
            folderId = null,
            title = "Trash",
            path = "/trash.wav",
            transcript = "from-trash",
            summary = "## from-trash",
            trashed = 1,
            createdAt = 2,
        )

        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val repo = VelumRepository(VelumDatabase(driver), driver)
        val kept = repo.getAllRecordings().single { it.session_id == FIXTURE_SESSION_B }
        assertEquals("rec-live", kept.id)
        assertEquals("from-trash", kept.transcript)
        assertEquals("## from-trash", kept.summary_markdown)
        assertNull(repo.getRecording("rec-trash-notes"))
        driver.close()
    }

    @Test
    fun upgrade_v4_to_v5_speakersOnlyFromTranscriptSource_notKeeperWithoutNotes() = runBlocking {
        val driver = openV4()
        // Keeper wins on non-trashed+oldest but has no transcript; has mismatched speakers.
        insertRecording(
            driver,
            id = "rec-live-empty",
            sessionId = FIXTURE_SESSION_C,
            folderId = null,
            title = "Empty",
            path = "/empty.wav",
            transcript = null,
            summary = null,
            trashed = 0,
            createdAt = 1,
        )
        insertRecording(
            driver,
            id = "rec-notes-src",
            sessionId = FIXTURE_SESSION_C,
            folderId = null,
            title = "Notes",
            path = "/notes.wav",
            transcript = "source-transcript",
            summary = null,
            trashed = 0,
            createdAt = 2,
        )
        insertSpeaker(driver, "spk-empty", "rec-live-empty", "X", "Wrong")
        insertSpeaker(driver, "spk-src-a", "rec-notes-src", "A", "Alice")
        insertSpeaker(driver, "spk-src-b", "rec-notes-src", "B", "Bob")

        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val repo = VelumRepository(VelumDatabase(driver), driver)
        val kept = repo.getAllRecordings().single { it.session_id == FIXTURE_SESSION_C }
        // Both non-trashed; empty is older but notes-src has transcript → notes-src is keeper.
        // Wait: preference is non-trashed, THEN has transcript/summary, THEN oldest.
        // So notes-src (has transcript) wins over empty. Keeper = rec-notes-src.
        assertEquals("rec-notes-src", kept.id)
        assertEquals("source-transcript", kept.transcript)
        val speakers = repo.getSpeakers(kept.id)
        assertEquals(2, speakers.size)
        assertTrue(speakers.any { it.id == "spk-src-a" })
        assertTrue(speakers.any { it.id == "spk-src-b" })
        assertTrue(speakers.none { it.id == "spk-empty" })
        driver.close()
    }

    @Test
    fun upgrade_v4_to_v5_whenKeeperLacksActive_picksNewestVersionAsActive() = runBlocking {
        val driver = openV4()
        // Keeper has transcript (wins) but no active version; duplicate has two versions,
        // newer inactive / older active — after re-point, newest by created_at becomes active
        // because the keeper itself had no prior active row.
        insertRecording(
            driver,
            id = "rec-keeper",
            sessionId = FIXTURE_SESSION_D,
            folderId = null,
            title = "Keep",
            path = "/k.wav",
            transcript = "kept",
            summary = "## kept",
            trashed = 0,
            createdAt = 5,
        )
        insertRecording(
            driver,
            id = "rec-other",
            sessionId = FIXTURE_SESSION_D,
            folderId = null,
            title = "Other",
            path = "/o.wav",
            transcript = null,
            summary = null,
            trashed = 0,
            createdAt = 6,
        )
        insertVersion(driver, "ver-old", "rec-other", "## old", createdAt = 10, active = 1)
        insertVersion(driver, "ver-new", "rec-other", "## new", createdAt = 20, active = 0)

        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val repo = VelumRepository(VelumDatabase(driver), driver)
        val kept = repo.getAllRecordings().single { it.session_id == FIXTURE_SESSION_D }
        assertEquals("rec-keeper", kept.id)
        val versions = repo.getSummaryVersions("rec-keeper")
        assertEquals(2, versions.size)
        assertEquals(1, versions.count { it.is_active == 1L })
        assertEquals("ver-new", versions.single { it.is_active == 1L }.id)
        driver.close()
    }

    @Test
    fun upgrade_v4_to_v5_repointsSpeakersFromTranscriptDonorOntoEmptyKeeper() = runBlocking {
        // Force keeper = empty live (only non-trashed), donor = trashed with transcript+speakers.
        val driver = openV4()
        insertRecording(
            driver,
            id = "rec-live",
            sessionId = FIXTURE_SESSION_E,
            folderId = null,
            title = "Live",
            path = "/live.wav",
            transcript = null,
            summary = null,
            trashed = 0,
            createdAt = 1,
        )
        insertRecording(
            driver,
            id = "rec-donor",
            sessionId = FIXTURE_SESSION_E,
            folderId = null,
            title = "Donor",
            path = "/donor.wav",
            transcript = "donor-text",
            summary = null,
            trashed = 1,
            createdAt = 2,
        )
        insertSpeaker(driver, "spk-live", "rec-live", "Z", "Zap")
        insertSpeaker(driver, "spk-donor", "rec-donor", "A", "Ann")

        VelumDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val repo = VelumRepository(VelumDatabase(driver), driver)
        val kept = repo.getAllRecordings().single { it.session_id == FIXTURE_SESSION_E }
        assertEquals("rec-live", kept.id)
        assertEquals("donor-text", kept.transcript)
        val speakers = repo.getSpeakers("rec-live")
        assertEquals(1, speakers.size, "only transcript-donor speakers survive")
        assertEquals("spk-donor", speakers.single().id)
        driver.close()
    }

    private fun openV4(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CREATE_V4_DDL.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> driver.execute(null, stmt, 0) }
        driver.execute(null, "PRAGMA user_version = 4", 0)
        return driver
    }

    private fun insertRecording(
        driver: JdbcSqliteDriver,
        id: String,
        sessionId: Long,
        folderId: String?,
        title: String,
        path: String?,
        transcript: String?,
        summary: String?,
        trashed: Long,
        createdAt: Long,
    ) {
        val folderSql = folderId?.let { "'$it'" } ?: "NULL"
        val pathSql = path?.let { "'$it'" } ?: "NULL"
        val transcriptSql = transcript?.let { "'$it'" } ?: "NULL"
        val summarySql = summary?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            """
            INSERT INTO recording(
              id, session_id, folder_id, title, source, device_serial,
              started_at, duration_seconds, size_bytes, local_path,
              transcript, summary_markdown, status, trashed, created_at
            ) VALUES (
              '$id', $sessionId, $folderSql, '$title', 'note_pro', NULL,
              $createdAt, 0, 0, $pathSql,
              $transcriptSql, $summarySql, 'ready', $trashed, $createdAt
            )
            """.trimIndent(),
            0,
        )
    }

    private fun insertSpeaker(
        driver: JdbcSqliteDriver,
        id: String,
        recordingId: String,
        label: String,
        displayName: String,
    ) {
        driver.execute(
            null,
            """
            INSERT INTO speaker(id, recording_id, label, display_name, talk_seconds)
            VALUES ('$id', '$recordingId', '$label', '$displayName', 1)
            """.trimIndent(),
            0,
        )
    }

    private fun insertVersion(
        driver: JdbcSqliteDriver,
        id: String,
        recordingId: String,
        markdown: String,
        createdAt: Long,
        active: Long,
    ) {
        driver.execute(
            null,
            """
            INSERT INTO summary_version(
              id, recording_id, template_id, template_name, markdown, created_at, is_active
            ) VALUES (
              '$id', '$recordingId', NULL, 't', '$markdown', $createdAt, $active
            )
            """.trimIndent(),
            0,
        )
    }

    companion object {
        private const val FIXTURE_SESSION = 0x4444_0001L
        private const val FIXTURE_SESSION_B = 0x4444_0002L
        private const val FIXTURE_SESSION_C = 0x4444_0003L
        private const val FIXTURE_SESSION_D = 0x4444_0004L
        private const val FIXTURE_SESSION_E = 0x4444_0005L

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
