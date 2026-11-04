package dev.velum.app.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.importing.WATCHED_SOURCE
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
                val result = pipeline.importFile(
                    absolutePath = entry.absoluteCopyPath,
                    source = WATCHED_SOURCE,
                    titleHint = entry.displayName.substringBeforeLast('.'),
                    startedAtEpochSeconds = entry.lastModifiedMs / 1000L,
                )
                results += result
                // A failure is retried on the next sync; anything that landed in the library is not copied again.
                if (result !is ImportResult.Failed && entry.fileKey.isNotEmpty()) WatchedFolderStore.markImported(context, entry.fileKey)
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

actual fun releaseFolderGrants() {
    val resolver = appContextOrNull()?.contentResolver ?: return
    resolver.persistedUriPermissions.toList().forEach { grant ->
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        // A grant the provider already dropped must not stop the rest of the wipe.
        try {
            resolver.releasePersistableUriPermission(grant.uri, flags)
        } catch (_: SecurityException) {
        }
    }
}
