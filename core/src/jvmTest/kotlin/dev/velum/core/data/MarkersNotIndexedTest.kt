package dev.velum.core.data

import dev.velum.core.search.RecordingSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MarkersNotIndexedTest {
    @Test fun marker_text_is_not_searchable_but_spoken_text_is() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "rec-1", sessionId = null, folderId = null, title = "Synthetic note", source = "phone",
            deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "We talked about oranges\n\n## Markers\n- **0:30** zebrafoo",
            summaryMarkdown = null, status = "ready",
        )
        assertEquals(listOf("rec-1"), repo.searchRecordingIdsFts("oranges*"))
        assertTrue(repo.searchRecordingIdsFts("zebrafoo*").isEmpty(), "marker text leaked into the FTS index")
        val rows = repo.getRecording("rec-1")!!
        assertTrue(RecordingSearch.recordings(listOf(rows), "zebrafoo", null).isEmpty())
        assertEquals(1, RecordingSearch.recordings(listOf(rows), "oranges", null).size)
    }
}
