package dev.velum.app.ui.autoflow

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
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

    private fun click(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun edit(index: Int) = click(compose.onAllNodesWithText(En.edit)[index])
    private fun action(label: String) = click(compose.onNodeWithContentDescription(label))
    private fun choose(label: String) {
        val nodes = compose.onAllNodes(hasText(label) and hasClickAction())
        click(nodes[nodes.fetchSemanticsNodes().lastIndex])
    }
    private fun replaceText(old: String, new: String) {
        compose.onNode(hasText(old) and hasSetTextAction()).performTextReplacement(new)
    }
    private fun save() = click(compose.onNodeWithText(En.save))

    private suspend fun insertFlow(app: AppContainer) {
        app.repository.upsertAutoflow(
            id = "sheet-test", name = "Synthetic flow", enabled = true, wakePhrase = "original",
            durationMinSeconds = 0, durationMaxSeconds = 10800, sourceFilter = "note_pro",
            templateId = "velum.meeting", autoLabelVoice = false, language = "auto", notify = true,
        )
    }

    private fun fixture(seedFlow: Boolean = true, block: (AppContainer, () -> Unit) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("autoflow-sheet-test").toString())
        var container: AppContainer? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val app = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
            container = app
            if (seedFlow) runBlocking { insertFlow(app) }
            val visible = mutableStateOf(true)
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        if (visible.value) AutoFlowEditScreen(app, "sheet-test", onBack = { visible.value = false })
                    }
                }
            }
            fun reopen() {
                compose.runOnIdle { visible.value = true }
                compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().size == 2 }
            }
            if (seedFlow) {
                compose.waitUntil(20_000) { compose.onAllNodesWithText("original").fetchSemanticsNodes().isNotEmpty() }
            }
            block(app, ::reopen)
        } finally {
            activity.pause().stop().destroy()
            container?.databaseHandle?.driver?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun edit_controls_can_appear_before_repository_emits_the_flow() = fixture(seedFlow = false) { app, _ ->
        compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("original").assertDoesNotExist()
        runBlocking { insertFlow(app) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("original").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("original").assertExists()
    }

    @Test
    fun cancel_and_dismiss_leave_saved_values_while_done_persists() = fixture { app, reopen ->
        edit(0)
        replaceText("original", "cancelled")
        action(En.cancel)
        edit(0)
        compose.onNode(hasText("original") and hasSetTextAction()).assertExists()
        replaceText("original", "dismissed")
        // This invokes the modal's dismissal semantics; it does not exercise an outside pointer gesture.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        edit(1)
        choose(En.languageEn)
        action(En.cancel)
        save()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().isEmpty() }
        var saved = runBlocking { app.repository.listAutoflows().single { it.id == "sheet-test" } }
        assertEquals("original", saved.wake_phrase)
        assertEquals("auto", saved.language)
        reopen()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("original").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("original").assertExists()
        edit(0)
        replaceText("original", "applied")
        action(En.done)
        edit(1)
        choose(En.languageEn)
        action(En.done)
        save()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().isEmpty() }
        saved = runBlocking { app.repository.listAutoflows().single { it.id == "sheet-test" } }
        assertEquals("applied", saved.wake_phrase)
        assertEquals("en", saved.language)
        reopen()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("applied").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("applied").assertExists()
        edit(0)
        compose.onNode(hasText("applied") and hasSetTextAction()).assertExists()
    }

    @Test
    fun cancel_and_unchanged_done_preserve_existing_dirty_parent() = fixture { app, _ ->
        replaceText("Synthetic flow", "Edited flow")
        edit(0)
        replaceText("original", "cancelled")
        action(En.cancel)
        edit(0)
        action(En.done)
        click(compose.onNodeWithContentDescription(En.ux.back))
        compose.onNodeWithContentDescription(En.discard).assertExists()
        action(En.save)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().isEmpty() }
        val saved = runBlocking { app.repository.listAutoflows().single { it.id == "sheet-test" } }
        assertEquals("Edited flow", saved.name)
        assertEquals("original", saved.wake_phrase)
    }

    @Test
    fun done_keeps_existing_save_validation() = fixture { app, _ ->
        edit(1)
        choose(En.postActionFolderExport)
        action(En.done)
        save()
        compose.onNodeWithText(En.ux.autoFlowNeedExportFolder).assertExists()
        val saved = runBlocking { app.repository.listAutoflows().single { it.id == "sheet-test" } }
        assertEquals(null, saved.post_action)
    }
}
