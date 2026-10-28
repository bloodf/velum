package dev.velum.app.platform

import androidx.compose.runtime.Composable
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit =
    rememberWatchedFolderPicker(onPicked)

actual suspend fun syncWatchedFolders(pipeline: AudioImportPipeline): List<ImportResult> =
    withContext(Dispatchers.IO) {
        val context = appContextOrNull() ?: return@withContext emptyList()
        val results = ArrayList<ImportResult>()
        WatchedFolderStore.list(context).forEach { uri ->
            WatchedFolderStore.scanNewAudio(context, uri).forEach { entry ->
                results += pipeline.importFile(
                    absolutePath = entry.absoluteCopyPath,
                    source = "watched",
                    titleHint = entry.displayName.substringBeforeLast('.'),
                    startedAtEpochSeconds = entry.lastModifiedMs / 1000L,
                )
            }
        }
        results
    }

actual fun watchedFolderCount(): Int {
    val context = appContextOrNull() ?: return 0
    return WatchedFolderStore.list(context).size
}

actual fun clearWatchedFolders() {
    val context = appContextOrNull() ?: return
    WatchedFolderStore.clear(context)
}
