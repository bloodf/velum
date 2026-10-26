package dev.velum.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.velum.app.R

@Composable
actual fun VelumLogo(
    modifier: Modifier,
    size: Dp,
    dark: Boolean,
    contentDescription: String?,
) {
    val resId = if (dark) R.drawable.ic_velum_logo_dark else R.drawable.ic_velum_logo_light
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
