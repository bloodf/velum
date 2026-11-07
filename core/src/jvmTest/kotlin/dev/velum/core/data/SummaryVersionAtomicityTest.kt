package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking

/** #103: the new summary and its template association are written together, or not at all. */
class SummaryVersionAtomicityTest {
    @Test fun a_failure_while_saving_the_new_summary_leaves_the_prior_summary_and_versions_untouched() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "Call", source = "import", deviceSerial = null,
            startedAt = 1, durationSeconds = 10, sizeBytes = 0, localPath = null, transcript = "hello",
            summaryMarkdown = "## Old notes", status = "ready",
        )
        repo.saveSummaryVersion("rec", "## Second notes", "velum.general", "General", versionId = "v-second", createdAt = 2)
        val before = repo.getSummaryVersions("rec").map { Triple(it.id, it.template_id, it.is_active) }.toSet()
        // The recording's summary is the last write of the save: everything before it must be undone.
        handle.driver.execute(
            null,
            "CREATE TRIGGER boom BEFORE UPDATE OF summary_markdown ON recording WHEN NEW.summary_markdown = '## New notes' BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        )

        assertFails { repo.saveSummaryVersion("rec", "## New notes", "velum.sales", "Sales", versionId = "v-new", createdAt = 3) }

        assertEquals("## Second notes", repo.getRecording("rec")!!.summary_markdown)
        assertEquals(before, repo.getSummaryVersions("rec").map { Triple(it.id, it.template_id, it.is_active) }.toSet(), "a version or the active flag changed")
        assertEquals(1, repo.getSummaryVersions("rec").count { it.is_active != 0L }, "exactly one active version")
    }
}
