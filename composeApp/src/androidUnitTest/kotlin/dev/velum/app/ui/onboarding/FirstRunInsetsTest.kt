package dev.velum.app.ui.onboarding

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
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

    private fun show(statusBarPx: Int, cutoutLeftPx: Int = 0, cutoutRightPx: Int = 0) {
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
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutoutLeftPx, 0, cutoutRightPx, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun viewport() = compose.onNode(hasScrollAction()).fetchSemanticsNode()

    @Test fun scroll_viewport_starts_below_the_status_bar() {
        show(statusBarPx = 120)
        val top = viewport().boundsInRoot.top
        assertTrue(top >= 120, "scroll viewport top $top must be at or below the status bar (120 px)")
    }

    @Test fun scrolling_moves_content_inside_a_viewport_that_stays_below_the_status_bar() {
        show(statusBarPx = 120)
        val topBefore = viewport().boundsInRoot.top
        val scrolledBefore = viewport().config[SemanticsProperties.VerticalScrollAxisRange].value()
        repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
        val node = viewport()
        assertTrue(node.config[SemanticsProperties.VerticalScrollAxisRange].value() > scrolledBefore, "the content must actually have scrolled")
        // The viewport is the clip, so scrolled-off content is cut at its top edge, below the bar.
        assertTrue(node.boundsInRoot.top == topBefore && node.boundsInRoot.top >= 120, "viewport top ${node.boundsInRoot.top} (was $topBefore)")
    }

    @Test fun a_landscape_side_cutout_keeps_content_clear_of_it() {
        show(statusBarPx = 0, cutoutLeftPx = 90, cutoutRightPx = 60)
        val node = viewport().boundsInRoot
        assertTrue(node.left >= 90 && node.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right - 60, "viewport $node must clear both cutouts")
    }
}
