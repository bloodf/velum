package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
