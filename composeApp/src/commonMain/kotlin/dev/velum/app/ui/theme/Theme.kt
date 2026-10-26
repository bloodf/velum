package dev.velum.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Soft mist / ink — light, AI-flavored, not purple-default.
private val Mist = Color(0xFFF4F7FB)
private val MistElevated = Color(0xFFFFFFFF)
private val Ink = Color(0xFF142033)
private val InkMuted = Color(0xFF5A6B7F)
private val Accent = Color(0xFF0F766E) // teal
private val AccentSoft = Color(0xFFCCFBF1)
private val OutlineSoft = Color(0xFFD5DEE8)

private val VelumLightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF334155),
    onSecondary = Color.White,
    background = Mist,
    onBackground = Ink,
    surface = MistElevated,
    onSurface = Ink,
    onSurfaceVariant = InkMuted,
    outline = OutlineSoft,
    surfaceVariant = Color(0xFFE8EEF5),
)

private val VelumTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun VelumTheme(content: @Composable () -> Unit) {
    // Foundation ships a deliberate light look; dark theme comes later.
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = VelumLightColors,
        typography = VelumTypography,
        content = content,
    )
    // Suppress unused warning without branching UI yet.
    @Suppress("UNUSED_EXPRESSION")
    dark
}
