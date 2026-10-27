package dev.velum.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.velum.app.ui.theme.DarkVelumColors
import dev.velum.app.ui.theme.LightVelumColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG AA contrast (≥4.5:1) for primary filled controls and body ink.
 * Catches dark-theme white-on-white regressions (VelumBlack + hardcoded White).
 */
class ThemeContrastTest {
    @Test
    fun primaryFilledLabelMeetsAa_lightAndDark() {
        assertContrast("light primary", LightVelumColors.black, LightVelumColors.onBlack)
        assertContrast("dark primary", DarkVelumColors.black, DarkVelumColors.onBlack)
        assertContrast("light disabled", LightVelumColors.disabledFill, LightVelumColors.onDisabled)
        assertContrast("dark disabled", DarkVelumColors.disabledFill, DarkVelumColors.onDisabled)
    }

    @Test
    fun bodyInkMeetsAaOnSurfaces() {
        assertContrast("light ink/bg", LightVelumColors.bg, LightVelumColors.ink)
        assertContrast("light secondary/bg", LightVelumColors.bg, LightVelumColors.inkSecondary)
        assertContrast("dark ink/bg", DarkVelumColors.bg, DarkVelumColors.ink)
        assertContrast("dark secondary/bg", DarkVelumColors.bg, DarkVelumColors.inkSecondary)
        assertContrast("light ink/surface", LightVelumColors.surface, LightVelumColors.ink)
        assertContrast("dark ink/surface", DarkVelumColors.surface, DarkVelumColors.ink)
    }

    private fun assertContrast(label: String, background: Color, foreground: Color) {
        val ratio = contrastRatio(background, foreground)
        assertTrue(
            ratio >= 4.5,
            "$label contrast $ratio < 4.5 (bg=${background.toHex()} fg=${foreground.toHex()})",
        )
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Color.toHex(): String {
        fun ch(v: Float) = ((v * 255f).toInt() and 0xff).toString(16).padStart(2, '0')
        return "#${ch(red)}${ch(green)}${ch(blue)}"
    }
}
