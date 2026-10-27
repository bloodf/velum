package dev.velum.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class VelumColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val inkSecondary: Color,
    val divider: Color,
    /** Filled primary control background (black in light, near-white in dark). */
    val black: Color,
    /** Label/icon on [black] — must stay ≥4.5:1 (WCAG AA). */
    val onBlack: Color,
    val surfaceVariant: Color,
    val disabledFill: Color,
    val onDisabled: Color,
    /** Soft warning / missing-model banner fill. */
    val warningContainer: Color,
    /** Title/body on [warningContainer] — ≥4.5:1. */
    val onWarningContainer: Color,
    val aiPurple: Color,
    val aiCyan: Color,
)

/** Light palette. Secondary ink ≥4.5:1 on white (#6C6C70 ≈ 5.3:1). */
val LightVelumColors = VelumColors(
    bg = Color(0xFFF9F9F9),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF000000),
    inkSecondary = Color(0xFF6C6C70),
    divider = Color(0xFFE5E5EA),
    black = Color(0xFF000000),
    onBlack = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F2F7),
    disabledFill = Color(0xFFC7C7CC),
    onDisabled = Color(0xFF3A3A3C),
    warningContainer = Color(0xFFFFF7ED),
    onWarningContainer = Color(0xFF9A3412),
    aiPurple = Color(0xFF8B5CF6),
    aiCyan = Color(0xFF22D3EE),
)

/** Dark surfaces: charcoal, not purple glow. Secondary ≥4.5:1 on #121212. */
val DarkVelumColors = VelumColors(
    bg = Color(0xFF121212),
    surface = Color(0xFF1C1C1E),
    ink = Color(0xFFF5F5F7),
    inkSecondary = Color(0xFFAEAEB2),
    divider = Color(0xFF38383A),
    black = Color(0xFFF5F5F7),
    onBlack = Color(0xFF121212),
    surfaceVariant = Color(0xFF2C2C2E),
    disabledFill = Color(0xFF3A3A3C),
    onDisabled = Color(0xFFE5E5EA),
    warningContainer = Color(0xFF3B2A1A),
    onWarningContainer = Color(0xFFFFB74A),
    aiPurple = Color(0xFFA78BFA),
    aiCyan = Color(0xFF67E8F9),
)

val LocalVelumColors = staticCompositionLocalOf { LightVelumColors }

/** Active palette bridge so existing Velum* call sites follow [VelumTheme]. */
internal object VelumTokenBridge {
    @Volatile
    var current: VelumColors = LightVelumColors
}

val VelumBg: Color get() = VelumTokenBridge.current.bg
val VelumSurface: Color get() = VelumTokenBridge.current.surface
val VelumInk: Color get() = VelumTokenBridge.current.ink
val VelumInkSecondary: Color get() = VelumTokenBridge.current.inkSecondary
val VelumDivider: Color get() = VelumTokenBridge.current.divider
val VelumBlack: Color get() = VelumTokenBridge.current.black
val VelumOnBlack: Color get() = VelumTokenBridge.current.onBlack
val VelumDisabledFill: Color get() = VelumTokenBridge.current.disabledFill
val VelumOnDisabled: Color get() = VelumTokenBridge.current.onDisabled
val VelumWarningContainer: Color get() = VelumTokenBridge.current.warningContainer
val VelumOnWarningContainer: Color get() = VelumTokenBridge.current.onWarningContainer
val VelumAiPurple: Color get() = VelumTokenBridge.current.aiPurple
val VelumAiCyan: Color get() = VelumTokenBridge.current.aiCyan

@Composable
@ReadOnlyComposable
fun velumColors(): VelumColors = LocalVelumColors.current

private val lightScheme = lightColorScheme(
    primary = LightVelumColors.black,
    onPrimary = LightVelumColors.onBlack,
    primaryContainer = LightVelumColors.surfaceVariant,
    onPrimaryContainer = LightVelumColors.ink,
    secondary = LightVelumColors.inkSecondary,
    onSecondary = LightVelumColors.onBlack,
    secondaryContainer = LightVelumColors.surfaceVariant,
    onSecondaryContainer = LightVelumColors.ink,
    background = LightVelumColors.bg,
    onBackground = LightVelumColors.ink,
    surface = LightVelumColors.surface,
    onSurface = LightVelumColors.ink,
    onSurfaceVariant = LightVelumColors.inkSecondary,
    outline = LightVelumColors.divider,
    surfaceVariant = LightVelumColors.surfaceVariant,
    error = Color(0xFFFF3B30),
    onError = Color.White,
    errorContainer = LightVelumColors.warningContainer,
    onErrorContainer = LightVelumColors.onWarningContainer,
)

private val darkScheme = darkColorScheme(
    primary = DarkVelumColors.black,
    onPrimary = DarkVelumColors.onBlack,
    primaryContainer = DarkVelumColors.surfaceVariant,
    onPrimaryContainer = DarkVelumColors.ink,
    secondary = DarkVelumColors.inkSecondary,
    onSecondary = DarkVelumColors.onBlack,
    secondaryContainer = DarkVelumColors.surfaceVariant,
    onSecondaryContainer = DarkVelumColors.ink,
    background = DarkVelumColors.bg,
    onBackground = DarkVelumColors.ink,
    surface = DarkVelumColors.surface,
    onSurface = DarkVelumColors.ink,
    onSurfaceVariant = DarkVelumColors.inkSecondary,
    outline = DarkVelumColors.divider,
    surfaceVariant = DarkVelumColors.surfaceVariant,
    error = Color(0xFFFF453A),
    onError = Color(0xFF121212),
    errorContainer = DarkVelumColors.warningContainer,
    onErrorContainer = DarkVelumColors.onWarningContainer,
)

private val type = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Thin,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
        color = LightVelumColors.ink,
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
        color = LightVelumColors.inkSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
)

val AiGradientBrush: Brush
    get() {
        val c = VelumTokenBridge.current
        return Brush.horizontalGradient(listOf(c.aiPurple, c.aiCyan))
    }

@Composable
fun VelumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkVelumColors else LightVelumColors
    DisposableEffect(palette) {
        VelumTokenBridge.current = palette
        onDispose { }
    }
    CompositionLocalProvider(LocalVelumColors provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme else lightScheme,
            typography = type,
            content = content,
        )
    }
}
