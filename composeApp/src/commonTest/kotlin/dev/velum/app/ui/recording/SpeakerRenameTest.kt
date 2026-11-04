package dev.velum.app.ui.recording

import dev.velum.core.db.Speaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #416: a name typed against one run's speakers must not land on another run's speakers. Synthetic rows. */
class SpeakerRenameTest {
    private fun run(talk1: Long, talk2: Long) = listOf(
        Speaker("rec-1-S1", "rec-1", "S1", null, talk1),
        Speaker("rec-1-S2", "rec-1", "S2", null, talk2),
    )

    private val typed = mapOf("rec-1-S1" to " Alex ", "rec-1-S2" to "Sam")

    @Test fun rows_replaced_while_the_sheet_is_open_save_and_enrol_nothing() = runTest {
        val shown = run(talk1 = 300, talk2 = 200)
        val saved = mutableListOf<Pair<String, String>>()
        // The background job swapped in a new run (same stable ids) before the confirm ran.
        val ok = confirmSpeakerNames(shown, current = { run(talk1 = 120, talk2 = 410) }, renames = typed) { row, name ->
            saved += row.id to name
        }
        assertFalse(ok)
        assertEquals(emptyList(), saved)
    }

    @Test fun a_swap_after_the_first_name_stops_the_rest() = runTest {
        val shown = run(300, 200)
        var live = shown
        val saved = mutableListOf<String>()
        val ok = confirmSpeakerNames(shown, current = { live }, renames = typed) { row, _ ->
            saved += row.id
            live = run(1, 2) // the swap lands while the first name is being saved
        }
        assertFalse(ok)
        assertEquals(listOf("rec-1-S1"), saved)
    }

    @Test fun unchanged_rows_save_every_trimmed_name() = runTest {
        val shown = run(300, 200)
        val saved = mutableListOf<Pair<String, String>>()
        val ok = confirmSpeakerNames(shown, current = { run(300, 200) }, renames = typed + ("rec-1-S9" to "Ghost") + ("rec-1-S2" to "  ")) { row, name ->
            saved += row.id to name
        }
        assertTrue(ok)
        assertEquals(listOf("rec-1-S1" to "Alex"), saved)
    }
}
