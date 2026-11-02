package dev.velum.app.ui.templates

import dev.velum.core.db.Template

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
