package dev.velum.app.ui.onboarding

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

/** #288: the first-run scroll viewport starts below the status bar, so scrolled text never draws under it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class FirstRunInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun showWithStatusBar(topPx: Int) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        FirstRunScreen({}, {}, {}, {}, onUseApi = {}, onSkip = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        activity.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, topPx, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    @Test fun scroll_viewport_starts_below_the_status_bar_before_and_after_scrolling() {
        val statusBar = 120
        showWithStatusBar(statusBar)
        val scroller = compose.onNode(hasScrollAction())
        val top = scroller.fetchSemanticsNode().boundsInRoot.top
        assertTrue(top >= statusBar, "scroll viewport top $top must be at or below the status bar ($statusBar px)")
        repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
        val after = scroller.fetchSemanticsNode().boundsInRoot.top
        assertTrue(after >= statusBar, "the viewport does not move when content scrolls: $after")
    }
}
