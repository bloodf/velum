package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isRoot
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
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

    /** Sees every loaded row by its key, where text checks only see composed ones. Only a missing key means absent. */
    private fun rowLoaded(key: String): Boolean {
        assertTrue(hasTag("templates:grid"), "no grid to look in")
        return try {
            compose.onNodeWithTag("templates:grid").performScrollToKey(key)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** A result row by key, not a ranking card with the same name: the filtered rows have landed once this returns. */
    private fun awaitRow(key: String) {
        waiting("row $key") { hasTag("templates:grid") && rowLoaded(key) }
        compose.onNodeWithTag("templates:grid").performScrollToIndex(0)
    }

    private fun assertRowAbsent(key: String) = assertTrue(!rowLoaded(key), "row $key should not be loaded")

    private fun await(text: String, present: Boolean = true) = waiting("text '$text' present=$present") {
        shows(text) == present
    }

    private fun awaitTag(tag: String, present: Boolean = true) = waiting("tag '$tag' present=$present") {
        hasTag(tag) == present
    }

    /** waitUntil that fails with the semantics tree so a timeout is diagnosable. */
    private val queryLog = QueryLog()

    private fun waiting(what: String, condition: () -> Boolean) {
        compose.waitWhileBusy(queryLog, onTimeout = { reason, e ->
            // Full tree, once: a truncated dump hid the rows below the category chips in a past flake.
            // Diagnostics must never replace the timeout; a second root (open sheet) makes onRoot() throw.
            val diagnostics = runCatching {
                "${chipState("tag:agenda")}\n${chipState("active:tag:agenda")}\n" +
                    compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE)
            }.getOrElse { "diagnostic failed: $it" }
            val message = "Timed out waiting for $what ($reason)\n${queryLog.dump()}\n$diagnostics"
            System.err.println(message)
            throw AssertionError(message, e)
        }, condition = condition)
    }

    /** One line on whether a tagged node exists and reports Selected, for timeout messages. */
    private fun chipState(tag: String): String {
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        val selected = nodes.map { it.config.getOrNull(SemanticsProperties.Selected) }
        return "state[$tag]: nodes=${nodes.size} selected=$selected"
    }

    /** Chips come from an async facets load, so wait for the node before tapping it. */
    private fun tap(tag: String) {
        // Wait until the chip is enabled (a facets reload disables the rows) and actually on screen, then act on it.
        waiting("tappable chip '$tag'") {
            compose.onAllNodes(hasTestTag(tag) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                run {
                    runCatching { compose.onNodeWithTag(tag).performScrollTo() }
                    runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
                }
        }
        // By node identity, not coordinates: a coordinate click could land on a neighbour if the row reflowed.
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
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
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            // No empty-state flash before the first load finishes.
            waiting("first rows") {
                assertTrue(!hasTag("templates:empty"), "empty state flashed before the first load")
                shows("ZQ Alpha")
            }
            // Featured tier: featured rows only.
            await("ZQ Alpha"); await("ZQ Beta"); await("ZQ Gamma")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-d")

            // Tier switching: All adds the non-featured row, Featured removes it again.
            tap("tier:all")
            waiting("Delta row loaded") { rowLoaded("ui102-d") }
            await("ZQ Delta")
            compose.onNodeWithTag("templates:grid").performScrollToIndex(0)
            tap("tier:featured"); await("ZQ Alpha"); await("ZQ Delta", present = false)
            awaitRow("ui102-a")
            assertRowAbsent("ui102-d")

            // Category filter, with the selected state exposed.
            tap("category:meeting")
            await("ZQ Alpha"); await("ZQ Beta")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-c")
            awaitTag("active:category")
            // A result card is actually on screen on a short phone, not just in the tree.
            compose.onAllNodesWithText("ZQ Beta")[0].assertIsDisplayed()

            // Tag filter composes with the category; removing it individually restores the row.
            tap("tag:agenda")
            await("ZQ Alpha")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-b")
            assertRowAbsent("ui102-c")
            tap("active:tag:agenda")
            await("ZQ Beta")

            // Language filter composes; a combination with no match shows the clearable empty state.
            tap("tag:agenda")
            await("ZQ Alpha")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-b")
            tap("lang:en"); await("ZQ Alpha")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-b")
            compose.onAllNodesWithText("ZQ Alpha")[0].assertIsDisplayed()
            tap("lang:pt")
            awaitTag("templates:empty")
            assertRowAbsent("ui102-a")
            compose.onNode(
                hasText(PtBr.templatesClearFilters) and hasAnyAncestor(hasTestTag("templates:empty")),
            ).performScrollTo().performClick()
            awaitTag("active:category", present = false)
            await("ZQ Alpha"); await("ZQ Beta"); await("ZQ Gamma")

            // Search composes with the category filter.
            tap("category:meeting")
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNodeWithTag("search:query").performTextInput("ZQ Beta")
            // Typing is debounced: wait for the old rows to go, then for the match to return.
            await("ZQ Alpha", present = false); await("ZQ Beta")
            awaitRow("ui102-b")
            assertRowAbsent("ui102-a")

            // Widen the search to every ZQ row: the category still excludes Gamma.
            compose.onNodeWithTag("search:query").performTextReplacement("ZQ")
            await("ZQ Alpha"); await("ZQ Beta")
            awaitRow("ui102-a")
            assertRowAbsent("ui102-c")

            // Clear-all keeps the search but drops every facet filter, so Gamma returns.
            tap("filters:clear")
            awaitTag("active:category", present = false)
            await("ZQ Gamma"); awaitRow("ui102-c"); assertRowAbsent("ui102-d")
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
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            await("ZQ Fail")
            tap("category:meeting"); awaitTag("active:category")
            tap("tag:agenda"); awaitTag("active:tag:agenda")
            // Every later facets/page query now fails; the selections must survive the failed load.
            container.databaseHandle.driver.close()
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNodeWithTag("search:query").performTextInput("zq")
            // The failed query is observable: the load error shows before we check the selections.
            await(PtBr.ux.genericError)
            // The facets effect is a separate coroutine from the page load: wait for its own failure, or the window
            // below could pass without the facets query ever having failed.
            waiting("facets failed") { queryLog.count("FAIL  facets(search='zq'") > 0 && queryLog.inFlight() == 0 }
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
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
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            val grid = { compose.onNodeWithTag("templates:grid") }
            // Page 1 is exactly 60 rows: pg-000 is loaded, pg-060 is not (composed rows alone would not show that).
            waiting("first page") { runCatching { grid().performScrollToKey("pg-000") }.isSuccess }
            // A loaded row past the viewport: a composed-text check can't see it, the key check can.
            assertTrue(!shows("PG 059"), "this row should be past the viewport")
            assertRowAbsent("pg-060")
            // Scrolling to the end composes the sentinel, which loads page 2 and appends it.
            assertTrue(rowLoaded("pg-059"))
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
    fun last_available_tag_can_be_selected() {
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
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            await("TG 0")
            tap("category:meeting")
            awaitTag("active:category")
            val tagSearch = compose.onNodeWithTag("tags:search")
            tagSearch.performScrollTo().performTextInput("no-such-synthetic-tag")
            awaitTag("tags:empty")
            assertTrue(hasTag("active:category"))
            tagSearch.performTextReplacement(dev.velum.core.templates.TemplateTagVocabulary.label(last, PtBr.localeTag))
            awaitTag("tag:$last")
            tap("tag:$last")
            awaitTag("active:tag:$last")
            await("TG ${slugs.lastIndex}")
            awaitRow("tg-${slugs.lastIndex}")
            assertRowAbsent("tg-0")
            assertTrue(hasTag("active:category"))
            tap("active:tag:$last")
            awaitTag("active:tag:$last", present = false)
            assertTrue(hasTag("active:category"))
            await("TG 0")
            tap("tags:clear")
            awaitTag("tag:${slugs.first()}")
            assertTrue(hasTag("active:category"))
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun changing_a_filter_after_page_two_resets_paging_without_omitting_rows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-reset-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            // 100 meeting rows rank above 70 sales rows, so page 2 is loaded before the filter changes.
            suspend fun add(prefix: String, category: String, i: Int, uses: Long) =
                container.repository.upsertTemplate(
                    id = "%s-%03d".format(prefix, i), name = "%s %03d".format(prefix.uppercase(), i), category = category,
                    description = "synthetic", systemPrompt = "p", builtin = true, featured = true,
                    tags = emptyList(), lang = "en", uses = uses,
                )
            for (i in 0 until 100) add("pg", "meeting", i, 3_000_000L - i)
            for (i in 0 until 70) add("sl", "sales", i, 1_000_000L - i)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            val grid = { compose.onNodeWithTag("templates:grid") }
            waiting("first page") { runCatching { grid().performScrollToKey("pg-000") }.isSuccess }
            grid().performScrollToKey("pg-059")
            waiting("page 2 loaded") { runCatching { grid().performScrollToKey("pg-099") }.isSuccess }
            // Filter change with paging at page 2: the new filter has 70 rows (two pages) and must show all of them.
            // The filter chips are grid item 0; scroll back up to reach them.
            grid().performScrollToIndex(0)
            tap("category:sales")
            waiting("sales page 1") { runCatching { grid().performScrollToKey("sl-000") }.isSuccess }
            grid().performScrollToKey("sl-059")
            waiting("sales page 2 (no rows omitted)") { runCatching { grid().performScrollToKey("sl-069") }.isSuccess }
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failed_page_two_keeps_page_one_rows_and_shows_the_error() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-page-fail-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
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
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            val grid = { compose.onNodeWithTag("templates:grid") }
            waiting("first page") { runCatching { grid().performScrollToKey("pg-000") }.isSuccess }
            awaitTag("templates:loading", present = false)
            // Every later query fails: scrolling to the end asks for page 2, which must not wipe page 1.
            container.databaseHandle.driver.close()
            grid().performScrollToKey("pg-059")
            await(PtBr.ux.genericError)
            repeat(5) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            assertTrue(runCatching { grid().performScrollToKey("pg-030") }.isSuccess, "page 1 rows were wiped by the failed page")
            assertTrue(!hasTag("templates:empty"), "empty state shown beside the error")
        } finally {
            activity.pause().stop().destroy()
            root.deleteRecursively()
        }
    }
}
