package dev.velum.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.model.AiModelInfo
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.TaskModelBinding
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class TaskBindingEditorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun boundProfileControlsChoicesAndUncachedProfileAllowsManualSave() {
        var binding by mutableStateOf(TaskModelBinding(profileId = "beta"))
        val profiles = listOf("alpha", "beta", "empty").map { ApiEndpointProfile(it, it, "https://example.invalid") }
        val caches = mapOf("alpha" to listOf(AiModelInfo("alpha-model")), "beta" to listOf(AiModelInfo("beta-model")))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.runOnUiThread {
                activity.setContent {
                    CompositionLocalProvider(LocalStrings provides En) {
                        VelumTheme(darkTheme = false) {
                            TaskBindingEditor(AiApiTask.Summary, "Summary", binding, profiles, "alpha", caches, "") { _, value -> binding = value }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("beta-model").assertExists().performClick()
            compose.onNodeWithText("alpha-model").assertDoesNotExist()
            assertEquals(TaskModelBinding("beta", "beta-model"), binding)

            compose.onNodeWithContentDescription(En.settingsUi.endpointProfile).performClick()
            compose.onNode(hasText("beta-model") and !hasSetTextAction()).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertTextEquals("beta-model")
            compose.onNode(hasSetTextAction()).performTextReplacement("manual-model")
            compose.onNodeWithContentDescription(En.save).performClick()
            assertEquals(TaskModelBinding("empty", "manual-model"), binding)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
