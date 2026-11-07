package dev.velum.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.PlatformBackHandler
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #300: system Back with the Create menu open closes the menu and keeps the activity alive. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FabOverlayBackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity get() = controller.get()

    private var open by mutableStateOf(true)

    @org.junit.After fun tearDown() { controller.pause().stop().destroy() }

    private fun menuShown() = compose.onAllNodesWithText(En.importAudio).fetchSemanticsNodes().isNotEmpty()

    private fun setContent() = activity.setContent {
        CompositionLocalProvider(LocalStrings provides En) {
            VelumTheme(darkTheme = false) {
                FabOverlay(visible = open, onStartRecording = {}, onImportAudio = {}, onDismiss = { open = false })
            }
        }
    }

    @Test fun back_closes_the_open_menu_and_does_not_finish_the_activity() {
        setContent()
        compose.waitForIdle()
        assertTrue(menuShown(), "menu should be open")
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(open, "Back must dismiss the menu")
        assertFalse(activity.isFinishing, "Back must not leave the app")
    }

    @Test fun back_with_the_menu_closed_is_not_consumed() {
        open = false
        setContent()
        compose.waitForIdle()
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(activity.isFinishing, "with nothing to close, Back keeps its default")
    }

    @Test fun platform_back_handler_runs_only_while_enabled() {
        var calls = 0
        var enabled by mutableStateOf(true)
        activity.setContent { PlatformBackHandler(enabled) { calls++ } }
        compose.waitForIdle()
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, calls)
        enabled = false
        compose.waitForIdle()
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, calls)
    }
}
