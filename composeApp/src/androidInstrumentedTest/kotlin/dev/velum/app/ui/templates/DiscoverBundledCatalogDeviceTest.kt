package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * bundled asset; the probe is a non-featured template of the full catalog, so it only shows in the All tier once
 * the import ran. Covers tier switching both ways, category / language / tag filters, search composition,
 * individual and clear-all, and the clearable empty state. Own database file, never velum.db. Offline: the
 * catalog comes from the APK, nothing here touches the network.
 *
 * Hosted in [MainActivity] (the only activity in the debug manifest); its content is replaced by TemplatesScreen
 * on a separate container once the app has finished its own handoff.
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.ui.templates.DiscoverBundledCatalogDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class DiscoverBundledCatalogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var container: AppContainer

    private class Probe(val name: String, val category: String, val lang: String, val tag: String)

    @Before fun open() {
        context.deleteDatabase(DB_NAME)
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-device").toString())
        container = AppContainer(InMemorySecureStore(), DriverFactory(context, DB_NAME), modelStoreRoot = root)
    }

    @After fun close() {
        // Activity first: nothing may touch the database while it is closed.
        compose.activityRule.scenario.close()
        container.databaseHandle.driver.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun until(ms: Long = UI_TIMEOUT_MS, what: String, cond: () -> Boolean) {
        try { compose.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** A non-featured, plain-named full-catalog template outside `meeting`, with a language and a tag. */
    private fun pickProbe(): Probe {
        val stream = javaClass.classLoader!!.getResourceAsStream("plaud_templates_full.jsonl.gz")
            ?: context.classLoader.getResourceAsStream("plaud_templates_full.jsonl.gz")
            ?: error("bundled catalog asset missing")
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val name = j.optString("name")
                val cat = j.optString("category")
                val lang = j.optString("lang")
                val tags = j.optJSONArray("tags")
                if (!j.optBoolean("featured", true) && cat.isNotBlank() && cat != "meeting" && lang.isNotBlank() &&
                    tags != null && tags.length() > 0 && Regex("[A-Za-z0-9 ]{12,50}").matches(name)
                ) return Probe(name, cat, lang, tags.getString(0))
            }
        }
        error("no suitable probe in bundled catalog")
    }

    @Test fun bundled_catalog_tiers_filters_search_and_clear() {
        val probe = pickProbe()
        // Wait for the app's own handoff so it cannot replace our content afterwards.
        until(30_000L, "app container ready") { (compose.activity.application as VelumApplication).isContainerReady() }
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
        }

        // All tier triggers the full import; wait for it (meta flag) before asserting anything on its rows.
        until(what = "featured tier chip") { has("tier:all") }
        tap("tier:all")
        until(IMPORT_TIMEOUT_MS, "full catalog import") {
            runBlocking { container.repository.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED) } == "1"
        }

        // Category filter: the probe's own category keeps it, `meeting` excludes it (empty state, clearable).
        tap("category:meeting")
        until(what = "active category chip") { has("active:category") }
        compose.onNodeWithContentDescription(En.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(probe.name)
        until(what = "empty state for meeting + probe") { has("templates:empty") }
        assertTrue("probe must be excluded by category", !shows(probe.name))
        tap("active:category") // individual clear
        until(what = "probe after clearing category") { shows(probe.name) }
        assertTrue(!has("active:category"))

        // Tier switching both ways with search kept: Featured drops the non-featured probe, All brings it back.
        tap("tier:featured")
        until(what = "probe gone in Featured") { !shows(probe.name) }
        tap("tier:all")
        until(what = "probe back in All") { shows(probe.name) }

        // Category + language + tag compose with the search and keep the probe; clear-all keeps the search.
        tap("category:${probe.category}")
        until(what = "probe in own category") { has("active:category") && shows(probe.name) }
        tap("lang:${probe.lang}")
        until(what = "active lang chip") { has("active:lang") }
        tap("tag:${probe.tag}")
        until(what = "active tag chip") { has("active:tag:${probe.tag}") }
        assertTrue(shows(probe.name))
        tap("filters:clear")
        until(what = "facets cleared") { !has("active:category") && !has("active:lang") && !has("active:tag:${probe.tag}") }
        assertTrue("search survives clear-all", shows(probe.name))
        assertEquals(false, has("templates:empty"))
    }

    private companion object {
        const val DB_NAME = "discover102-instrumented.db"
        const val IMPORT_TIMEOUT_MS = 120_000L
        const val UI_TIMEOUT_MS = 15_000L
    }
}
