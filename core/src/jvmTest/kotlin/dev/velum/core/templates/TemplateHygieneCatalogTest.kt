package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hygiene checks on the Featured classpath asset (and optionally the gzip Full set).
 */
class TemplateHygieneCatalogTest {

    @Test
    fun featured_asset_has_no_pii_or_plaud_branding() {
        val raw = PlaudTemplateAsset.readJsonl()
        assertTrue(raw.isNotBlank(), "Featured plaud_templates.jsonl missing from classpath")
        val entries = PlaudTemplateCatalog.parseJsonl(raw)
        assertTrue(entries.size >= 1500, "got ${entries.size}")

        val ids = mutableSetOf<String>()
        val titleBodyKeys = mutableSetOf<String>()
        entries.forEach { e ->
            assertTrue(e.name.isNotBlank(), e.id)
            assertTrue(e.body.isNotBlank(), e.id)
            assertTrue(e.tags.isNotEmpty(), "empty tags ${e.id}")
            assertTrue(e.lang.isNotBlank(), "empty lang ${e.id}")
            assertTrue(ids.add(e.id), "duplicate id ${e.id}")
            val key = e.name.trim().lowercase() + "\u0000" + e.body.trim().lowercase()
            assertTrue(titleBodyKeys.add(key), "duplicate title+body ${e.id}")
            assertNoHygieneLeak(e.body, e.id)
            assertNoHygieneLeak(e.name, e.id)
            assertNoHygieneLeak(e.description, e.id)
        }
    }

    @Test
    fun full_gzip_parses_with_unique_ids_and_featured_subset() {
        val fullRaw = PlaudFullTemplateAsset.readJsonl()
        if (fullRaw.isBlank()) return

        val featured = PlaudTemplateCatalog.All
        val full = PlaudTemplateCatalog.parseJsonl(fullRaw)
        assertTrue(full.size > featured.size, "full=${full.size} featured=${featured.size}")

        val fullIds = mutableSetOf<String>()
        full.forEach { e ->
            assertTrue(e.id.isNotBlank())
            assertTrue(e.tags.isNotEmpty(), "empty tags in full ${e.id}")
            assertTrue(fullIds.add(e.id), "duplicate full id ${e.id}")
        }
        featured.forEach { e ->
            assertTrue(e.id in fullIds, "Featured id missing from Full: ${e.id}")
        }
    }

    private fun assertNoHygieneLeak(text: String, id: String) {
        assertTrue(!EMAIL_RE.containsMatchIn(text), "email in $id")
        assertTrue(!URL_RE.containsMatchIn(text), "url in $id")
        assertTrue(!PLAUD_RE.containsMatchIn(text), "plaud branding in $id")
    }

    companion object {
        // Match tools/templates/assert_hygiene.py (word-boundary Plaud brand, not substring).
        private val EMAIL_RE = Regex("""\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
        private val URL_RE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val PLAUD_RE = Regex("""\bplaud\b""", RegexOption.IGNORE_CASE)
    }
}
