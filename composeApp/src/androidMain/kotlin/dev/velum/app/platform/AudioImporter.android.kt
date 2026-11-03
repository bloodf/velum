package dev.velum.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberAudioImporter(onFailed: () -> Unit, onPicked: (path: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { importPickedDocument(context, uri, onFailed, onPicked) }
    }
    return remember(launcher) {
        {
            launcher.launch(ImportAudioFormats.mimeTypes)
        }
    }
}

private const val TAG = "AudioImporter"

/**
 * Copies [uri] into the import cache and reports the result: [onPicked] with the cached path, or
 * [onFailed] when the provider cannot be read. A cancelled copy removes its partial file and rethrows.
 */
internal suspend fun importPickedDocument(
    context: android.content.Context,
    uri: Uri,
    onFailed: () -> Unit,
    onPicked: (path: String) -> Unit,
    openStream: () -> java.io.InputStream? = { context.contentResolver.openInputStream(uri) },
) {
    var outFile: File? = null
    var displayName: String? = null
    val path = try {
        withContext(Dispatchers.IO) {
            displayName = displayNameOf(context, uri)
            val name = sanitizeImportFileName(displayName, "${UUID.randomUUID()}.audio")
            val dest = File(context.cacheDir, "imports").apply { mkdirs() }
            val target = File(dest, "${System.currentTimeMillis()}_$name").also { outFile = it }
            val copied = copyToCache(openStream, target) {
                // Class name only: the message and path can carry the user's file names.
                android.util.Log.w(TAG, "Import copy failed (${it::class.java.simpleName})")
            }
            if (copied) {
                target.absolutePath
            } else {
                null
            }
        }
    } catch (cancelled: CancellationException) {
        outFile?.delete()
        throw cancelled
    }
    if (path != null) {
        // Only for a copy that survived: a cancelled or failed import must not leave a registry entry.
        dev.velum.app.importing.ImportDisplayNames.put(path, displayName)
        onPicked(path)
    } else {
        onFailed()
    }
}

/**
 * Copies a picked document into the cache. A provider error (offline Drive, revoked grant) or an empty
 * stream returns false and leaves no partial file; cancellation still propagates.
 */
internal fun copyToCache(
    open: () -> java.io.InputStream?,
    outFile: File,
    onError: (Exception) -> Unit = {},
): Boolean {
    try {
        open()?.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
    } catch (cancelled: CancellationException) {
        outFile.delete()
        throw cancelled
    } catch (failure: Exception) {
        onError(failure)
        outFile.delete()
        return false
    }
    if (outFile.exists() && outFile.length() > 0L) return true
    outFile.delete()
    return false
}

/** The provider's display name ("two-speakers.wav"); the last path segment is an opaque id ("msf:1000000063") for document providers. */
internal fun displayNameOf(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
