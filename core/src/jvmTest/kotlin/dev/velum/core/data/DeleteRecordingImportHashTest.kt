package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #327: a permanent delete turns the recording's import hashes into tombstones, and only that recording's. */
class DeleteRecordingImportHashTest {
    @Test fun deleting_a_recording_tombstones_its_import_hashes() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_sha256_source-a", "rec-a")
        repo.setMeta("import_sha256_content-a", "rec-a")
        repo.setMeta("import_sha256_source-b", "rec-b")
        repo.deleteRecordingHard("rec-a")
        assertNull(repo.getMeta("import_sha256_source-a"))
        assertNull(repo.getMeta("import_sha256_content-a"))
        assertNotNull(repo.getMeta("import_deleted_source-a"), "watched sync would import the deleted file again")
        assertNotNull(repo.getMeta("import_deleted_content-a"))
        assertEquals("rec-b", repo.getMeta("import_sha256_source-b"))
        assertNull(repo.getMeta("import_deleted_source-b"))
    }

    @Test fun a_wipe_clears_the_tombstones() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_deleted_source-a", "1")
        repo.wipeAllUserData()
        assertNull(repo.getMeta("import_deleted_source-a"))
    }

    @Test fun prefix_deletes_treat_underscore_literally() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        // `_` is a LIKE wildcard: a prefix match must not reach keys that only look similar.
        repo.setMeta("processingXerror:synthetic", "kept")
        repo.setMeta("importXsha256Xsynthetic", "rec-a")
        repo.wipeAllUserData()
        repo.deleteRecordingHard("rec-a")
        assertEquals("kept", repo.getMeta("processingXerror:synthetic"))
        assertEquals("rec-a", repo.getMeta("importXsha256Xsynthetic"))
    }

    @Test fun a_tombstone_records_when_the_recording_was_deleted() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_sha256_source-a", "rec-a")
        val before = epochSecondsNow()
        repo.deleteRecordingHard("rec-a")
        val deletedAt = assertNotNull(repo.getMeta("import_deleted_source-a")).toLongOrNull()
        assertNotNull(deletedAt, "the tombstone holds no time")
        assertTrue(deletedAt >= before, "tombstone time $deletedAt is before the delete at $before")
    }

    @Test fun a_delete_that_fails_part_way_changes_nothing() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "rec-a", sessionId = null, folderId = null, title = "Synthetic", source = "import", deviceSerial = null,
            startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = null, summaryMarkdown = null, status = "ready",
        )
        repo.setMeta("import_sha256_source-a", "rec-a")
        handle.driver.execute(
            null,
            "CREATE TRIGGER synthetic_failure BEFORE INSERT ON app_meta WHEN NEW.key = 'import_deleted_source-a' " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
            0,
        )
        assertFails { repo.deleteRecordingHard("rec-a") }
        assertNotNull(repo.getRecording("rec-a"), "the recording was deleted but its tombstone was not written")
        assertEquals("rec-a", repo.getMeta("import_sha256_source-a"))
    }

    @Test fun a_glob_prefix_takes_its_metacharacters_literally() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val q = handle.database.velumQueries
        for (key in listOf("a*b?c[d-1", "aXbYcZd-1", "A*b?c[d-1")) repo.setMeta(key, "1")
        q.deleteMetaMatching(VelumRepository.globPrefix("a*b?c[d"))
        assertNull(repo.getMeta("a*b?c[d-1"), "the literal prefix did not match")
        assertEquals("1", repo.getMeta("aXbYcZd-1"), "a metacharacter acted as a wildcard")
        assertEquals("1", repo.getMeta("A*b?c[d-1"), "the match ignored case")
    }
}
