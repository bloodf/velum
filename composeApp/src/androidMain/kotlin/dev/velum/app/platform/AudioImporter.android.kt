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
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val displayName = displayNameOf(context, uri)
                val name = sanitizeImportFileName(displayName, "${UUID.randomUUID()}.audio")
                val dest = File(context.cacheDir, "imports").apply { mkdirs() }
                val outFile = File(dest, "${System.currentTimeMillis()}_$name")
                if (copyToCache({ context.contentResolver.openInputStream(uri) }, outFile)) {
                    dev.velum.app.importing.ImportDisplayNames.put(outFile.absolutePath, displayName)
                    outFile.absolutePath
                } else {
                    null
                }
            }
            if (path != null) onPicked(path) else onFailed()
        }
    }
    return remember(launcher) {
        {
            launcher.launch(ImportAudioFormats.mimeTypes)
        }
    }
}

actual fun deleteImportCopies() {
    val context = appContextOrNull() ?: return
    File(context.cacheDir, "imports").deleteRecursively()
}

/**
 * Copies a picked document into the cache. A provider error (offline Drive, revoked grant) or an empty
 * stream returns false and leaves no partial file; cancellation still propagates.
 */
internal fun copyToCache(open: () -> java.io.InputStream?, outFile: File): Boolean {
    try {
        open()?.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
    } catch (cancelled: CancellationException) {
        outFile.delete()
        throw cancelled
    } catch (failure: Exception) {
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
