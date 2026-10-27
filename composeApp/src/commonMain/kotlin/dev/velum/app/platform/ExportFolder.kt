package dev.velum.app.platform

import androidx.compose.runtime.Composable

/** Result of writing a Markdown note into a persisted SAF / local export folder. */
sealed class ExportFolderResult {
    data class Ok(val displayPath: String) : ExportFolderResult()
    data class Failed(val message: String) : ExportFolderResult()
}

/** Persistable folder for Obsidian / Syncthing / local vault export. */
expect object ExportFolderStore {
    fun currentUri(): String?
    fun clear()
}

@Composable
expect fun rememberExportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit

/**
 * Write [markdown] as [filename] into the persisted export folder.
 * Returns [ExportFolderResult.Failed] when no folder is configured.
 */
expect fun writeMarkdownToExportFolder(filename: String, markdown: String): ExportFolderResult
