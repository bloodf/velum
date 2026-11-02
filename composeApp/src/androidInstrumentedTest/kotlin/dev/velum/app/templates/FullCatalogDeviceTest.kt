package dev.velum.app.templates

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.VelumDatabase
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import kotlin.system.measureNanoTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #100 on-device check of the full bundled "All" catalog: import, restart, paging order and search
 * latency against the real Android SQLite. Uses its own database file (never the app's velum.db) and
 * needs no network. Expected rows are read from the bundled asset, so the test is count-agnostic.
 *
 * Budgets are deliberately loose until measured on the reference emulator; the measured values are
 * reported as instrumentation status (key `velum.catalog`) and in logcat (tag `VelumCatalog100`).
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.templates.FullCatalogDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class FullCatalogDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var repo: VelumRepository

    @Before fun open() {
        context.deleteDatabase(DB_NAME)
        driver = AndroidSqliteDriver(VelumDatabase.Schema, context, DB_NAME)
        repo = VelumRepository(VelumDatabase(driver), driver)
    }

    @After fun close() {
        driver.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test fun full_catalog_imports_pages_and_searches_within_budget() = runBlocking {
        val asset = readAsset()
        assertTrue("bundled catalog asset missing", asset.isNotEmpty())
        val importMs = measureNanoTime { assertEquals(asset.size, repo.ensureFullTemplatesImported()) } / 1_000_000

        // Restart: a second repository on the same file keeps every row and the index.
        driver.close()
        driver = AndroidSqliteDriver(VelumDatabase.Schema, context, DB_NAME)
        repo = VelumRepository(VelumDatabase(driver), driver)
        assertEquals(0, repo.ensureFullTemplatesImported())

        val builtins = repo.observeTemplates().first().filter { it.builtin != 0L }
        val ids = builtins.map { it.id }
        assertEquals("no repeated entries", ids.size, ids.toSet().size)
        assertTrue("no omitted entries", ids.toSet().containsAll(asset.keys))
        val keys = builtins.map { Triple(it.category, it.name, it.id) }
        assertEquals("total, stable order", keys.sortedWith(sqliteBinaryOrder), keys)
        val pages = builtins.chunked(PAGE)
        assertTrue(pages.first().isNotEmpty() && pages[pages.size / 2].isNotEmpty() && pages.last().isNotEmpty())

        val probe = asset.values.first { it.second }.first
        val folded = Normalizer.normalize(probe, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val probeId = asset.entries.first { it.value.first == probe && it.value.second }.key
        val all = repo.observeTemplates().first()
        val latencies = (1..SEARCH_RUNS).map { run ->
            val query = if (run % 2 == 0) probe else folded
            measureNanoTime {
                val ftsIds = if (repo.ftsAvailable) repo.searchTemplateIdsFts(FtsQuery.fromUserInput(query)!!) else null
                val hits = RecordingSearch.templates(all, query, ftsIds)
                assertTrue("'$query' must find the non-featured entry", hits.any { it.id == probeId })
            } / 1_000_000
        }.sorted()
        val noMatch = if (repo.ftsAvailable) repo.searchTemplateIdsFts(FtsQuery.fromUserInput("zzqxjvwk")!!) else emptyList()
        assertTrue(RecordingSearch.templates(all, "zzqxjvwk", noMatch).isEmpty())

        val p95 = latencies[(latencies.size * 95 / 100).coerceAtMost(latencies.size - 1)]
        val report = "rows=${asset.size} importMs=$importMs searchP50Ms=${latencies[latencies.size / 2]} " +
            "searchP95Ms=$p95 fts=${repo.ftsAvailable} sdk=${android.os.Build.VERSION.SDK_INT}"
        Log.i(TAG, report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.catalog", report) })
        assertTrue("import ${importMs}ms > budget", importMs <= IMPORT_BUDGET_MS)
        assertTrue("search p95 ${p95}ms > budget", p95 <= SEARCH_P95_BUDGET_MS)
    }

    /** id -> (name, isAccentedNonFeaturedProbe) from the bundled gz, read without the app's parser. */
    private fun readAsset(): Map<String, Pair<String, Boolean>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("plaud_templates_full.jsonl.gz")
            ?: context.classLoader.getResourceAsStream("plaud_templates_full.jsonl.gz")
            ?: return emptyMap()
        val out = LinkedHashMap<String, Pair<String, Boolean>>()
        var probeChosen = false
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@forEach
                val word = json.optString("name").split(Regex("\\s+")).map { w -> w.filter { it.isLetter() } }
                    .firstOrNull { w ->
                        val f = Normalizer.normalize(w, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                        w.length >= 4 && f != w && f.all { it in 'a'..'z' || it in 'A'..'Z' }
                    }
                val isProbe = !probeChosen && word != null && !json.optBoolean("featured", true)
                if (isProbe) probeChosen = true
                out[id] = (if (isProbe) word!! else json.optString("name")) to isProbe
            }
        }
        return out
    }

    private companion object {
        // Same comparator as core FullCatalogImportTest (separate module, so copied): SQLite's BINARY
        // collation compares UTF-8 bytes, which differs from Kotlin's UTF-16 order for emoji names.
        val utf8: Comparator<String> = Comparator { a, b ->
            val x = a.encodeToByteArray(); val y = b.encodeToByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (d != 0) return@Comparator d
            }
            x.size - y.size
        }
        val sqliteBinaryOrder: Comparator<Triple<String, String, String>> =
            Comparator { a, b -> utf8.compare(a.first, b.first).takeIf { it != 0 } ?: utf8.compare(a.second, b.second).takeIf { it != 0 } ?: utf8.compare(a.third, b.third) }

        const val DB_NAME = "catalog100-instrumented.db"
        const val TAG = "VelumCatalog100"
        const val PAGE = 50
        const val SEARCH_RUNS = 20
        // Provisional ceilings; tighten to measured value plus margin after the first emulator run.
        const val IMPORT_BUDGET_MS = 90_000L
        const val SEARCH_P95_BUDGET_MS = 1_500L
    }
}
