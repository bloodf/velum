package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

@Composable
actual fun VelumLogo(
    modifier: Modifier,
    size: Dp,
    dark: Boolean,
    contentDescription: String?,
) {
    val path = if (dark) "icons/velum-logo-dark.png" else "icons/velum-logo-light.png"
    Image(
        painter = painterResource(path),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
