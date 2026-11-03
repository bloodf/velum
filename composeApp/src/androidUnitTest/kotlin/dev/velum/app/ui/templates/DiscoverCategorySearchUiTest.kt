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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import androidx.compose.ui.test.performTextInput
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #102: a selected category survives a search with no hit in it and shows the clearable no-match state. */
@RunWith(AndroidJUnit4::class)
// Short phone viewport: filter rows live inside the grid, so results must still be reachable.
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class DiscoverCategorySearchUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
            // Diagnostics must never replace the timeout; an open sheet is a second root.
            val diagnostics = runCatching { compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
                .getOrElse { "diagnostic failed: $it" }
            throw AssertionError("Timed out waiting for $what\n$diagnostics", e)
        }
    }

    // Chips appear once the facets loaded, which is after the first rows: wait for the chip before tapping it.
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

    /** Facets queries started and finished, so a test waits for the reload itself instead of sleeping. */
    private val facetsStarted = AtomicInteger()
    private val facetsFinished = AtomicInteger()

    private fun countedQueries(container: AppContainer): DiscoverQueries {
        val real = container.repository.discoverQueries()
        return DiscoverQueries(
            page = real.page, count = real.count,
            facets = { f ->
                facetsStarted.incrementAndGet()
                try { real.facets(f) } finally { facetsFinished.incrementAndGet() }
            },
        )
    }

    /** Waits until a facets reload that began after [finishedBefore] has completed and none is in flight. */
    private fun awaitFacetsReloadAfter(finishedBefore: Int) = waiting("a facets reload after $finishedBefore") {
        facetsFinished.get() > finishedBefore && facetsStarted.get() == facetsFinished.get()
    }

    /**
     * The reload counter moves before the screen stores the new facets, so a wrong prune lands after it. Poll the
     * selection across a short window instead of checking it once.
     */
    private fun assertStaysSelected(tag: String) = repeat(6) {
        Thread.sleep(100)
        compose.waitForIdle()
        assertTrue(hasTag(tag), "$tag was pruned after the facets reload")
    }

    @Test
    fun category_survives_search_without_hits_and_clears() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-cat-search").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            suspend fun add(id: String, name: String, cat: String) = container.repository.upsertTemplate(
                id = id, name = name, category = cat, description = "synthetic", systemPrompt = "p",
                builtin = true, featured = true, tags = emptyList(), lang = "en", uses = 1_000_000,
            )
            add("cs102-a", "ZQ Alpha", "meeting")
            add("cs102-c", "ZQ Gamma", "sales")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = countedQueries(container)) }
                }
            }
            await("ZQ Alpha"); await("ZQ Gamma")
            tap("category:meeting")
            // Wait for the filtered rows first: "Gamma absent" alone is also true while the list is cleared.
            await("ZQ Alpha")
            await("ZQ Gamma", present = false)
            val facetsBeforeSearch = facetsFinished.get()
            // Search for a template that is only in the other category: the category must stay selected.
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("ZQ Gamma")
            awaitTag("templates:empty")
            awaitTag("active:category")
            // Facets reload asynchronously after the search; the category must still be there once they landed.
            awaitFacetsReloadAfter(facetsBeforeSearch)
            assertStaysSelected("active:category")
            assertTrue(hasTag("templates:empty"))
            // The search field itself contains "ZQ Gamma"; only result cards would be non-editable matches.
            assertTrue(compose.onAllNodes(hasText("ZQ Gamma") and !hasSetTextAction()).fetchSemanticsNodes().isEmpty())
            // Individual clear brings the match back, search kept.
            tap("active:category")
            await("ZQ Gamma")
            awaitTag("templates:empty", present = false)
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun withScreen(block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-facet-search").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            suspend fun add(id: String, name: String, cat: String, tags: List<String>, lang: String) =
                container.repository.upsertTemplate(
                    id = id, name = name, category = cat, description = "synthetic", systemPrompt = "p",
                    builtin = true, featured = true, tags = tags, lang = lang, uses = 1_000_000,
                )
            add("fs-a", "ZQ Alpha", "meeting", listOf("agenda"), "en")
            add("fs-b", "ZQ Beta", "meeting", listOf("summary"), "pt")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = countedQueries(container)) }
                }
            }
            await("ZQ Alpha"); await("ZQ Beta")
            block()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun searchThenExpectNoMatch(query: String, activeTag: String) {
        val facetsBeforeSearch = facetsFinished.get()
        compose.onNodeWithContentDescription(PtBr.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        awaitTag("templates:empty")
        // Facets reload asynchronously after the search; the filter must still be selected once they landed.
        awaitFacetsReloadAfter(facetsBeforeSearch)
        assertStaysSelected(activeTag)
        assertTrue(hasTag("templates:empty"))
        tap(activeTag)
        await(query)
        awaitTag("templates:empty", present = false)
    }

    @Test
    fun tag_survives_search_without_hits_and_clears() = withScreen {
        tap("tag:agenda") // only ZQ Alpha
        await("ZQ Beta", present = false)
        searchThenExpectNoMatch("ZQ Beta", "active:tag:agenda")
    }

    @Test
    fun language_survives_search_without_hits_and_clears() = withScreen {
        tap("lang:en") // only ZQ Alpha
        await("ZQ Beta", present = false)
        searchThenExpectNoMatch("ZQ Beta", "active:lang")
    }
}
