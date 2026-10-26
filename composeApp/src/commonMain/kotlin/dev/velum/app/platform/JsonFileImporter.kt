package dev.velum.app.platform

import androidx.compose.runtime.Composable

/**
 * Opens a platform picker for a JSON text file.
 * Invokes [onPicked] with UTF-8 file contents when the user selects one.
 */
@Composable
expect fun rememberJsonFileImporter(onPicked: (jsonText: String) -> Unit): () -> Unit
