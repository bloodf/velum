package dev.velum.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.theme.VelumTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #87: a sensitivity label must never break inside a word ("Balance/d", "Rigoros/a").
 *
 * Proves: at 400dp width and font scales 1.0 / 1.3 / 2.0, in EN and PT-BR, every button has at least as much
 * text width (button width minus its 16dp side padding) as the widest single word of its label measured with the
 * button's text style. Text can only break inside a word when a word is wider than that, so this rules it out.
 * Does not prove: real-device font metrics (Robolectric native graphics uses the host font stack), row height at 2.0x, or other screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class SensitivityOptionsLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var strings by mutableStateOf<Strings>(En)
    private var fontScale by mutableStateOf(1f)
    private lateinit var measurer: TextMeasurer

    @Test
    fun no_label_is_narrower_than_its_widest_word_in_both_locales_and_font_scales() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val baseDensity = activity.get().resources.displayMetrics.density
            activity.get().setContent {
                CompositionLocalProvider(
                    LocalStrings provides strings,
                    LocalDensity provides Density(baseDensity, fontScale),
                ) {
                    measurer = rememberTextMeasurer()
                    VelumTheme(darkTheme = false) {
                        Box(Modifier.width(400.dp)) { SensitivityOptions(strings, 0.55f) {} }
                    }
                }
            }
            for (s in listOf(En, PtBr)) for (scale in listOf(1f, 1.3f, 2f)) {
                strings = s
                fontScale = scale
                compose.waitForIdle()
                val density = Density(baseDensity, scale)
                for ((_, label) in thresholdOptions(s)) {
                    val width = compose.onNodeWithContentDescription(label).fetchSemanticsNode().size.width
                    val available = width - with(density) { 32.dp.toPx() }
                    val widest = label.split(' ').maxOf { word ->
                        measurer.measure(word, TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)).size.width
                    }
                    assertTrue(available >= widest, "'$label' breaks mid-word: ${available}px for a ${widest}px word ($scale x)")
                }
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
