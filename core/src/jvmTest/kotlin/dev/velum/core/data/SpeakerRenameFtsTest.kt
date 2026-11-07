package dev.velum.core.data

import dev.velum.core.search.RecordingSearch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakerRenameFtsTest {
    @Test fun conditional_rename_refreshes_new_and_old_name_search_results() = runTest {
        val handle = createDatabase(DriverFactory())
        try {
            val repo = VelumRepository(handle)
            assertTrue(repo.ftsAvailable, "This regression requires real FTS support")
            insert(repo, "renamed", "**Vordain**\nSynthetic discussion")
            insert(repo, "existing", "Zelmar discussed synthetic plans")
            val q = handle.database.velumQueries
            q.insertSpeaker("renamed-S1", "renamed", "S1", "Vordain", 30)
            val shown = repo.getSpeakers("renamed").single()
            assertEquals(listOf("existing"), repo.searchRecordingIdsFts("Zelmar*"))
            assertEquals(listOf("renamed"), repo.searchRecordingIdsFts("Vordain*"))

            assertTrue(repo.renameSpeakerIfUnchanged("renamed", shown, "Zelmar"))

            assertEquals("**Zelmar**\nSynthetic discussion", repo.getRecording("renamed")!!.transcript)
            val newIds = repo.searchRecordingIdsFts("Zelmar*")
            assertEquals(setOf("renamed", "existing"), newIds.toSet())
            val rows = repo.getAllRecordings()
            assertEquals(setOf("renamed", "existing"),
                RecordingSearch.recordings(rows, "Zelmar", newIds).map { it.id }.toSet())
            val oldIds = repo.searchRecordingIdsFts("Vordain*")
            assertEquals(emptyList(), oldIds)
            assertEquals(emptyList(), RecordingSearch.recordings(rows, "Vordain", oldIds))
        } finally {
            handle.driver.close()
        }
    }

    @Test fun direct_rename_reindexes_default_and_cluster_headings() = runTest {
        val handle = createDatabase(DriverFactory())
        try {
            val repo = VelumRepository(handle)
            assertTrue(repo.ftsAvailable)
            insert(repo, "renamed", "**Speaker 1**\nSynthetic discussion\n**S1**\nMore discussion")
            handle.database.velumQueries.insertSpeaker("renamed-S1", "renamed", "S1", null, 30)

            repo.renameSpeaker("renamed", "renamed-S1", "Zelmar")

            assertEquals("**Zelmar**\nSynthetic discussion\n**Zelmar**\nMore discussion",
                repo.getRecording("renamed")!!.transcript)
            assertEquals(listOf("renamed"), repo.searchRecordingIdsFts("Zelmar*"))
            assertEquals(emptyList(), repo.searchRecordingIdsFts("Speaker*"))
            assertEquals(emptyList(), repo.searchRecordingIdsFts("S1*"))
        } finally {
            handle.driver.close()
        }
    }

    @Test fun replaced_run_rejects_rename_and_preserves_index() = runTest {
        val handle = createDatabase(DriverFactory())
        try {
            val repo = VelumRepository(handle)
            assertTrue(repo.ftsAvailable)
            insert(repo, "renamed", "**Vordain**\nSynthetic discussion")
            insert(repo, "existing", "Zelmar discussed synthetic plans")
            val q = handle.database.velumQueries
            q.insertSpeaker("renamed-S1", "renamed", "S1", "Vordain", 30)
            val shown = repo.getSpeakers("renamed").single()
            q.insertSpeaker("renamed-S1", "renamed", "S1", "Vordain", 10)

            assertFalse(repo.renameSpeakerIfUnchanged("renamed", shown, "Zelmar"))

            assertEquals("Vordain", repo.getSpeakers("renamed").single().display_name)
            assertEquals("**Vordain**\nSynthetic discussion", repo.getRecording("renamed")!!.transcript)
            assertEquals(listOf("renamed"), repo.searchRecordingIdsFts("Vordain*"))
            assertEquals(listOf("existing"), repo.searchRecordingIdsFts("Zelmar*"))
        } finally {
            handle.driver.close()
        }
    }

    private suspend fun insert(repo: VelumRepository, id: String, transcript: String) {
        repo.insertRecording(
            id = id, sessionId = null, folderId = null, title = "Synthetic note", source = "phone",
            deviceSerial = null, startedAt = 1, durationSeconds = 30, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = null, status = "ready",
        )
    }
}
