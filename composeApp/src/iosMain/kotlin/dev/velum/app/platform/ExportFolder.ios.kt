package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual object ExportFolderStore {
    actual fun currentUri(): String? = null
    actual fun clear() = Unit
}

@Composable
actual fun rememberExportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit =
    remember { { /* iOS export folder deferred */ } }

actual fun writeMarkdownToExportFolder(filename: String, markdown: String): ExportFolderResult =
    ExportFolderResult.Failed("Export folder is not available on this platform yet")
