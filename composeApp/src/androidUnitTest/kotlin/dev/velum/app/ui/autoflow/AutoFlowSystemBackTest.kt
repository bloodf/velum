package dev.velum.app.ui.autoflow

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class AutoFlowSystemBackTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun fixture(block: (ComponentActivity, AppContainer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("autoflow-back-test").toString())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val app = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            runBlocking {
                app.repository.upsertAutoflow(
                    id = "back-test", name = "Synthetic flow", enabled = true, wakePhrase = "original",
                    durationMinSeconds = 0, durationMaxSeconds = 10800, sourceFilter = "note_pro",
                    templateId = "velum.meeting", autoLabelVoice = false, language = "auto", notify = true,
                )
            }
            controller.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "list") {
                            composable("list") { Text("Synthetic list") }
                            composable("edit") {
                                AutoFlowEditScreen(app, "back-test", onBack = { nav.popBackStack() })
                            }
                        }
                        androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate("edit") }
                    }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("original").fetchSemanticsNodes().isNotEmpty() }
            block(controller.get(), app)
        } finally {
            controller.pause().stop().destroy()
            app.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun back(activity: ComponentActivity) {
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun dirty() {
        compose.onNode(hasText("Synthetic flow") and hasSetTextAction()).performTextReplacement("Edited flow")
    }

    @Test fun clean_back_pops_navigation() = fixture { activity, _ ->
        back(activity)
        compose.onNodeWithText("Synthetic list").assertExists()
    }

    @Test fun dirty_back_cancel_then_discard_pops_without_saving() = fixture { activity, app ->
        dirty()
        back(activity)
        compose.onNodeWithContentDescription(En.discard).assertExists()
        back(activity)
        compose.onNodeWithContentDescription(En.discard).assertDoesNotExist()
        compose.onNode(hasText("Edited flow") and hasSetTextAction()).assertExists()
        back(activity)
        compose.onNodeWithContentDescription(En.discard).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic list").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Synthetic flow", runBlocking { app.repository.listAutoflows().single { it.id == "back-test" }.name })
    }

    @Test fun sheet_back_dismisses_sheet_before_dirty_editor_and_save_navigates() = fixture { activity, app ->
        dirty()
        compose.onAllNodesWithText(En.edit)[0].performClick()
        compose.onNode(hasText("original") and hasSetTextAction()).performTextReplacement("sheet draft")
        back(activity)
        compose.onNode(hasText("Edited flow") and hasSetTextAction()).assertExists()
        compose.onNodeWithContentDescription(En.discard).assertDoesNotExist()
        back(activity)
        compose.onNodeWithContentDescription(En.save).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic list").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { app.repository.listAutoflows().single { it.id == "back-test" } }
        assertEquals("Edited flow", saved.name)
        assertEquals("original", saved.wake_phrase)
    }
}
