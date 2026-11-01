package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun full_catalog_has_one_row_per_exact_body_and_aliases_resolve() {
        val full = PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl())
        assertTrue(full.isNotEmpty(), "full catalog missing")
        val bodies = HashMap<String, String>()
        full.forEach { e ->
            val prev = bodies.put(e.body, e.id)
            assertTrue(prev == null, "duplicate exact body ${e.id} == $prev")
        }
        val ids = full.mapTo(HashSet()) { it.id }
        assertTrue(PlaudTemplateAliases.RemovedDuplicates.isNotEmpty())
        PlaudTemplateAliases.RemovedDuplicates.forEach { (removed, survivor) ->
            assertFalse(removed in ids, "removed duplicate still shipped: $removed")
            assertTrue(survivor in ids, "alias target not retained: $removed -> $survivor")
        }
    }

    @Test
    fun featured_has_100_distinct_bodies_per_category_and_every_official() {
        val featured = PlaudTemplateCatalog.All
        val distinct = featured.groupBy { it.category }.mapValues { (_, v) -> v.mapTo(HashSet()) { it.body }.size }
        CATEGORIES.forEach { cat ->
            assertTrue((distinct[cat] ?: 0) >= 100, "Featured $cat has ${distinct[cat] ?: 0} distinct bodies")
        }
        val featuredIds = featured.mapTo(HashSet()) { it.id }
        val officials = PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl()).filter { it.official }
        assertTrue(officials.size >= 41, "official rows dropped: ${officials.size} < 41")
        officials.forEach { assertTrue(it.id in featuredIds, "official ${it.id} not Featured") }
    }

    @Test
    fun official_baseline_stays_official_and_featured() {
        val file = assertNotNull(
            generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { it.resolve("docs/templates/official_baseline.txt") }
                .firstOrNull { it.isFile },
            "docs/templates/official_baseline.txt not found from ${System.getProperty("user.dir")}",
        )
        val baseline = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(baseline.size >= 41, "baseline has ${baseline.size} ids")
        val full = PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl()).associateBy { it.id }
        val featuredIds = PlaudTemplateCatalog.All.mapTo(HashSet()) { it.id }
        baseline.forEach { id ->
            assertTrue(full[id]?.official == true, "official $id missing or not official in Full")
            assertTrue(id in featuredIds, "official $id not Featured")
        }
    }

    private fun assertNoHygieneLeak(text: String, id: String) {
        assertTrue(!EMAIL_RE.containsMatchIn(text), "email in $id")
        assertTrue(!URL_RE.containsMatchIn(text), "url in $id")
        assertTrue(!PLAUD_RE.containsMatchIn(text), "plaud branding in $id")
    }

    companion object {
        private val CATEGORIES = listOf(
            "general", "meeting", "call", "sales", "interview", "consulting", "education", "speech",
            "medical", "legal", "financial", "estate", "construction", "it", "extractive",
        )

        // Match tools/templates/assert_hygiene.py (word-boundary Plaud brand, not substring).
        private val EMAIL_RE = Regex("""\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
        private val URL_RE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val PLAUD_RE = Regex("""\bplaud\b""", RegexOption.IGNORE_CASE)
    }
}
