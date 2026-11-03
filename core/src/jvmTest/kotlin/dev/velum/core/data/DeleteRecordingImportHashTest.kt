package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** #327: a permanent delete removes the import hashes that point at that recording, and only those. */
class DeleteRecordingImportHashTest {
    @Test fun deleting_a_recording_removes_its_import_hashes() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_sha256_source-a", "rec-a")
        repo.setMeta("import_sha256_content-a", "rec-a")
        repo.setMeta("import_sha256_source-b", "rec-b")
        repo.deleteRecordingHard("rec-a")
        assertNull(repo.getMeta("import_sha256_source-a"))
        assertNull(repo.getMeta("import_sha256_content-a"))
        assertEquals("rec-b", repo.getMeta("import_sha256_source-b"))
    }
}
