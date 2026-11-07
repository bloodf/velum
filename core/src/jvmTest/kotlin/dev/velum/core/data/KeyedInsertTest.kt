package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** #391: the dedupe check, the insert and the keys are one transaction. */
class KeyedInsertTest {
    private suspend fun VelumRepository.insert(
        id: String,
        keys: List<String>,
        refuseKeys: List<String> = emptyList(),
        clearKeys: List<String> = emptyList(),
    ) = insertRecordingUnlessKeyed(
        id = id, title = "Synthetic", source = "import", startedAt = 1, durationSeconds = 1, localPath = "/synthetic/$id.wav",
        status = "queued", keys = keys, refuseKeys = refuseKeys, clearKeys = clearKeys,
    )

    @Test fun a_second_call_with_the_same_key_returns_the_first_recording_and_inserts_nothing() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        assertEquals(KeyedInsert.Inserted, repo.insert("rec-1", listOf("import_sha256_a", "import_sha256_b")))
        assertEquals(KeyedInsert.Existing("rec-1"), repo.insert("rec-2", listOf("import_sha256_b", "import_sha256_c")))
        assertNull(repo.getRecording("rec-2"), "the loser inserted a second recording")
        assertEquals("rec-1", repo.getMeta("import_sha256_c"), "the loser's other key must point at the kept recording")
    }

    @Test fun a_tombstone_refuses_the_insert_and_stays() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_deleted_a", "1")
        val result = repo.insert("rec-1", listOf("import_sha256_a"), refuseKeys = listOf("import_deleted_a"))
        assertEquals(KeyedInsert.Refused, result)
        assertNull(repo.getRecording("rec-1"), "a deleted recording came back")
        assertNull(repo.getMeta("import_sha256_a"))
        assertNotNull(repo.getMeta("import_deleted_a"))
        Unit
    }

    @Test fun without_a_refuse_list_the_tombstones_are_cleared_with_the_insert() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setMeta("import_deleted_a", "1")
        assertEquals(KeyedInsert.Inserted, repo.insert("rec-1", listOf("import_sha256_a"), clearKeys = listOf("import_deleted_a")))
        assertNull(repo.getMeta("import_deleted_a"))
    }

    @Test fun a_failure_after_the_insert_leaves_no_row_no_keys_and_the_tombstone() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.setMeta("import_deleted_a", "1")
        // The second key cannot be written: everything the call did before it must be undone.
        handle.driver.execute(
            null,
            "CREATE TRIGGER boom BEFORE INSERT ON app_meta WHEN NEW.key = 'import_sha256_boom' BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        )
        assertFails { repo.insert("rec-1", listOf("import_sha256_ok", "import_sha256_boom"), clearKeys = listOf("import_deleted_a")) }
        assertNull(repo.getRecording("rec-1"), "a recording was left without its keys")
        assertNull(repo.getMeta("import_sha256_ok"))
        assertNotNull(repo.getMeta("import_deleted_a"), "the tombstone went with a failed insert")
        Unit
    }
}
