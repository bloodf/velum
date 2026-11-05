package dev.velum.app.ui.autoflow

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class AutoFlowSheetCancelTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun cancel_and_done_use_sheet_drafts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("autoflow-sheet-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.upsertAutoflow(
                id = "sheet-test", name = "Synthetic flow", enabled = true, wakePhrase = "original",
                durationMinSeconds = 0, durationMaxSeconds = 10800, sourceFilter = "note_pro",
                templateId = "velum.meeting", autoLabelVoice = false, language = "auto", notify = true,
            )
        }
        var backs = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { AutoFlowEditScreen(container, "sheet-test", onBack = { backs++ }) }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("original").fetchSemanticsNodes().isNotEmpty() }
            fun click(node: SemanticsNodeInteraction) {
                node.performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            fun edit(index: Int) = click(compose.onAllNodesWithText(En.edit)[index])
            fun action(label: String) = click(compose.onAllNodesWithContentDescription(label)[0])
            edit(0)
            compose.onNode(hasText("original") and hasSetTextAction()).performTextClearance().performTextInput("cancelled")
            action(En.cancel)
            compose.onNodeWithText("original").assertExists()
            edit(0)
            compose.onNode(hasText("original") and hasSetTextAction()).assertExists()
            compose.onNode(hasText("original") and hasSetTextAction()).performTextClearance().performTextInput("dismissed")
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss) { it() }
            click(compose.onNodeWithContentDescription(En.ux.back))
            compose.runOnIdle { assertEquals(1, backs) }

            edit(0)
            compose.onNode(hasText("original") and hasSetTextAction()).performTextClearance().performTextInput("applied")
            action(En.done)
            compose.onNodeWithText("applied").assertExists()
            edit(1)
            click(compose.onAllNodesWithText(En.languageEn).let { it[it.fetchSemanticsNodes().lastIndex] })
            action(En.cancel)
            edit(1)
            click(compose.onAllNodesWithText(En.languageEn).let { it[it.fetchSemanticsNodes().lastIndex] })
            action(En.done)
            click(compose.onNodeWithContentDescription(En.ux.back))
            compose.onNodeWithContentDescription(En.discard).assertExists()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }
}
