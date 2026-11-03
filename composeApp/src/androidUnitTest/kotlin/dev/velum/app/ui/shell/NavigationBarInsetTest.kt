package dev.velum.app.ui.shell

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #299: with 3-button navigation the bottom bar, Create button and Create menu clear the navigation bar. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class NavigationBarInsetTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun show(navBarPx: Int, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { content() } }
                }
            }
        }
        compose.waitForIdle()
        activity.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun rootBottom() = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    @Test fun tabs_and_the_create_button_sit_above_the_navigation_bar() {
        val navBar = 126
        show(navBar) { VelumBottomBar(ShellTab.Files, fabOpen = false, onFiles = {}, onExplore = {}, onFab = {}) }
        val limit = rootBottom() - navBar
        val files = compose.onNodeWithContentDescription(En.allFiles).fetchSemanticsNode().boundsInRoot
        val explore = compose.onNodeWithContentDescription(En.explore).fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithContentDescription(En.create).fetchSemanticsNode().boundsInRoot
        assertTrue(files.bottom <= limit && explore.bottom <= limit, "tabs $files $explore must end above $limit")
        assertTrue(create.bottom <= limit, "Create button $create must end above $limit")
    }

    @Test fun the_create_menu_clears_the_navigation_bar() {
        val navBar = 126
        show(navBar) { FabOverlay(visible = true, onStartRecording = {}, onImportAudio = {}, onDismiss = {}) }
        val limit = rootBottom() - navBar
        val import = compose.onNodeWithContentDescription(En.importAudio).fetchSemanticsNode().boundsInRoot
        assertTrue(import.bottom <= limit, "menu action $import must end above $limit")
    }

    @Test fun no_navigation_bar_leaves_the_layout_unchanged() {
        show(0) { VelumBottomBar(ShellTab.Files, fabOpen = false, onFiles = {}, onExplore = {}, onFab = {}) }
        val files = compose.onNodeWithContentDescription(En.allFiles).fetchSemanticsNode().boundsInRoot
        // A 64 dp bar: the icon is centred in it, within 64 dp (density 1.0 here) of the bottom.
        assertTrue(rootBottom() - files.bottom < 64f, "tab $files")
    }
}
