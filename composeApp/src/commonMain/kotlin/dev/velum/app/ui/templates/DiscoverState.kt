package dev.velum.app.ui.templates

import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Template
import dev.velum.core.templates.DiscoverFacets
import dev.velum.core.templates.DiscoverFilter

/** Search keystrokes settle for this long before the Discover queries re-run. */
internal const val SEARCH_DEBOUNCE_MS = 200L

/**
 * Keeps only the selections the latest facets still offer. A facets load that has not succeeded yet
 * ([facetsLoaded] false) must never prune: a failed or still-pending load is not "no facets".
 */
internal fun <T> pruneSelection(selected: Set<T>, available: List<T>, facetsLoaded: Boolean): Set<T> =
    if (!facetsLoaded) selected else selected.filterTo(LinkedHashSet()) { it in available }

internal fun pruneSelection(selected: String?, available: List<String>, facetsLoaded: Boolean): String? =
    if (!facetsLoaded || selected == null || selected in available) selected else null

/** Appends the next page (loaded with offset = [shown].size) without duplicating ids already shown. */
internal fun appendPage(shown: List<Template>, next: List<Template>): List<Template> {
    val seen = shown.mapTo(HashSet()) { it.id }
    return shown + next.filter { seen.add(it.id) }
}

/**
 * The empty state is only valid once a load has finished: `null` means "not loaded yet". A failed load shows the
 * error line instead, since "no results" would be wrong.
 */
internal fun showEmptyState(shown: List<Template>?, fullImportRunning: Boolean, loadFailed: Boolean): Boolean =
    shown != null && shown.isEmpty() && !fullImportRunning && !loadFailed

/** Typing waits for the debounce; clearing the search (or an unchanged query) applies at once. */
internal fun searchApplyDelayMs(query: String, applied: String): Long =
    if (query.isNotEmpty() && query != applied) SEARCH_DEBOUNCE_MS else 0L

/**
 * The next page is requested only when the rows on screen were loaded at the current catalog tick (so stale rows
 * cannot trigger an append), the last page came back full, and more matches exist.
 */
internal fun showPagingSentinel(shownTick: Long, currentTick: Long, rowCount: Int, loadedTarget: Int, totalMatches: Long): Boolean =
    shownTick == currentTick && rowCount >= loadedTarget && rowCount < totalMatches

/** A spinner stands in for the grid until the first page of the current filter has loaded (or failed). */
internal fun showLoadingState(shown: List<Template>?, loadFailed: Boolean): Boolean = shown == null && !loadFailed

/**
 * Which optional blocks the results grid has, decided once per composition from plain values. The grid's item list
 * must not depend on state read while the lazy grid measures: an item count that moves between the grid's reads
 * of it may be the `IndexOutOfBounds` seen in #202 (the cause is not proven). With the plan fixed per composition,
 * every read in a measure pass sees the same structure.
 */
internal data class DiscoverGridPlan(
    val showRanking: Boolean,
    val showLoading: Boolean,
    val showEmpty: Boolean,
    val showRetry: Boolean,
    val showPagingSentinel: Boolean,
)

internal fun discoverGridPlan(
    discoverTab: Boolean,
    featuredOnly: Boolean,
    hasRanking: Boolean,
    searchBlank: Boolean,
    noFacetSelected: Boolean,
    shown: List<Template>?,
    fullImportRunning: Boolean,
    loadFailed: Boolean,
    shownTick: Long,
    currentTick: Long,
    loadedTarget: Int,
    totalMatches: Long,
): DiscoverGridPlan = DiscoverGridPlan(
    showRanking = discoverTab && featuredOnly && hasRanking && searchBlank && noFacetSelected,
    showLoading = showLoadingState(shown, loadFailed),
    showEmpty = showEmptyState(shown, fullImportRunning, loadFailed),
    showRetry = loadFailed,
    showPagingSentinel = showPagingSentinel(shownTick, currentTick, shown.orEmpty().size, loadedTarget, totalMatches),
)

/** The three Discover queries as a seam: production delegates to the repository, tests count, fail or hold them. */
internal class DiscoverQueries(
    val page: suspend (DiscoverFilter, Int, Int) -> List<Template>,
    val count: suspend (DiscoverFilter) -> Long,
    val facets: suspend (DiscoverFilter) -> DiscoverFacets,
)

internal fun VelumRepository.discoverQueries() = DiscoverQueries(
    page = { filter, limit, offset -> queryDiscoverTemplates(filter, limit = limit, offset = offset) },
    count = { filter -> countDiscoverTemplates(filter) },
    facets = { filter -> discoverFacets(filter) },
)

/**
 * Rows to request for a load. A reload of the same filter at page one (a catalog tick) keeps the rows already
 * paged in, so a deep scroll does not collapse back to one page.
 */
internal fun reloadLimit(pages: Int, sameFilterRowCount: Int, pageSize: Int): Int =
    if (pages == 1) minOf(maxOf(pageSize, sameFilterRowCount), pageSize * MAX_RELOAD_PAGES) else pageSize

/** Upper bound on a single tick reload, so a very deep scroll of the ~16k catalog never re-queries thousands of rows. */
internal const val MAX_RELOAD_PAGES = 10

/** Facets together with the (debounced) search they were loaded for, so the pair is replaced atomically. */
internal data class LoadedFacets(val facets: DiscoverFacets, val search: String)

internal val NoFacets = DiscoverFacets(emptyList(), emptyList(), emptyList())

/**
 * Loads facets for [filter] and labels them with that same filter's search. The label comes from the filter captured
 * before the query, never from live state read after it: clearing the search writes the new value at once, so a
 * slower load for the old search would otherwise be stored as a blank-search load and allowed to prune selections.
 */
internal suspend fun loadFacets(queries: DiscoverQueries, filter: DiscoverFilter): LoadedFacets =
    LoadedFacets(queries.facets(filter), filter.search)
