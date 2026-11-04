package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.compose.runtime.Composable
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.importing.WATCHED_SOURCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit =
    rememberWatchedFolderPicker(onPicked)

actual suspend fun syncWatchedFolders(pipeline: AudioImportPipeline): List<ImportResult> =
    syncWatchedFolders(pipeline, WatchedFolderStore::scanNewAudio)

/** All of it under the wipe gate: a wipe stops the scan and its copying too, not only the imports. */
internal suspend fun syncWatchedFolders(
    pipeline: AudioImportPipeline,
    scan: (Context, String, () -> Unit) -> List<WatchedFolderEntry>,
): List<ImportResult> = pipeline.underWipeGate {
    withContext(Dispatchers.IO) {
        val context = appContextOrNull() ?: return@withContext emptyList()
        val coroutine = currentCoroutineContext()
        val results = ArrayList<ImportResult>()
        WatchedFolderStore.list(context).forEach { uri ->
            val entries = scan(context, uri) { coroutine.ensureActive() }
            try {
                entries.forEach { entry ->
                    coroutine.ensureActive()
                    val result = pipeline.importFile(
                        absolutePath = entry.absoluteCopyPath,
                        source = WATCHED_SOURCE,
                        titleHint = entry.displayName.substringBeforeLast('.'),
                        startedAtEpochSeconds = entry.lastModifiedMs / 1000L,
                    )
                    if (result !is ImportResult.Failed) WatchedFolderStore.markImported(context, entry)
                    results += result
                }
            } finally {
                entries.forEach { java.io.File(it.absoluteCopyPath).delete() }
            }
        }
        results
    }
}.orEmpty()

actual fun watchedFolderCount(): Int {
    val context = appContextOrNull() ?: return 0
    return WatchedFolderStore.list(context).size
}

actual fun clearWatchedFolders() {
    val context = appContextOrNull() ?: return
    WatchedFolderStore.clear(context)
}

actual fun releaseFolderGrants(): Boolean {
    val resolver = appContextOrNull()?.contentResolver ?: return false
    releaseGrants(resolver.persistedUriPermissions.toList()) { uri, flags -> resolver.releasePersistableUriPermission(uri, flags) }
    // Judged by what is left, not by what threw.
    return resolver.persistedUriPermissions.isEmpty()
}

/** Asks to release each grant with the access it actually has. A provider's refusal must not stop the rest. */
internal fun releaseGrants(grants: List<UriPermission>, release: (Uri, Int) -> Unit) {
    grants.forEach { grant ->
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            release(grant.uri, flags)
        } catch (_: SecurityException) {
            // Whatever remains is reported by the caller.
        }
    }
}
