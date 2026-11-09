package dev.velum.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

actual fun deleteImportCopies(): Boolean = deleteCacheDir("imports")

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
    openStream: (() -> java.io.InputStream?)? = null,
) {
    var outFile: File? = null
    var displayName: String? = null
    val path = try {
        withContext(Dispatchers.IO) {
            if (openStream == null) {
                val result = copyProviderInPrivateProcess(context, uri, share = false)
                outFile = result?.file
                displayName = result?.displayName
                return@withContext result?.file?.absolutePath
            }
            // Explicit stream injection is a unit-test seam; production always uses private IPC.
            displayName = displayNameOf(context, uri)
            val extension = displayName?.takeIf { ImportAudioFormats.isSupportedFileName(it) }?.substringAfterLast('.')?.lowercase() ?: "audio"
            val dest = File(context.cacheDir, "imports").apply { mkdirs() }
            val target = File(dest, "${UUID.randomUUID()}.$extension")
            val copyContext = currentCoroutineContext()
            val copied = copyToCache(openStream, target, checkCancelled = { copyContext.ensureActive() }) {
                // Class name only: the message and path can carry the user's file names.
                android.util.Log.w(TAG, "Import copy failed (${it::class.java.simpleName})")
            }
            if (copied) {
                outFile = target
                target.absolutePath
            } else {
                null
            }
        }
    } catch (cancelled: CancellationException) {
        outFile?.delete()
        throw cancelled
    } catch (failure: Exception) {
        android.util.Log.w(TAG, "Import copy failed (${failure::class.java.simpleName})")
        null
    }
    if (path != null) {
        // Only for a copy that survived: a cancelled or failed import must not leave a registry entry.
        dev.velum.app.importing.ImportDisplayNames.put(path, displayName)
        onPicked(path)
    } else {
        onFailed()
    }
}

internal const val MAX_PROVIDER_COPY_BYTES = 1_073_741_824L

/**
 * Bounds actual bytes, including providers with absent or incorrect lengths. Only a file created by
 * this invocation is removed on failure. Blocking provider open/read still requires a separate
 * cancellable I/O boundary before a wall-clock deadline can be guaranteed.
 */
internal fun copyToCache(
    open: () -> java.io.InputStream?,
    outFile: File,
    checkCancelled: () -> Unit = {},
    knownLength: Long? = null,
    maxBytes: Long = MAX_PROVIDER_COPY_BYTES,
    onError: (Exception) -> Unit = {},
): Boolean {
    require(maxBytes in 0..MAX_PROVIDER_COPY_BYTES)
    var owned = false
    var complete = false
    try {
        checkCancelled()
        if (knownLength != null && knownLength > maxBytes) throw IOException("Import exceeds size limit")
        open()?.use { input ->
            // CREATE_NEW refuses existing files and symlinks without truncating or deleting them.
            val output = Files.newOutputStream(outFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            owned = true
            output.use {
                copyProviderBytes(input, output, maxBytes, checkCancelled)
            }
        } ?: return false
        checkCancelled()
        complete = true
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onError(failure)
        return false
    } finally {
        if (owned && !complete) outFile.delete()
    }
}

internal fun copyProviderBytes(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long = MAX_PROVIDER_COPY_BYTES,
    checkCancelled: () -> Unit = {},
) {
    require(maxBytes in 0..MAX_PROVIDER_COPY_BYTES)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        checkCancelled()
        val requested = minOf(buffer.size.toLong(), maxBytes - copied + 1).toInt()
        val count = input.read(buffer, 0, requested)
        checkCancelled()
        if (count < 0) break
        if (count > maxBytes - copied) throw IOException("Import exceeds size limit")
        output.write(buffer, 0, count)
        copied += count
    }
    if (copied == 0L) throw IOException("Empty import")
}

/** The provider's display name ("two-speakers.wav"); the last path segment is an opaque id ("msf:1000000063") for document providers. */
internal fun displayNameOf(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment

actual fun deleteShareExports(): Boolean = deleteCacheDir("share")

private fun deleteCacheDir(name: String): Boolean {
    val context = appContextOrNull() ?: return false
    val dir = File(context.cacheDir, name)
    dir.deleteRecursively()
    return !dir.exists()
}
