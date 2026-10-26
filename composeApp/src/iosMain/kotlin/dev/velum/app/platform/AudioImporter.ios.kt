package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberAudioImporter(onPicked: (path: String) -> Unit): () -> Unit {
    return remember { { /* iOS host deferred */ } }
}
