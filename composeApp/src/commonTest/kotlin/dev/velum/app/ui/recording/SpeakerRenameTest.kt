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

    /** Stands in for the repository: renaming updates display_name, as the real one does; rename is refused for a replaced row. */
    private class Store(var rows: List<Speaker>) {
        val enrolled = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        var onRename: () -> Unit = {}
        suspend fun current() = rows
        fun enrol(row: Speaker, @Suppress("UNUSED_PARAMETER") name: String) { enrolled += row.id }
        fun rename(row: Speaker, name: String): Boolean {
            val stored = rows.firstOrNull { it.id == row.id } ?: return false
            if (stored.label != row.label || stored.talk_seconds != row.talk_seconds) return false
            rows = rows.map { if (it.id == row.id) it.copy(display_name = name) else it }
            renamed += row.id to name
            onRename()
            return true
        }
    }

    private suspend fun Store.confirm(shown: List<Speaker>, renames: Map<String, String> = typed, onEnrol: () -> Unit = {}) =
        confirmSpeakerNames(shown, ::current, renames, { r, n -> enrol(r, n); onEnrol() }, ::rename)

    @Test fun a_normal_confirm_saves_every_speaker_even_though_each_save_changes_display_name() = runTest {
        val shown = run(300, 200)
        val store = Store(shown)
        assertTrue(store.confirm(shown))
        assertEquals(listOf("rec-1-S1" to "Alex", "rec-1-S2" to "Sam"), store.renamed)
        assertEquals(listOf("rec-1-S1", "rec-1-S2"), store.enrolled)
    }

    @Test fun rows_replaced_while_the_sheet_is_open_enrol_and_rename_nothing() = runTest {
        val store = Store(run(talk1 = 120, talk2 = 410)) // the background run swapped in before the confirm
        assertFalse(store.confirm(shown = run(300, 200)))
        assertEquals(emptyList(), store.enrolled)
        assertEquals(emptyList(), store.renamed)
    }

    @Test fun a_swap_after_the_first_name_stops_the_rest() = runTest {
        val shown = run(300, 200)
        val store = Store(shown)
        var first = true
        val ok = store.confirm(shown) { if (first) { first = false; store.rows = run(1, 2) } }
        assertFalse(ok)
        assertEquals(listOf("rec-1-S1"), store.enrolled)
        assertEquals(listOf("rec-1-S1" to "Alex"), store.renamed, "the second rename never ran")
    }

    @Test fun a_refused_rename_enrols_nothing() = runTest {
        val shown = run(300, 200)
        val store = Store(shown)
        store.rows = run(1, 2) // replaced after the tap; the atomic rename refuses it
        // current() would also notice, so call the loop with a current that still looks unchanged.
        val ok = confirmSpeakerNames(shown, { shown }, typed, { r, n -> store.enrol(r, n) }, store::rename)
        assertFalse(ok)
        assertEquals(emptyList(), store.enrolled)
        assertEquals(emptyList(), store.renamed)
    }

    @Test fun a_swap_between_the_rename_and_the_enrol_keeps_the_name_and_skips_the_voice() = runTest {
        val shown = run(300, 200)
        val store = Store(shown)
        store.onRename = { store.rows = run(1, 2).map { it.copy(display_name = "Alex") } }
        val ok = store.confirm(shown, mapOf("rec-1-S1" to "Alex"))
        assertFalse(ok)
        assertEquals(listOf("rec-1-S1" to "Alex"), store.renamed)
        assertEquals(emptyList(), store.enrolled, "no voice may be enrolled against the new run")
    }

    @Test fun unchanged_rows_skip_blank_and_unknown_ids() = runTest {
        val shown = run(300, 200)
        val store = Store(shown)
        assertTrue(store.confirm(shown, mapOf("rec-1-S1" to " Alex ", "rec-1-S9" to "Ghost", "rec-1-S2" to "  ")))
        assertEquals(listOf("rec-1-S1" to "Alex"), store.renamed)
    }
}
