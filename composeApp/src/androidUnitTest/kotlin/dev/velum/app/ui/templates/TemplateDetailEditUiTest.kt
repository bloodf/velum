package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h1800dp-xxhdpi")
class TemplateDetailEditUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun exercise(builtin: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-template-edit").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        val id = if (builtin) SeedData.builtinTemplates().first().id else "synthetic-custom"
        val originalName = "Synthetic original"
        val originalPrompt = "Synthetic original prompt"
        runBlocking {
            container.repository.upsertTemplate(
                id = id, name = originalName, category = "meeting", description = "synthetic",
                systemPrompt = originalPrompt, builtin = builtin, tags = listOf("agenda"), lang = "en",
                preferredModel = "synthetic-model", preferredEndpointProfileId = "synthetic-profile",
            )
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { TemplateDetailScreen(container, id, onBack = {}) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(originalPrompt).fetchSemanticsNodes().isNotEmpty() }
            val edit = compose.onNodeWithContentDescription(PtBr.edit)
            val editSize = edit.fetchSemanticsNode().size
            val density = context.resources.displayMetrics.density
            assertTrue(editSize.width / density >= 48f && editSize.height / density >= 48f)
            edit.performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Discarded name")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("Discarded prompt")
            compose.onNodeWithContentDescription(PtBr.cancel).performScrollTo().performClick()
            val q = container.databaseHandle.database.velumQueries
            val unchanged = q.selectTemplateById(id).executeAsOne()
            assertEquals(originalName, unchanged.name)
            assertEquals(originalPrompt, unchanged.system_prompt)
            assertEquals(if (builtin) 1L else 0L, unchanged.builtin)

            compose.onNodeWithContentDescription(PtBr.edit).performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Synthetic saved name")
            compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("Synthetic saved prompt")
            compose.onNodeWithContentDescription(PtBr.save).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                q.selectTemplateById(id).executeAsOne().system_prompt == "Synthetic saved prompt"
            }
            val saved = q.selectTemplateById(id).executeAsOne()
            assertEquals(id, saved.id)
            assertEquals(0L, saved.builtin)
            assertEquals("Synthetic saved name", saved.name)
            assertEquals("Synthetic saved prompt", saved.system_prompt)
            assertEquals("en", saved.lang)
            assertEquals("synthetic-model", saved.preferred_model)
            assertEquals("synthetic-profile", saved.preferred_endpoint_profile_id)
            assertTrue(saved.tags.contains("agenda"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic saved name").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, compose.onAllNodes(hasContentDescription(PtBr.edit) and hasClickAction()).fetchSemanticsNodes().size)
            if (builtin) {
                q.setMeta("demo_seed_version", "0")
                assertTrue(SeedData.maybeSeed(container.databaseHandle.database))
                val reseeded = q.selectTemplateById(id).executeAsOne()
                assertEquals("Synthetic saved name", reseeded.name)
                assertEquals("Synthetic saved prompt", reseeded.system_prompt)
                assertEquals(0L, reseeded.builtin)
            }
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun builtin_edit_cancel_and_save_keep_id_and_survive_reseed() = exercise(builtin = true)
    @Test fun custom_edit_cancel_and_save_keep_id() = exercise(builtin = false)
}
