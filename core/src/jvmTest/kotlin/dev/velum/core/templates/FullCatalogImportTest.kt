package dev.velum.core.templates

import app.cash.sqldelight.db.QueryResult
import dev.velum.core.data.DatabaseHandle
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.SeedData
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.db.Template
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #100: the full bundled "All" catalog. Expected data is read from the asset itself, so these tests
 * stay valid when the catalog changes size (e.g. #99 duplicate removal).
 */
class FullCatalogImportTest {
    private class Db(val handle: DatabaseHandle = createDatabase(DriverFactory())) {
        val repo = VelumRepository(handle)
        val q get() = handle.database.velumQueries
        fun builtins(): List<Template> = q.selectAllBuiltinTemplates().executeAsList()
        fun ftsRows(): Long = handle.driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM template_fts",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ).value
        fun templateRows(): Long = q.selectTemplates().executeAsList().size.toLong()
    }

    private fun imported(): Db = Db().also { runBlocking { it.repo.ensureFullTemplatesImported() } }

    @Test fun fresh_import_exposes_every_asset_entry_with_full_fts_coverage() {
        val db = Db()
        val seeded = db.builtins().map { it.id }.toSet()
        val upserted = runBlocking { db.repo.ensureFullTemplatesImported() }
        assertEquals(asset.size, upserted, "every parsable asset line is imported")
        val ids = db.builtins().map { it.id }.toSet()
        assertTrue(ids.containsAll(assetIds), "missing ${(assetIds - ids).size} asset ids")
        assertEquals((assetIds + seeded).size, ids.size, "no extra or duplicated builtin rows")
        assertEquals(db.templateRows(), db.ftsRows(), "every template row is searchable")
        assertEquals("1", db.q.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED).executeAsOneOrNull())
    }

    @Test fun repeated_import_and_restart_neither_duplicate_nor_lose_fts() {
        val db = imported()
        val before = db.builtins().map { it.id }.sorted()
        val fts = db.ftsRows()
        val bumped = assetIds.first()
        val usesBefore = db.q.selectTemplateById(bumped).executeAsOne().uses
        db.handle.driver.execute(null, "UPDATE template SET uses = uses + 1 WHERE id = ?", 1) { bindString(0, bumped) }
        assertEquals(0, runBlocking { db.repo.ensureFullTemplatesImported() }, "second call is a no-op")
        // Forced re-import (e.g. after reseed resets the marker) upserts in place.
        db.q.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "0")
        assertEquals(asset.size, runBlocking { db.repo.ensureFullTemplatesImported() })
        assertEquals(before, db.builtins().map { it.id }.sorted())
        assertEquals(fts, db.ftsRows())
        assertEquals(usesBefore + 1, db.q.selectTemplateById(bumped).executeAsOne().uses, "uses kept on re-import")
        // Restart: a new repository on the same database.
        val restarted = VelumRepository(db.handle)
        assertEquals(before, db.builtins().map { it.id }.sorted())
        assertEquals(fts, db.ftsRows())
        val probe = searchProbe()
        assertTrue(probe.id in runBlocking { restarted.searchTemplateIdsFts(FtsQuery.fromUserInput(probe.query)!!) })
    }

    @Test fun reimport_never_overwrites_a_user_edited_builtin() {
        val db = imported()
        val edited = asset.first { !it.featured }
        runBlocking {
            db.repo.upsertTemplate(edited.id, "Synthetic edit", edited.category, "", "SYNTHETIC USER PROMPT")
        }
        db.q.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "0")
        runBlocking { db.repo.ensureFullTemplatesImported() }
        val row = db.q.selectTemplateById(edited.id).executeAsOne()
        assertEquals(0L, row.builtin)
        assertEquals("SYNTHETIC USER PROMPT", row.system_prompt)
        assertEquals("Synthetic edit", row.name)
    }

    @Test fun fts_finds_entries_outside_featured_with_and_without_accents_and_handles_no_match() {
        val db = imported()
        val probe = searchProbe()
        assertTrue(probe.query != probe.folded, "probe must exercise diacritics")
        for (query in listOf(probe.query, probe.folded, probe.query.uppercase())) {
            val hits = runBlocking { db.repo.searchTemplateIdsFts(FtsQuery.fromUserInput(query)!!) }
            assertTrue(probe.id in hits, "'$query' must find the non-featured entry")
        }
        val none = FtsQuery.fromUserInput("zzqxjvwk")!!
        val noHits = runBlocking { db.repo.searchTemplateIdsFts(none) }
        assertTrue(noHits.isEmpty())
        val all = runBlocking { db.repo.observeTemplates().first() }
        assertTrue(RecordingSearch.templates(all, "zzqxjvwk", noHits).isEmpty())
    }

    @Test fun paging_the_all_list_reaches_every_entry_once_in_a_stable_order() {
        val db = imported()
        fun allBuiltins() = runBlocking { db.repo.observeTemplates().first() }.filter { it.builtin != 0L }
        val first = allBuiltins()
        // Ties on (category, name) are common in the catalog; the order must still be total.
        val keys = first.map { Triple(it.category, it.name, it.id) }
        assertEquals(keys.sortedWith(sqliteBinaryOrder), keys)
        val pages = first.chunked(PAGE)
        val reread = allBuiltins().chunked(PAGE)
        assertEquals(pages.map { p -> p.map { it.id } }, reread.map { p -> p.map { it.id } }, "stable across reads")
        val seen = pages.flatten().map { it.id }
        assertEquals(seen.size, seen.toSet().size, "no repeated entries")
        assertTrue(seen.toSet().containsAll(assetIds), "no omitted entries")
        val mid = pages[pages.size / 2]
        assertTrue(pages.first().isNotEmpty() && mid.isNotEmpty() && pages.last().isNotEmpty())
    }

    @Test fun cancelled_import_leaves_no_half_state_and_a_retry_completes() {
        val db = Db()
        val before = db.builtins().map { it.id }.toSet()
        val result = runBlocking { withTimeoutOrNull(20) { db.repo.ensureFullTemplatesImported() } }
        if (result == null) {
            assertEquals(before, db.builtins().map { it.id }.toSet(), "cancelled import rolls back")
            assertTrue(db.q.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED).executeAsOneOrNull() != "1")
        }
        assertEquals(asset.size, runBlocking { db.repo.ensureFullTemplatesImported() })
        assertTrue(db.builtins().map { it.id }.toSet().containsAll(assetIds))
        assertEquals(db.templateRows(), db.ftsRows())
    }

    @Test fun import_stays_within_time_and_heap_budget() {
        val runtime = Runtime.getRuntime()
        val db = Db()
        System.gc()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val took = measureTime { runBlocking { db.repo.ensureFullTemplatesImported() } }
        System.gc()
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val growthMb = (heapAfter - heapBefore) / (1024 * 1024)
        println("FullCatalogImport rows=${asset.size} took=${took.inWholeMilliseconds}ms heapGrowth=${growthMb}MB")
        assertTrue(took.inWholeSeconds < JVM_IMPORT_BUDGET_SECONDS, "import took $took")
        // Streaming import: retained heap must not scale with the ~29MB decompressed catalog.
        assertTrue(growthMb < JVM_HEAP_GROWTH_BUDGET_MB, "heap grew ${growthMb}MB")
    }

    private data class Probe(val id: String, val query: String, val folded: String)

    /** A non-featured entry whose name has an accented word; prefers pt entries. */
    private fun searchProbe(): Probe {
        fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val candidates = asset.asSequence().filter { !it.featured }.mapNotNull { e ->
            e.name.split(Regex("\\s+"))
                .map { w -> w.filter { it.isLetter() } }
                .firstOrNull { w -> w.length >= 4 && fold(w) != w && fold(w).all { it in 'a'..'z' || it in 'A'..'Z' } }
                ?.let { Probe(e.id, it, fold(it)) to e.lang }
        }.toList()
        val pick = candidates.firstOrNull { it.second.startsWith("pt") } ?: candidates.firstOrNull()
        return assertNotNull(pick, "asset has no accented non-featured name").first
    }

    companion object {
        private const val PAGE = 50

        /** SQLite's default BINARY collation compares UTF-8 bytes, not UTF-16 chars (emoji names differ). */
        private val utf8: Comparator<String> = Comparator { a, b ->
            val x = a.encodeToByteArray(); val y = b.encodeToByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (d != 0) return@Comparator d
            }
            x.size - y.size
        }
        private val sqliteBinaryOrder: Comparator<Triple<String, String, String>> =
            Comparator { a, b -> utf8.compare(a.first, b.first).takeIf { it != 0 } ?: utf8.compare(a.second, b.second).takeIf { it != 0 } ?: utf8.compare(a.third, b.third) }
        private const val JVM_IMPORT_BUDGET_SECONDS = 60L
        private const val JVM_HEAP_GROWTH_BUDGET_MB = 128L

        /** Distinct parsable entries of the bundled asset (last line wins per id, like the import). */
        private val asset: List<TemplateCatalogEntry> by lazy {
            val byId = LinkedHashMap<String, TemplateCatalogEntry>()
            PlaudFullTemplateAsset.forEachLine { line -> PlaudTemplateCatalog.parseJsonlLine(line)?.let { byId[it.id] = it } }
            check(byId.isNotEmpty()) { "bundled catalog asset missing" }
            byId.values.toList()
        }
        private val assetIds: Set<String> by lazy { asset.map { it.id }.toSet() }
    }
}
