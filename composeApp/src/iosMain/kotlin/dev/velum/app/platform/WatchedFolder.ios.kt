package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult

@Composable
actual fun rememberImportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit =
    remember { { } }

actual suspend fun syncWatchedFolders(pipeline: AudioImportPipeline): List<ImportResult> =
    emptyList()
