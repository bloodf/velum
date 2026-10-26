package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import dev.velum.app.resources.Res
import dev.velum.app.resources.velum_hero_dark
import dev.velum.app.resources.velum_hero_light
import dev.velum.app.resources.velum_wordmark_light
import org.jetbrains.compose.resources.painterResource

@Composable
actual fun VelumHero(
    modifier: Modifier,
    dark: Boolean,
    contentDescription: String?,
) {
    val painter = painterResource(
        if (dark) Res.drawable.velum_hero_dark else Res.drawable.velum_hero_light,
    )
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth(),
        contentScale = ContentScale.FillWidth,
    )
}

@Composable
actual fun VelumWordmark(
    modifier: Modifier,
    height: Dp,
    contentDescription: String?,
) {
    Image(
        painter = painterResource(Res.drawable.velum_wordmark_light),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth().height(height),
        contentScale = ContentScale.Fit,
    )
}
