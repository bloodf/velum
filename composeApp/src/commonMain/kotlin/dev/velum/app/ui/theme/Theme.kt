package dev.velum.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Matched to Plaud screenshot visual language (Velum branding).
val VelumBg = Color(0xFFF9F9F9)
val VelumSurface = Color(0xFFFFFFFF)
val VelumInk = Color(0xFF000000)
val VelumInkSecondary = Color(0xFF8E8E93)
val VelumDivider = Color(0xFFE5E5EA)
val VelumBlack = Color(0xFF000000)
val VelumAiPurple = Color(0xFF8B5CF6)
val VelumAiCyan = Color(0xFF22D3EE)

private val colors = lightColorScheme(
    primary = VelumBlack,
    onPrimary = Color.White,
    secondary = VelumInkSecondary,
    onSecondary = Color.White,
    background = VelumBg,
    onBackground = VelumInk,
    surface = VelumSurface,
    onSurface = VelumInk,
    onSurfaceVariant = VelumInkSecondary,
    outline = VelumDivider,
    surfaceVariant = Color(0xFFF2F2F7),
    error = Color(0xFFFF3B30),
)

private val type = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Thin,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
        color = VelumInk,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Thin,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = VelumInkSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
)

val AiGradientBrush = Brush.horizontalGradient(listOf(VelumAiPurple, VelumAiCyan))

@Composable
fun VelumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
