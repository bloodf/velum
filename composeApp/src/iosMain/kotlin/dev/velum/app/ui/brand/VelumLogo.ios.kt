package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import dev.velum.app.resources.Res
import dev.velum.app.resources.velum_logo_dark
import dev.velum.app.resources.velum_logo_light
import org.jetbrains.compose.resources.painterResource

@Composable
actual fun VelumLogo(
    modifier: Modifier,
    size: Dp,
    dark: Boolean,
    contentDescription: String?,
) {
    val painter = painterResource(
        if (dark) Res.drawable.velum_logo_dark else Res.drawable.velum_logo_light,
    )
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
