package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.velum.app.R

@Composable
actual fun VelumHero(
    modifier: Modifier,
    dark: Boolean,
    contentDescription: String?,
) {
    val resId = if (dark) R.drawable.ic_velum_hero_dark else R.drawable.ic_velum_hero_light
    // Crop (not FillWidth): FirstRun caps hero height so Skip stays on-screen;
    // FillWidth would ignore heightIn and push the fold (#106).
    Image(
        painter = painterResource(resId),
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
        painter = painterResource(R.drawable.ic_velum_wordmark_light),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth().height(height),
        contentScale = ContentScale.Fit,
    )
}
