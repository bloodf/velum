package dev.velum.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.velum.app.ui.theme.DarkVelumColors
import dev.velum.app.ui.theme.LightVelumColors
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Typography colours must come from the active palette, not the light one.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TypographyThemeColorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun text_styles_follow_the_active_palette() {
        var dark by mutableStateOf(true)
        var display = Color.Unspecified
        var body = Color.Unspecified
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            VelumTheme(darkTheme = dark) {
                Text("a", style = MaterialTheme.typography.displayLarge, onTextLayout = { display = it.layoutInput.style.color })
                Text("b", style = MaterialTheme.typography.bodyMedium, onTextLayout = { body = it.layoutInput.style.color })
            }
        }
        compose.waitForIdle()
        assertEquals(DarkVelumColors.ink, display)
        assertEquals(DarkVelumColors.inkSecondary, body)
        dark = false
        compose.waitForIdle()
        assertEquals(LightVelumColors.ink, display)
        assertEquals(LightVelumColors.inkSecondary, body)
    }

    // Styles without an explicit colour take LocalContentColor; under a Surface that is onSurface.
    @Test fun uncoloured_style_resolves_to_dark_on_surface() {
        var title = Color.Unspecified
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            VelumTheme(darkTheme = true) {
                Surface {
                    Text("c", style = MaterialTheme.typography.titleLarge, onTextLayout = { title = it.layoutInput.style.color })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(DarkVelumColors.ink, title)
    }
}
