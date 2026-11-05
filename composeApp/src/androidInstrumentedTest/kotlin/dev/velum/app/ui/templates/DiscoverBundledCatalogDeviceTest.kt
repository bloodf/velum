package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.MainActivity
import dev.velum.app.VelumApplication
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.SeedData
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.templates.DiscoverFilter
import dev.velum.core.templates.DiscoverTier
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #102 on a device: Discover over the real bundled catalog (no fixture). The first All switch imports the whole
 * bundled asset. Every filter step compares the result count the search sheet shows with
 * `repository.countDiscoverTemplates` for the same filter, and checks that each filter really narrows (negative
 * control: 0 < filtered < unfiltered). Covers tier switching both ways, category / tag / language filters, search
 * composition, the clearable no-match state, individual clear and clear-all. Offline: the catalog comes from the
 * APK, nothing here touches the network. The screen under test uses its own container and database file (not
 * velum.db); the hosting MainActivity still starts the app's own container on velum.db.
 *
 * Hosted in [MainActivity] (the only activity in the debug manifest); its content is replaced by TemplatesScreen
 * once the app has finished its own handoff.
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.ui.templates.DiscoverBundledCatalogDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class DiscoverBundledCatalogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var container: AppContainer
    private lateinit var root: ModelStoreDir

    private class Probe(val name: String, val category: String, val lang: String, val tag: String)

    @Before fun open() {
        context.deleteDatabase(DB_NAME)
        root = ModelStoreDir(Files.createTempDirectory("velum-discover-device").toString())
        container = AppContainer(InMemorySecureStore(), DriverFactory(context, DB_NAME), modelStoreRoot = root)
    }

    @After fun close() {
        // Activity first: nothing may touch the database while it is closed.
        compose.activityRule.scenario.close()
        container.databaseHandle.driver.close()
        context.deleteDatabase(DB_NAME)
        root.deleteRecursively()
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    /** Chips reappear only after the facets reload, so wait for the chip before tapping it. */
    private fun tap(tag: String) {
        until(what = "chip $tag") { has(tag) }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun until(ms: Long = UI_TIMEOUT_MS, what: String, cond: () -> Boolean) {
        try { compose.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** A result card showing [text]; the search field also contains the query, so editable nodes do not count. */
    private fun card(text: String) = hasText(text) and !hasSetTextAction()
    private fun shows(text: String) = compose.onAllNodes(card(text)).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls the results grid to the card (it is lazy, so composed cells alone prove nothing). */
    private fun cardReachable(text: String): Boolean = runCatching {
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(card(text))
        shows(text)
    }.getOrDefault(false)

    private val countText = Regex("(\\d+) results?")

    /** The result count rendered by the search sheet, or null while it is not composed. */
    private fun uiCount(): Int? = compose.onAllNodes(hasText(countText)).fetchSemanticsNodes()
        .firstNotNullOfOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.firstNotNullOfOrNull {
                countText.find(it.text)?.groupValues?.get(1)?.toInt()
            }
        }

    /** Waits until the sheet's count equals the repository count for [f]; returns that count. */
    private fun awaitCount(f: DiscoverFilter): Int {
        val expected = runBlocking { container.repository.countDiscoverTemplates(f) }.toInt()
        until(what = "UI count == repository count $expected for $f (UI shows ${uiCount()})") { uiCount() == expected }
        return expected
    }

    private class Catalog(val rows: Int, val probe: Probe)

    /** Reads the bundled assets without the app's parser; the probe is unique across the full and seed catalogs. */
    private fun readCatalog(): Catalog {
        fun lines(name: String, gz: Boolean): List<JSONObject> {
            val stream = javaClass.classLoader!!.getResourceAsStream(name)
                ?: context.classLoader.getResourceAsStream(name) ?: error("bundled asset $name missing")
            val reader = (if (gz) GZIPInputStream(stream) else stream).bufferedReader()
            return reader.useLines { seq ->
                seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
            }
        }
        val full = lines("plaud_templates_full.jsonl.gz", gz = true)
        val names = (full + lines("plaud_templates.jsonl", gz = false)).map { it.optString("name").trim().lowercase() }
        val probe = full.firstNotNullOfOrNull { j ->
            val name = j.optString("name")
            val lang = j.optString("lang")
            val cat = j.optString("category")
            val tags = j.optJSONArray("tags")
            val key = name.trim().lowercase()
            if (!j.optBoolean("featured", true) && cat.isNotBlank() && cat != "meeting" &&
                lang.isNotBlank() && lang != "en" && lang != "und" && tags != null && tags.length() > 0 &&
                Regex("[A-Za-z0-9 ]{12,50}").matches(name) && names.count { key in it } == 1
            ) Probe(name, cat, lang, tags.getString(0)) else null
        } ?: error("no unique probe in bundled catalog")
        return Catalog(full.size, probe)
    }

    @Test fun bundled_catalog_tiers_filters_search_and_clear() {
        val catalog = readCatalog()
        val probe = catalog.probe
        // Wait for the app's own handoff (it replaces content once, with App), then take over the activity.
        until(30_000L, "app container ready") { (compose.activity.application as VelumApplication).isContainerReady() }
        until(30_000L, "app content hosted") {
            runCatching { compose.onRoot().fetchSemanticsNode().children.isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
        }

        // All tier triggers the full import; wait for the flag, then for real rows.
        until(what = "tier chip") { has("tier:all") }
        tap("tier:all")
        until(IMPORT_TIMEOUT_MS, "full catalog import") {
            runBlocking { container.repository.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED) } == "1"
        }
        val all = DiscoverFilter(tier = DiscoverTier.All)
        // The sheet shows the result count; it stays open for the whole test.
        compose.onNodeWithContentDescription(En.search).performClick()
        val total = awaitCount(all)
        assertTrue("import flag set but only $total rows (asset has ${catalog.rows})", total >= catalog.rows)

        // Negative controls without search: each filter narrows (0 < n < total) and clears back to total.
        tap("category:meeting")
        until(what = "active category") { has("active:category") }
        val meeting = awaitCount(all.copy(category = "meeting"))
        assertTrue("meeting must narrow: $meeting of $total", meeting in 1 until total)
        tap("active:category")
        assertEquals(total, awaitCount(all))

        tap("tag:${probe.tag}")
        val tagged = awaitCount(all.copy(tags = setOf(probe.tag)))
        assertTrue("tag must narrow: $tagged of $total", tagged in 1 until total)
        tap("active:tag:${probe.tag}")
        assertEquals(total, awaitCount(all))

        tap("lang:${probe.lang}")
        val langOnly = awaitCount(all.copy(lang = probe.lang))
        assertTrue("lang must narrow: $langOnly of $total", langOnly in 1 until total)
        tap("active:lang")
        assertEquals(total, awaitCount(all))

        // Combined filters without search must keep narrowing (counts move), and clear-all returns to the total.
        tap("category:general")
        val general = awaitCount(all.copy(category = "general"))
        tap("lang:zh")
        val generalZh = awaitCount(all.copy(category = "general", lang = "zh"))
        tap("tag:bullets")
        val generalZhBullets = awaitCount(all.copy(category = "general", lang = "zh", tags = setOf("bullets")))
        assertTrue(
            "each added filter must narrow: general=$general, +zh=$generalZh, +bullets=$generalZhBullets, total=$total",
            generalZhBullets in 1 until generalZh && generalZh in 1 until general && general < total,
        )
        tap("filters:clear")
        assertEquals(total, awaitCount(all))

        // Search composition: category `meeting` plus a probe from another category is a clearable no-match.
        tap("category:meeting")
        until(what = "active category") { has("active:category") }
        compose.onNode(hasSetTextAction()).performTextInput(probe.name)
        val miss = all.copy(category = "meeting", search = probe.name)
        assertEquals(0, awaitCount(miss))
        until(what = "empty state for meeting + probe") { has("templates:empty") }
        // Facets reload asynchronously after the search; the category must survive them (it used to be dropped).
        Thread.sleep(FACET_SETTLE_MS)
        compose.waitForIdle()
        assertTrue("category dropped by search facets", has("templates:empty") && has("active:category"))
        assertTrue(!shows(probe.name))
        tap("active:category") // individual clear
        val hit = awaitCount(all.copy(search = probe.name))
        assertTrue("probe must match once the category is cleared", hit >= 1)
        until(what = "probe card after clearing category") { cardReachable(probe.name) }

        // Tier switching both ways with the search kept: the non-featured probe is Featured-excluded.
        tap("tier:featured")
        assertEquals(0, awaitCount(all.copy(tier = DiscoverTier.Featured, search = probe.name)))
        until(what = "probe gone in Featured") { !shows(probe.name) }
        tap("tier:all")
        assertEquals(hit, awaitCount(all.copy(search = probe.name)))
        until(what = "probe back in All") { cardReachable(probe.name) }

        // Category + language + tag compose with the search and keep the probe; clear-all keeps the search.
        tap("category:${probe.category}")
        tap("lang:${probe.lang}")
        tap("tag:${probe.tag}")
        until(what = "all three active chips") {
            has("active:category") && has("active:lang") && has("active:tag:${probe.tag}")
        }
        val combined = all.copy(search = probe.name, category = probe.category, lang = probe.lang, tags = setOf(probe.tag))
        assertTrue("combined filters must keep the probe", awaitCount(combined) >= 1)
        until(what = "probe card under combined filters") { cardReachable(probe.name) }
        tap("filters:clear")
        until(what = "facets cleared") { !has("active:category") && !has("active:lang") && !has("active:tag:${probe.tag}") }
        assertEquals(hit, awaitCount(all.copy(search = probe.name)))
        assertTrue("search survives clear-all", cardReachable(probe.name))
        assertTrue(!has("templates:empty"))
    }

    private companion object {
        const val DB_NAME = "discover102-instrumented.db"
        const val IMPORT_TIMEOUT_MS = 120_000L
        const val UI_TIMEOUT_MS = 15_000L
        const val FACET_SETTLE_MS = 2_000L
    }
}
