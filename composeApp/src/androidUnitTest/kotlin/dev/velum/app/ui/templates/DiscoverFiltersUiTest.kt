package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.SeedData
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #102: Discover tier, category/tag/language filters, search composition and clearing, on a tiny offline fixture. */
@RunWith(AndroidJUnit4::class)
// Short phone viewport: filter rows live inside the grid, so results must still be reachable.
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class DiscoverFiltersUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Rendered text only: the open search field also carries its typed text and must not count as a result.
    private fun shows(text: String) =
        compose.onAllNodes(hasText(text) and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(text: String, present: Boolean = true) = waiting("text '$text' present=$present") {
        shows(text) == present
    }

    private fun awaitTag(tag: String, present: Boolean = true) = waiting("tag '$tag' present=$present") {
        hasTag(tag) == present
    }

    /** waitUntil that fails with the semantics tree so a timeout is diagnosable. */
    private fun waiting(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val tree = compose.onRoot().printToString(maxDepth = 12).take(6000)
            throw AssertionError("Timed out waiting for $what\n$tree", e)
        }
    }

    /** Chips come from an async facets load, so wait for the node before tapping it. */
    private fun tap(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun tier_filters_search_and_clear_compose() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-ui-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            // Skip the ~29 MB full-catalog import: the All tier is exercised with this tiny fixture.
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            suspend fun add(id: String, name: String, cat: String, tags: List<String>, lang: String, featured: Boolean = true) =
                container.repository.upsertTemplate(
                    id = id, name = name, category = cat, description = "synthetic", systemPrompt = "p",
                    builtin = true, featured = featured, tags = tags, lang = lang, uses = 1_000_000,
                )
            add("ui102-a", "ZQ Alpha", "meeting", listOf("agenda"), "en")
            add("ui102-b", "ZQ Beta", "meeting", emptyList(), "pt")
            add("ui102-c", "ZQ Gamma", "sales", listOf("agenda"), "en")
            add("ui102-d", "ZQ Delta", "meeting", listOf("agenda"), "en", featured = false)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
            // No empty-state flash before the first load finishes.
            waiting("first rows") {
                assertTrue(!hasTag("templates:empty"), "empty state flashed before the first load")
                shows("ZQ Alpha")
            }
            // Featured tier: featured rows only.
            await("ZQ Alpha"); await("ZQ Beta"); await("ZQ Gamma")
            assertTrue(!shows("ZQ Delta"))

            // Tier switching: All adds the non-featured row, Featured removes it again.
            tap("tier:all"); await("ZQ Delta")
            tap("tier:featured"); await("ZQ Alpha"); await("ZQ Delta", present = false)
            assertTrue(!shows("ZQ Delta"))

            // Category filter, with the selected state exposed.
            tap("category:meeting")
            await("ZQ Alpha"); await("ZQ Beta")
            assertTrue(!shows("ZQ Gamma"))
            awaitTag("active:category")
            // A result card is actually on screen on a short phone, not just in the tree.
            compose.onAllNodesWithText("ZQ Beta")[0].assertIsDisplayed()

            // Tag filter composes with the category; removing it individually restores the row.
            tap("tag:agenda")
            await("ZQ Alpha")
            assertTrue(!shows("ZQ Beta"))
            assertTrue(!shows("ZQ Gamma"))
            tap("active:tag:agenda")
            await("ZQ Beta")

            // Language filter composes; a combination with no match shows the clearable empty state.
            tap("tag:agenda")
            await("ZQ Alpha")
            assertTrue(!shows("ZQ Beta"))
            tap("lang:en"); await("ZQ Alpha")
            assertTrue(!shows("ZQ Beta"))
            compose.onAllNodesWithText("ZQ Alpha")[0].assertIsDisplayed()
            tap("lang:pt")
            awaitTag("templates:empty")
            assertTrue(!shows("ZQ Alpha"))
            compose.onNode(
                hasText(PtBr.templatesClearFilters) and hasAnyAncestor(hasTestTag("templates:empty")),
            ).performClick()
            awaitTag("active:category", present = false)
            await("ZQ Alpha"); await("ZQ Beta"); await("ZQ Gamma")

            // Search composes with the category filter.
            tap("category:meeting")
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("ZQ Beta")
            // Typing is debounced: wait for the old rows to go, then for the match to return.
            await("ZQ Alpha", present = false); await("ZQ Beta")
            assertTrue(!shows("ZQ Alpha"))

            // Widen the search to every ZQ row: the category still excludes Gamma.
            compose.onNode(hasSetTextAction()).performTextReplacement("ZQ")
            await("ZQ Alpha"); await("ZQ Beta")
            assertTrue(!shows("ZQ Gamma"))

            // Clear-all keeps the search but drops every facet filter, so Gamma returns.
            tap("filters:clear")
            awaitTag("active:category", present = false)
            await("ZQ Gamma"); assertTrue(!shows("ZQ Delta"))
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failed_facets_load_keeps_selections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-fail-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            container.repository.upsertTemplate(
                id = "ui102-f", name = "ZQ Fail", category = "meeting", description = "synthetic", systemPrompt = "p",
                builtin = true, featured = true, tags = listOf("agenda"), lang = "en", uses = 1_000_000,
            )
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
            await("ZQ Fail")
            tap("category:meeting"); awaitTag("active:category")
            tap("tag:agenda"); awaitTag("active:tag:agenda")
            // Every later facets/page query now fails; the selections must survive the failed load.
            container.databaseHandle.driver.close()
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("zq")
            // The failed query is observable: the load error shows before we check the selections.
            await(PtBr.ux.genericError)
            // Give the facets effect (a separate coroutine) time to fail and any pruning time to run.
            repeat(10) { Thread.sleep(100); compose.waitForIdle() }
            assertTrue(hasTag("active:category") && hasTag("active:tag:agenda"), "selections pruned after failed facets load")
            // A failed load also keeps the previous facet options on screen (not an empty facet row).
            assertTrue(
                hasTag("category:meeting") && hasTag("tag:agenda") && hasTag("lang:en"),
                "facet options cleared by a failed facets load",
            )
        } finally {
            activity.pause().stop().destroy()
            root.deleteRecursively()
        }
    }

    @Test
    fun paging_appends_the_next_page_when_scrolled_to_the_end() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-page-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            // More than one 60-row page, ordered by uses so "PG 000" is first and "PG 099" is on page 2.
            for (i in 0 until 100) {
                container.repository.upsertTemplate(
                    id = "pg-%03d".format(i), name = "PG %03d".format(i), category = "meeting", description = "synthetic",
                    systemPrompt = "p", builtin = true, featured = true, tags = emptyList(), lang = "en",
                    uses = 2_000_000L - i,
                )
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
            val grid = { compose.onNodeWithTag("templates:grid") }
            // Page 1 is exactly 60 rows: pg-000 is loaded, pg-060 is not (composed rows alone would not show that).
            waiting("first page") { runCatching { grid().performScrollToKey("pg-000") }.isSuccess }
            assertTrue(runCatching { grid().performScrollToKey("pg-060") }.isFailure, "page 1 holds more than 60 rows")
            // Scrolling to the end composes the sentinel, which loads page 2 and appends it.
            grid().performScrollToKey("pg-059")
            waiting("page 2 row PG 099") {
                runCatching { grid().performScrollToKey("pg-099") }
                shows("PG 099")
            }
            // Page 1 rows outside the top-5 ranking cards survive the append (not replaced by page 2).
            grid().performScrollToKey("pg-010")
            await("PG 010")
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun every_available_tag_can_be_selected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-tags-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        // One template per vocabulary tag: more tags than any fixed chip cap, so the last one must still be offered.
        val slugs = dev.velum.core.templates.TemplateTagVocabulary.All.map { it.slug }.sorted()
        assertTrue(slugs.size > 24, "fixture needs more than 24 tags")
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            slugs.forEachIndexed { i, slug ->
                container.repository.upsertTemplate(
                    id = "tg-$i", name = "TG $i", category = "meeting", description = "synthetic", systemPrompt = "p",
                    builtin = true, featured = true, tags = listOf(slug), lang = "en", uses = 1_000_000L - i,
                )
            }
        }
        val last = slugs.last()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
                }
            }
            await("TG 0")
            awaitTag("tag:$last")
            compose.onNodeWithTag("tag:$last").performScrollTo()
            tap("tag:$last")
            awaitTag("active:tag:$last")
            await("TG ${slugs.lastIndex}")
            assertTrue(!shows("TG 0"))
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }
}
