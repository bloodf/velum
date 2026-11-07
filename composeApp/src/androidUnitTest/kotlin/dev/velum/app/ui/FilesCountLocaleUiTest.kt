package dev.velum.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class FilesCountLocaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectionEnglish() = selection(En, "1 selected", "2 selected")
    @Test fun selectionPortuguese() = selection(PtBr, "1 selecionada", "2 selecionadas")
    @Test fun searchEnglish() = search(En, "1 result", "2 results", "0 results")
    @Test fun searchPortuguese() = search(PtBr, "1 resultado", "2 resultados", "0 resultados")

    private fun selection(strings: Strings, one: String, many: String) = withFiles(strings) {
        compose.onNodeWithText("Project Alpha").performTouchInput { longClick() }
        compose.onNodeWithText(one).assertIsDisplayed()
        compose.onNodeWithText("Project Beta").performClick()
        compose.onNodeWithText(many).assertIsDisplayed()
        compose.onNodeWithText(one).assertDoesNotExist()
        compose.onNodeWithText("Project Alpha").performClick()
        compose.onNodeWithText(one).assertIsDisplayed()
        compose.onNodeWithText(many).assertDoesNotExist()
        compose.onNodeWithText("Project Beta").performClick()
        compose.onNodeWithText(one).assertDoesNotExist()
        compose.onNodeWithText(many).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.search).assertIsDisplayed()
    }

    private fun search(strings: Strings, one: String, many: String, zero: String) = withFiles(strings) {
        fun query(value: String, expected: String) {
            compose.onNodeWithContentDescription(strings.search).performClick()
            compose.onNodeWithTag("search:query").performTextReplacement(value)
            compose.waitForIdle()
            pressBack()
            compose.waitForIdle()
            // Assert FilesScreen after dismissing SearchSheet, whose boundary has separate coverage.
            compose.onNodeWithTag("search:query").assertDoesNotExist()
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
        query("Alpha", one)
        compose.onNodeWithText("Project Alpha").assertIsDisplayed()
        compose.onNodeWithText("Project Beta").assertDoesNotExist()
        query("Project", many)
        compose.onNodeWithText("Project Alpha").assertIsDisplayed()
        compose.onNodeWithText("Project Beta").assertIsDisplayed()
        compose.onNodeWithText(one).assertDoesNotExist()
        query("Unmatched", zero)
        compose.onNodeWithText("Project Alpha").assertDoesNotExist()
        compose.onNodeWithText("Project Beta").assertDoesNotExist()
        compose.onNodeWithText(many).assertDoesNotExist()
    }

    private fun withFiles(strings: Strings, assertions: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val modelRoot = ModelStoreDir(java.nio.file.Files.createTempDirectory("velum-files-counts").toString())
        val container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
            modelStoreRoot = modelRoot,
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            runBlocking {
                listOf("Project Alpha", "Project Beta").forEachIndexed { index, title ->
                    container.repository.insertRecording(
                        id = "count-$index", sessionId = 100L + index, folderId = null,
                        title = title, source = "phone", deviceSerial = null,
                        startedAt = 100L + index, durationSeconds = 10, sizeBytes = 100,
                        localPath = null, transcript = null, summaryMarkdown = null,
                        status = "ready", createdAt = 100L + index,
                    )
                }
            }
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    VelumTheme(darkTheme = false) {
                        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Project Alpha").fetchSemanticsNodes().isNotEmpty()
            }
            assertions()
        } finally {
            activity.pause().stop().destroy()
            modelRoot.deleteRecursively()
        }
    }
}
