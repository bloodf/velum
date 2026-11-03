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
}
