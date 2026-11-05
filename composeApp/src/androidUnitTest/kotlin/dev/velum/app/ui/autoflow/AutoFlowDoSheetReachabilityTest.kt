package dev.velum.app.ui.autoflow

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class AutoFlowDoSheetReachabilityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun long_do_sheet_reaches_controls_and_preserves_cancel_and_done_drafts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("autoflow-reachability").toString())
        var activity: org.robolectric.android.controller.ActivityController<ComponentActivity>? = null
        var container: AppContainer? = null
        try {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            activity = controller
            controller.setup()
            val app = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
            container = app
            runBlocking {
                repeat(12) { index ->
                    app.repository.upsertTemplate("synthetic-$index", "Synthetic template %02d".format(index), "!synthetic", "", "Synthetic prompt")
                }
                app.repository.upsertAutoflow(
                    id = "reachability", name = "Synthetic flow", enabled = true, wakePhrase = "original",
                    durationMinSeconds = 0, durationMaxSeconds = 10800, sourceFilter = "note_pro",
                    templateId = "synthetic-0", autoLabelVoice = false, language = "auto", notify = true,
                )
            }
            val saves = AtomicInteger()
            val visible = mutableStateOf(true)
            controller.get().setContent {
                CompositionLocalProvider(LocalStrings provides En, LocalDensity provides Density(3f, 1.5f)) {
                    VelumTheme(darkTheme = false) {
                        if (visible.value) AutoFlowEditScreen(app, "reachability", onBack = {
                            visible.value = false
                            saves.incrementAndGet()
                        })
                    }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().size == 2 }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic template 00").fetchSemanticsNodes().isNotEmpty() }
            fun openDo() = compose.onAllNodesWithText(En.edit)[1].performScrollTo().performClick()
            val inSheet = hasAnyAncestor(isDialog())
            fun reach(label: String) = compose.onNode(hasText(label) and inSheet).performScrollTo().assertIsDisplayed()
            fun action(label: String) = compose.onNode(hasContentDescription(label) and inSheet).performScrollTo().assertIsDisplayed().performClick()
            fun choose(label: String) = compose.onNode(hasText(label) and hasClickAction() and inSheet).performScrollTo().assertIsDisplayed().performClick()

            openDo()
            repeat(12) {
                compose.onNode(hasText("Synthetic template %02d".format(it)) and hasClickAction() and inSheet)
                    .performScrollTo().assertIsDisplayed()
            }
            reach(En.autoSpeakerLabel)
            listOf(En.automatic, En.languagePtBr, En.languageEn, En.postActionNone, En.postActionShare,
                En.postActionCalendar, En.postActionFolderExport, En.postActionWebhook,
                En.settingsUi.modelForAutoFlow, En.settingsUi.endpointProfile).forEach(::reach)
            choose(En.languageEn)
            action(En.cancel)
            compose.onNodeWithText(En.save).performClick()
            compose.waitUntil(20_000) {
                saves.get() == 1 && compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("auto", runBlocking { app.repository.listAutoflows().single { it.id == "reachability" }.language })
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().size == 2 }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic template 00").fetchSemanticsNodes().isNotEmpty() }
            openDo()
            choose(En.languagePtBr)
            action(En.done)
            compose.onNode(hasText(En.languagePtBr) and !inSheet).assertExists()
            compose.onNodeWithText(En.save).performClick()
            compose.waitUntil(20_000) {
                saves.get() == 2 && compose.onAllNodesWithText(En.edit).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("pt-BR", runBlocking { app.repository.listAutoflows().single { it.id == "reachability" }.language })
        } finally {
            try {
                activity?.pause()?.stop()?.destroy()
            } finally {
                try {
                    container?.databaseHandle?.driver?.close()
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }
}
