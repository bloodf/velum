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

    @Test fun loading_state_until_first_page_lands_or_fails() {
        assertTrue(showLoadingState(null, loadFailed = false))
        assertFalse(showLoadingState(null, loadFailed = true))
        assertFalse(showLoadingState(emptyList(), loadFailed = false))
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

    @Test fun tick_reload_keeps_paged_in_rows_up_to_a_bound() {
        assertEquals(60, reloadLimit(pages = 1, sameFilterRowCount = 0, pageSize = 60))
        assertEquals(120, reloadLimit(pages = 1, sameFilterRowCount = 120, pageSize = 60))
        assertEquals(60 * MAX_RELOAD_PAGES, reloadLimit(pages = 1, sameFilterRowCount = 16_000, pageSize = 60))
        assertEquals(60, reloadLimit(pages = 2, sameFilterRowCount = 120, pageSize = 60))
    }
}
