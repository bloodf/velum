package dev.velum.app.platform

import androidx.compose.runtime.Composable

/**
 * Opens a multi-format audio document picker (SAF on Android).
 * Supported: mp3, m4a/aac, wav, ogg/opus, flac, amr, 3gp, webm, mp4 audio.
 * [onFailed] runs when the chosen document cannot be read (provider error, revoked grant).
 */
@Composable
expect fun rememberAudioImporter(onFailed: () -> Unit = {}, onPicked: (path: String) -> Unit): () -> Unit
