package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Rows written for a recording that was already deleted, and import hashes, must not survive a wipe. */
class WipeOrphansTest {
    @Test fun wipe_deletes_orphan_summaries_speakers_and_import_hashes() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        // Written before the repository refused rows for a missing recording, so they can exist on disk.
        val q = handle.database.velumQueries
        q.insertSummaryVersion("gone-v1", "gone", null, "Synthetic", "## Synthetic summary", 1L, 0L)
        q.insertSpeaker("gone-s1", "gone", "S1", "Synthetic Ana", 1L)
        repo.setMeta("import_sha256_synthetic", "gone")
        assertEquals(1, repo.getSummaryVersions("gone").size)

        repo.wipeAllUserData()

        assertEquals(emptyList(), repo.getSummaryVersions("gone"))
        assertEquals(emptyList(), repo.getSpeakers("gone"))
        assertNull(repo.getMeta("import_sha256_synthetic"))
    }

    @Test fun a_job_running_on_after_its_recording_is_gone_leaves_no_rows() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.saveSummaryVersion("gone", "## Synthetic summary", templateId = null, templateName = "Synthetic")
        repo.updateSpeakers("gone", listOf(VelumRepository.SpeakerUpsert(id = "gone-s1", label = "S1", displayName = "Synthetic Ana", talkSeconds = 1L)))
        assertEquals(emptyList(), repo.getSummaryVersions("gone"), "a summary was saved for a recording that is gone")
        assertEquals(emptyList(), repo.getSpeakers("gone"), "speakers were saved for a recording that is gone")
    }
}
