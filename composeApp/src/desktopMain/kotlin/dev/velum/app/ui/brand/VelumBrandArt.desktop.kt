package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

@Composable
actual fun VelumHero(
    modifier: Modifier,
    dark: Boolean,
    contentDescription: String?,
) {
    val path = if (dark) "icons/velum-hero-dark.png" else "icons/velum-hero-light.png"
    Image(
        painter = painterResource(path),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth(),
        contentScale = ContentScale.Crop,
    )
}

@Composable
actual fun VelumWordmark(
    modifier: Modifier,
    height: Dp,
    contentDescription: String?,
) {
    Image(
        painter = painterResource("icons/velum-wordmark-light.png"),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth().height(height),
        contentScale = ContentScale.Fit,
    )
}
