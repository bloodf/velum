package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #416: the rename refuses a speaker row that a later run replaced under the same id. Synthetic rows. */
class RenameSpeakerIfUnchangedTest {
    @Test fun renames_only_while_the_row_is_still_the_one_shown() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val q = handle.database.velumQueries
        q.insertSpeaker("rec-1-S1", "rec-1", "S1", null, 300)
        val shown = q.selectSpeakersForRecording("rec-1").executeAsList().single()

        assertTrue(repo.renameSpeakerIfUnchanged("rec-1", shown, "Alex"))
        assertEquals("Alex", q.selectSpeakersForRecording("rec-1").executeAsList().single().display_name)

        q.insertSpeaker("rec-1-S1", "rec-1", "S1", null, 40) // a rerun replaced it, same id
        assertFalse(repo.renameSpeakerIfUnchanged("rec-1", shown, "Sam"))
        assertEquals(null, q.selectSpeakersForRecording("rec-1").executeAsList().single().display_name)
    }
}
