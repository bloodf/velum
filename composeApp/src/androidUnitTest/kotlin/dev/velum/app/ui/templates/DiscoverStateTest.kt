package dev.velum.app.ui.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverStateTest {
    @Test fun prune_skips_until_facets_load() {
        assertEquals(setOf("a", "b"), pruneSelection(setOf("a", "b"), emptyList(), facetsLoaded = false))
        assertEquals(setOf("a"), pruneSelection(setOf("a", "b"), listOf("a"), facetsLoaded = true))
        assertEquals("x", pruneSelection("x", emptyList(), facetsLoaded = false))
        assertNull(pruneSelection("x", listOf("y"), facetsLoaded = true))
    }

    @Test fun empty_state_only_after_load() {
        assertFalse(showEmptyState(null, fullImportRunning = false, loadFailed = false))
        assertTrue(showEmptyState(emptyList(), fullImportRunning = false, loadFailed = false))
        assertFalse(showEmptyState(emptyList(), fullImportRunning = true, loadFailed = false))
        assertFalse(showEmptyState(emptyList(), fullImportRunning = false, loadFailed = true))
    }

    private fun t(id: String) = dev.velum.core.db.Template(
        id = id, name = id, category = "meeting", description = "", author = "Velum", system_prompt = "p",
        uses = 0, builtin = 1, featured = 1, tags = "", lang = "en", preferred_model = null,
        preferred_endpoint_profile_id = null, created_at = 1,
    )

    @Test fun append_page_keeps_order_and_drops_duplicates() {
        val merged = appendPage(listOf(t("a"), t("b")), listOf(t("b"), t("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test fun clearing_the_search_applies_at_once_but_typing_is_debounced() {
        assertEquals(0L, searchApplyDelayMs("", "zq"))
        assertEquals(SEARCH_DEBOUNCE_MS, searchApplyDelayMs("zq", ""))
        assertEquals(0L, searchApplyDelayMs("zq", "zq"))
    }

    @Test fun paging_sentinel_waits_for_rows_of_the_current_tick() {
        assertTrue(showPagingSentinel(shownTick = 3, currentTick = 3, rowCount = 60, loadedTarget = 60, totalMatches = 100))
        assertFalse(showPagingSentinel(shownTick = 2, currentTick = 3, rowCount = 60, loadedTarget = 60, totalMatches = 100))
        assertFalse(showPagingSentinel(shownTick = 3, currentTick = 3, rowCount = 59, loadedTarget = 60, totalMatches = 100))
        assertFalse(showPagingSentinel(shownTick = 3, currentTick = 3, rowCount = 100, loadedTarget = 60, totalMatches = 100))
    }
}
