package dev.velum.app.platform

import androidx.compose.runtime.Composable

/**
 * Returns a lambda that opens a platform file picker for audio.
 * Invokes [onPicked] with an absolute path when the user selects a file.
 */
@Composable
expect fun rememberAudioImporter(onPicked: (path: String) -> Unit): () -> Unit
