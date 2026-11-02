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
            val tree = compose.onRoot().printToString(maxDepth = 12).take(6000)
            throw AssertionError("Timed out waiting for $what\n$tree", e)
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()

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
            tap("tier:featured"); await("ZQ Delta", present = false)

            // Category filter, with the selected state exposed.
            tap("category:meeting")
            await("ZQ Gamma", present = false)
            await("ZQ Alpha"); await("ZQ Beta")
            awaitTag("active:category")
            // A result card is actually on screen on a short phone, not just in the tree.
            compose.onAllNodesWithText("ZQ Beta")[0].assertIsDisplayed()

            // Tag filter composes with the category; removing it individually restores the row.
            tap("tag:agenda")
            await("ZQ Beta", present = false)
            await("ZQ Alpha")
            tap("active:tag:agenda")
            await("ZQ Beta")

            // Language filter composes; a combination with no match shows the clearable empty state.
            tap("tag:agenda")
            await("ZQ Beta", present = false)
            tap("lang:en"); await("ZQ Alpha")
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
            await("ZQ Alpha", present = false)
            await("ZQ Beta")

            // Clear-all keeps the search but drops every facet filter.
            tap("filters:clear")
            awaitTag("active:category", present = false)
            await("ZQ Beta"); await("ZQ Gamma", present = false)
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
            compose.waitUntil(5_000) { true }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertTrue(hasTag("active:category") && hasTag("active:tag:agenda"), "selections pruned after failed facets load")
        } finally {
            activity.pause().stop().destroy()
            root.deleteRecursively()
        }
    }
}
