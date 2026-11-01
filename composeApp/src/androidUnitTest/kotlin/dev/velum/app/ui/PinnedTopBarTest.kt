package dev.velum.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.settings.AboutScreen
import dev.velum.app.ui.theme.VelumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// #118: Back must stay composed after the body scrolls (device-walk pressNavBack).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PinnedTopBarTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun back_stays_displayed_after_scrolling_body() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            CompositionLocalProvider(LocalStrings provides En) { VelumTheme(darkTheme = false) { AboutScreen(onBack = {}) } }
        }
        repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
        compose.onNodeWithContentDescription(En.ux.back).assertIsDisplayed()
    }
}
