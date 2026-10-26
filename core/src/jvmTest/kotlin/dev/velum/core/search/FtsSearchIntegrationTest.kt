package dev.velum.core.search

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FtsSearchIntegrationTest {
    @Test
    fun ftsFindsTranscriptAndTitle() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "fts-1",
            sessionId = null,
            folderId = null,
            title = "Weekly sync",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 1,
            localPath = null,
            transcript = "Discussed Velum BLE handshake",
            summaryMarkdown = "Action items for protocol",
            status = "ready",
        )
        val match = FtsQuery.fromUserInput("handshake")!!
        val ids = repo.searchRecordingIdsFts(match)
        assertTrue(ids.contains("fts-1"), "FTS should find handshake in transcript: $ids match=$match")

        val titleMatch = FtsQuery.fromUserInput("Weekly")!!
        val titleIds = repo.searchRecordingIdsFts(titleMatch)
        assertTrue(titleIds.contains("fts-1"), "FTS should find Weekly in title: $titleIds")
    }

    @Test
    fun glossaryRoundTrip() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.addGlossaryTerm("g1", "Velum", "local companion")
        val terms = repo.listGlossaryTerms()
        assertTrue(terms.any { it.term == "Velum" })
        val fragment = repo.personalizationPromptFragment(includeMemory = false)
        assertTrue(fragment.contains("Velum"))
    }
}
