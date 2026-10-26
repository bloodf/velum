package dev.velum.app.ui.brand

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Portrait splash/hero artwork (ribbons + V). Light vs dark theme. */
@Composable
expect fun VelumHero(
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    contentDescription: String? = null,
)

/** Horizontal wordmark (3D V + “Velum” text) for About. Light master only. */
@Composable
expect fun VelumWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    contentDescription: String? = "Velum",
)
