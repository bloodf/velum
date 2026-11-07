package dev.velum.app.ui.a11y

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.settings.LangOption
import dev.velum.app.ui.settings.LanguagePickerSheet
import dev.velum.app.ui.templates.TemplateDetailScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #332: labelled 48dp close controls and pickers that expose their selected state. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class AccessibilitySemanticsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        a.runOnUiThread {
            a.setContent { CompositionLocalProvider(LocalStrings provides En) { VelumTheme(darkTheme = false) { content() } } }
        }
        compose.waitForIdle()
    }

    @Test fun template_close_is_labelled_clickable_and_at_least_48dp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-a11y").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            show { TemplateDetailScreen(container, templateId = "missing", onBack = {}) }
            // The detail screen has a "Close" button at the bottom too; the control under test is the top icon.
            val density = context.resources.displayMetrics.density
            val topClose = compose.onAllNodes(hasContentDescription(En.close) and hasClickAction())
                .fetchSemanticsNodes().filter { it.boundsInRoot.top / density < 100f }
            assertEquals(1, topClose.size, "expected one labelled, clickable Close icon at the top, found ${topClose.size}")
            val size = topClose.single().size
            assertTrue(size.width / density >= 48f && size.height / density >= 48f, "close target $size px at density $density")
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun language_picker_exposes_the_selected_option_as_a_radio_button() {
        show {
            LanguagePickerSheet(
                title = "Language",
                options = listOf(LangOption("en", "English"), LangOption("pt", "Portuguese")),
                selected = "pt",
                onSelect = {},
                onDismiss = {},
            )
        }
        fun cfg(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().config
        assertEquals(true, cfg("Portuguese").getOrNull(SemanticsProperties.Selected))
        assertEquals(false, cfg("English").getOrNull(SemanticsProperties.Selected))
        assertEquals(Role.RadioButton, cfg("Portuguese").getOrNull(SemanticsProperties.Role))
    }

    @Test fun templates_back_is_a_48dp_target() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-a11y-back").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            show { dev.velum.app.ui.templates.TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
            val density = context.resources.displayMetrics.density
            val back = compose.onAllNodes(hasContentDescription(En.ux.back) and hasClickAction()).fetchSemanticsNodes()
                .minByOrNull { it.boundsInRoot.top }!! // the top-left control
            assertEquals(Role.Button, back.config.getOrNull(SemanticsProperties.Role))
            assertTrue(back.size.width / density >= 48f && back.size.height / density >= 48f, "back target ${back.size} px at density $density")
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun withContainer(block: (AppContainer, Float) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-a11y-rows").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            block(container, context.resources.displayMetrics.density)
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun memory_glossary_row_without_a_definition_is_at_least_48dp() = withContainer { container, density ->
        kotlinx.coroutines.runBlocking { container.repository.addGlossaryTerm("g1", "OKR") }
        show { dev.velum.app.ui.settings.MemoryScreen(container, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("OKR")).fetchSemanticsNodes().isNotEmpty() }
        val row = compose.onAllNodes(hasText("OKR") and hasClickAction()).fetchSemanticsNodes().first()
        assertTrue(row.size.height / density >= 48f, "glossary row height ${row.size.height / density} dp")
    }

    @Test fun model_picker_row_without_an_owner_is_at_least_48dp() {
        show {
            dev.velum.app.ui.settings.TaskModelPicker(
                title = "Summary",
                selectedId = "gpt-x",
                models = listOf(dev.velum.core.model.AiModelInfo("gpt-x")),
                onSelect = {},
            )
        }
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        val row = compose.onAllNodes(hasText("gpt-x") and hasClickAction()).fetchSemanticsNodes().first()
        assertTrue(row.size.height / density >= 48f, "model row height ${row.size.height / density} dp")
    }

    @Test fun autoflow_search_row_keeps_its_height_when_typing() {
        show { dev.velum.app.ui.autoflow.AutoFlowTemplateLibraryScreen(onBack = {}, onUseTemplate = {}) }
        // The category chips sit directly below the search row, so any change of the row height moves them.
        fun chipTop() = compose.onAllNodes(hasText(En.everything)).fetchSemanticsNodes().first().boundsInRoot.top
        val before = chipTop()
        compose.onNode(hasSetTextAction()).performTextInput("a")
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasContentDescription(En.clearSearch)).fetchSemanticsNodes().isNotEmpty(), "clear shown")
        assertEquals(before, chipTop(), "the chips moved when the clear button appeared")
    }
}
