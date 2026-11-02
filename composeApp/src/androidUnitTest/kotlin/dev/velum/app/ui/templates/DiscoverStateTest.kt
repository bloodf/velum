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
        assertFalse(showEmptyState(null, fullImportRunning = false))
        assertTrue(showEmptyState(emptyList(), fullImportRunning = false))
        assertFalse(showEmptyState(emptyList(), fullImportRunning = true))
    }
}
