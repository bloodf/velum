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
import androidx.compose.ui.test.performTextClearance
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

/** #102: a selection the catalog tier no longer offers is still pruned, but never while a search narrows the facets. */
@RunWith(AndroidJUnit4::class)
// Short phone viewport: filter rows live inside the grid, so results must still be reachable.
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class DiscoverPruneUiTest {
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
            // An open search sheet is a second root, which makes onRoot() throw and hide the timeout.
            val tree = runCatching { compose.onAllNodes(androidx.compose.ui.test.isRoot()).printToString(maxDepth = 12).take(6000) }
                .getOrElse { "diagnostic failed: $it" }
            throw AssertionError("Timed out waiting for $what\n${queryLog.dump()}\n$tree", e)
        }
    }

    // Chips appear once the facets loaded, which is after the first rows: wait for the chip before tapping it.
    private fun tap(tag: String) { awaitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }

    private val queryLog = QueryLog()

    private fun withScreen(block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-prune").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            suspend fun add(id: String, name: String, cat: String, tags: List<String>, featured: Boolean) =
                container.repository.upsertTemplate(
                    id = id, name = name, category = cat, description = "synthetic", systemPrompt = "p",
                    builtin = true, featured = featured, tags = tags, lang = "en", uses = 1_000_000,
                )
            add("pr-a", "ZQ Alpha", "meeting", listOf("agenda"), featured = true)
            add("pr-d", "ZQ Delta", "zqcat", listOf("aa-zqonly"), featured = false) // category and tag only in the All tier
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queryLog.wrap(container)) }
                }
            }
            await("ZQ Alpha")
            tap("tier:all")
            await("ZQ Delta")
            block()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun category_missing_from_the_tier_is_pruned() = withScreen {
        tap("category:zqcat"); awaitTag("active:category")
        tap("tier:featured")
        awaitTag("active:category", present = false)
    }

    @Test
    fun tag_missing_from_the_tier_is_pruned() = withScreen {
        tap("tag:aa-zqonly"); awaitTag("active:tag:aa-zqonly")
        tap("tier:featured")
        awaitTag("active:tag:aa-zqonly", present = false)
    }

    @Test
    fun tier_switch_during_a_search_keeps_the_selection_until_the_search_is_cleared() = withScreen {
        tap("category:zqcat"); awaitTag("active:category")
        compose.onNodeWithContentDescription(PtBr.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ZQ")
        // The search is debounced: switching tier before it applies would load facets for a blank search, which may
        // legitimately prune. Wait until the debounce has applied, i.e. a facets load for "ZQ" has started.
        waiting("debounced search applied") { queryLog.dump().contains("start facets(search='ZQ'") }
        tap("tier:featured")
        Thread.sleep(1500)
        compose.waitForIdle()
        assertTrue(hasTag("active:category"), "category pruned while a search was narrowing the facets")
        compose.onNode(hasSetTextAction()).performTextClearance()
        awaitTag("active:category", present = false)
    }
}
