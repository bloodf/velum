package dev.velum.app.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact Material-style width buckets without pulling in material3-adaptive.
 * Expanded (≥840 dp) enables list–detail panes (desktop / large tablets / unfoldeds).
 */
@Immutable
enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded,
}

fun windowWidthSizeClass(width: Dp): WindowWidthSizeClass = when {
    width < 600.dp -> WindowWidthSizeClass.Compact
    width < 840.dp -> WindowWidthSizeClass.Medium
    else -> WindowWidthSizeClass.Expanded
}

@Composable
fun WithWindowWidthSizeClass(
    modifier: Modifier = Modifier,
    content: @Composable (WindowWidthSizeClass, Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        content(windowWidthSizeClass(maxWidth), maxWidth)
    }
}
