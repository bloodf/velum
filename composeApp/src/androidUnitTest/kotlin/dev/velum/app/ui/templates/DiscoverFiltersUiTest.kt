package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.di.AppContainer
import androidx.compose.runtime.CompositionLocalProvider
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import dev.velum.ai.local.ModelStoreDir

/** #102: Discover category/tag/language filters, search composition and clearing, on bundled offline data. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class DiscoverFiltersUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun await(text: String, present: Boolean = true) =
        compose.waitUntil(10_000) { shows(text) == present }

    @Test
    fun filters_compose_with_search_and_clear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-ui-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
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
            // Featured tier, no filters: featured rows only.
            await("ZQ Alpha"); await("ZQ Beta"); await("ZQ Gamma")
            assertTrue(!shows("ZQ Delta"))

            // Category filter.
            compose.onAllNodesWithText("Reunião").onFirst().performClick()
            await("ZQ Gamma", present = false)
            assertTrue(shows("ZQ Alpha") && shows("ZQ Beta"))

            // Tag filter composes with the category.
            compose.onAllNodesWithText("Pauta").onFirst().performClick()
            await("ZQ Beta", present = false)
            assertTrue(shows("ZQ Alpha"))

            // Individual clear via the active-filter chip restores the category-only result.
            compose.onNodeWithContentDescription(PtBr.templatesRemoveFilterFormat.replace("%s", "Pauta")).performClick()
            await("ZQ Beta")

            // Search composes with the category filter.
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("ZQ Beta")
            await("ZQ Alpha", present = false)
            assertTrue(shows("ZQ Beta"))

            // Clear all restores filters; search still applies, so the no-match state offers a way out.
            compose.onNode(hasSetTextAction()).performTextInput(" nothing-matches-this")
            await("ZQ Beta", present = false)
            compose.onAllNodesWithText(PtBr.templatesClearFilters).onFirst().performClick()
            assertTrue(!shows(PtBr.templatesActiveFilters))
        } finally {
            activity.pause().stop().destroy()
            root.deleteRecursively()
        }
    }
}
