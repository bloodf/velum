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

    @Test fun facets_are_labelled_with_the_search_they_were_loaded_for() = kotlinx.coroutines.runBlocking {
        val narrow = dev.velum.core.templates.DiscoverFacets(listOf("meeting"), emptyList(), listOf("pt"))
        var liveSearch = "foo"
        val queries = DiscoverQueries(
            page = { _, _, _ -> emptyList() }, count = { 0L },
            // The live search is cleared while this query for "foo" is still running.
            facets = { liveSearch = ""; narrow },
        )
        val loaded = loadFacets(queries, dev.velum.core.templates.DiscoverFilter(dev.velum.core.templates.DiscoverTier.All, "foo"))
        assertEquals("foo", loaded.search)
        assertEquals(liveSearch, "")
        assertEquals(LoadedFacets(narrow, "foo"), loaded)
    }

    private fun plan(
        discoverTab: Boolean = true, featuredOnly: Boolean = true, hasRanking: Boolean = true, searchBlank: Boolean = true,
        noFacetSelected: Boolean = true, shown: List<dev.velum.core.db.Template>? = emptyList(), fullImportRunning: Boolean = false,
        loadFailed: Boolean = false, shownTick: Long = 1, currentTick: Long = 1, loadedTarget: Int = 60, totalMatches: Long = 0,
    ) = discoverGridPlan(
        discoverTab, featuredOnly, hasRanking, searchBlank, noFacetSelected, shown, fullImportRunning, loadFailed,
        shownTick, currentTick, loadedTarget, totalMatches,
    )

    @Test fun grid_plan_ranking_needs_every_condition() {
        assertTrue(plan().showRanking)
        assertFalse(plan(discoverTab = false).showRanking)
        assertFalse(plan(featuredOnly = false).showRanking)
        assertFalse(plan(hasRanking = false).showRanking)
        assertFalse(plan(searchBlank = false).showRanking)
        assertFalse(plan(noFacetSelected = false).showRanking)
    }

    @Test fun grid_plan_blocks_follow_the_load_state() {
        assertTrue(plan(shown = null).showLoading)
        assertFalse(plan(shown = null, loadFailed = true).showLoading)
        assertTrue(plan(shown = null, loadFailed = true).showRetry)
        assertTrue(plan(shown = emptyList()).showEmpty)
        assertFalse(plan(shown = emptyList(), fullImportRunning = true).showEmpty)
        assertFalse(plan(shown = null).showEmpty)
    }

    @Test fun grid_plan_paging_sentinel_matches_the_rule() {
        val rows = List(60) { t("r$it") }
        assertTrue(plan(shown = rows, loadedTarget = 60, totalMatches = 100).showPagingSentinel)
        assertFalse(plan(shown = rows, loadedTarget = 60, totalMatches = 60).showPagingSentinel)
        assertFalse(plan(shown = rows, shownTick = 0, currentTick = 1, totalMatches = 100).showPagingSentinel)
        assertFalse(plan(shown = rows.take(10), loadedTarget = 60, totalMatches = 100).showPagingSentinel)
    }

    @Test fun grid_scope_reads_the_plan_not_live_state() {
        val src = listOf(
            java.io.File("src/commonMain/kotlin/dev/velum/app/ui/templates/TemplatesScreen.kt"),
            java.io.File("composeApp/src/commonMain/kotlin/dev/velum/app/ui/templates/TemplatesScreen.kt"),
        ).first { it.isFile }.readText()
        val scope = src.substringAfter("LazyVerticalGrid(").substringAfter(") {\n            // Filter rows scroll")
        for (stateRead in listOf("showLoadingState(", "showEmptyState(", "showPagingSentinel(", "if (loadFailed)", "ranking.isNotEmpty()")) {
            assertFalse(scope.contains(stateRead), "the grid scope must not read $stateRead; use the plan")
        }
        assertTrue(scope.contains("plan.showRanking") && scope.contains("plan.showPagingSentinel"))
    }
}
