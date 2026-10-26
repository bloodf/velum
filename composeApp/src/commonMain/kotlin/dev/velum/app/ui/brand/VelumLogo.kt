package dev.velum.app.ui.brand

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Theme-aware Velum mark: light 3D (black V on white) by day, dark 3D (glass V on black) by night.
 * Launcher icons stay on the light master (Android cannot theme the home-screen icon).
 */
@Composable
expect fun VelumLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    dark: Boolean = isSystemInDarkTheme(),
    contentDescription: String? = "Velum",
)
