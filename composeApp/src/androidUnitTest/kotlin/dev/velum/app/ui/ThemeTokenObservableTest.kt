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
import dev.velum.app.ui.theme.LocalVelumColors
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertEquals
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

    @Test fun velum_bg_matches_local_palette_on_every_frame_across_flips() {
        var dark by mutableStateOf(false)
        // (LocalVelumColors.bg, VelumBg) per content invocation. They differ only when the
        // global bridge lags the composition-local palette, e.g. an effect-timed write.
        val frames = mutableListOf<Pair<Color, Color>>()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            VelumTheme(darkTheme = dark) { frames += LocalVelumColors.current.bg to VelumBg }
        }
        compose.waitForIdle()
        dark = true
        compose.waitForIdle()
        dark = false
        compose.waitForIdle()

        frames.forEach { (local, bridge) -> assertEquals(local, bridge, "VelumBg lagged palette: $frames") }
        assertEquals(
            listOf(LightVelumColors.bg, DarkVelumColors.bg, LightVelumColors.bg),
            frames.map { it.first },
            "expected exactly initial + one recomposition per flip",
        )
    }
}
