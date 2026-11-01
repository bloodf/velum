package dev.velum.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.velum.app.ui.theme.DarkVelumColors
import dev.velum.app.ui.theme.LightVelumColors
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// #118/#87: Velum* getters must follow VelumTheme in the same frame.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThemeTokenObservableTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun velum_bg_recomposes_with_new_palette_in_same_frame() {
        var dark by mutableStateOf(false)
        var seen = Color.Unspecified
        var count = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            VelumTheme(darkTheme = dark) { seen = VelumBg; count++ }
        }
        compose.waitForIdle()
        assertEquals(LightVelumColors.bg, seen)
        dark = true
        compose.waitForIdle()
        assertEquals(DarkVelumColors.bg, seen)
        assertTrue(count <= 2, "recomposed $count times") // initial + one flip; no loop
    }
}
