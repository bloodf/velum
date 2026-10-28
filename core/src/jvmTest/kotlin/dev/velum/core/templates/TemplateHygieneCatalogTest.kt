package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
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
        val normalizedBodies = mutableSetOf<String>()
        full.forEach { e ->
            assertTrue(e.id.isNotBlank())
            assertTrue(e.tags.isNotEmpty(), "empty tags in full ${e.id}")
            assertTrue(fullIds.add(e.id), "duplicate full id ${e.id}")
            // Whitespace/CRLF-normalized body uniqueness; case preserved (#99).
            val key = normalizeBodyKey(e.body)
            assertTrue(key.isNotEmpty(), "blank normalized body ${e.id}")
            assertTrue(normalizedBodies.add(key), "duplicate normalized body in full ${e.id}")
        }
        featured.forEach { e ->
            assertTrue(e.id in fullIds, "Featured id missing from Full: ${e.id}")
        }
        val official = full.filter { it.official }
        assertEquals(41, official.size, "expected 41 official templates, got ${official.size}")
        assertEquals(
            41,
            official.map { normalizeBodyKey(it.body) }.toSet().size,
            "official normalized bodies must be unique",
        )
    }

    /** Match tools/templates/body_dedupe.normalize_body_key (no casefold). */
    private fun normalizeBodyKey(body: String): String {
        val text = body.replace("\r\n", "\n").replace("\r", "\n")
        val lines = text.split("\n").map { it.trimEnd() }
        return lines.joinToString("\n").trim()
    }

    @Test
    fun body_dedupe_remap_targets_are_retained_survivors() {
        val fullRaw = PlaudFullTemplateAsset.readJsonl()
        if (fullRaw.isBlank()) return
        val fullIds = PlaudTemplateCatalog.parseJsonl(fullRaw).map { it.id }.toSet()
        TemplateBodyDedupeRemap.OldToSurvivor.forEach { (oldId, survivorId) ->
            assertTrue(oldId !in fullIds, "dropped id still in full catalog: $oldId")
            assertTrue(survivorId in fullIds, "survivor missing from full catalog: $survivorId")
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
