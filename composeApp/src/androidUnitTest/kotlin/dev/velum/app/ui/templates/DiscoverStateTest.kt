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

    private fun t(id: String) = dev.velum.core.db.Template(
        id = id, name = id, category = "meeting", description = "", author = "Velum", system_prompt = "p",
        uses = 0, builtin = 1, featured = 1, tags = "", lang = "en", preferred_model = null,
        preferred_endpoint_profile_id = null, created_at = 1,
    )

    @Test fun append_page_keeps_order_and_drops_duplicates() {
        val merged = appendPage(listOf(t("a"), t("b")), listOf(t("b"), t("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }
}
