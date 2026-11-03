package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Rows written for a recording that was already deleted, and import hashes, must not survive a wipe. */
class WipeOrphansTest {
    @Test fun wipe_deletes_orphan_summaries_speakers_and_import_hashes() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.saveSummaryVersion("gone", "## Synthetic summary", templateId = null, templateName = "Synthetic")
        repo.updateSpeakers("gone", listOf(VelumRepository.SpeakerUpsert(id = "gone-s1", label = "S1", displayName = "Synthetic Ana", talkSeconds = 1L)))
        repo.setMeta("import_sha256_synthetic", "gone")
        assertEquals(1, repo.getSummaryVersions("gone").size)

        repo.wipeAllUserData()

        assertEquals(emptyList(), repo.getSummaryVersions("gone"))
        assertEquals(emptyList(), repo.getSpeakers("gone"))
        assertNull(repo.getMeta("import_sha256_synthetic"))
    }
}
