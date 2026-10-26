package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberJsonFileImporter(onPicked: (jsonText: String) -> Unit): () -> Unit {
    return remember { { /* iOS host deferred */ } }
}
