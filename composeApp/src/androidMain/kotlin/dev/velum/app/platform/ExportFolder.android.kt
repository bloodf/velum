package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

actual object ExportFolderStore {
    private const val PREFS = "velum_export_folder"
    private const val KEY_URI = "tree_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY_URI, treeUri.toString()).apply()
    }

    actual fun currentUri(): String? =
        appContextOrNull()?.let { prefs(it).getString(KEY_URI, null) }

    actual fun clear() {
        appContextOrNull()?.let { prefs(it).edit().remove(KEY_URI).apply() }
    }
}

@Composable
actual fun rememberExportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ExportFolderStore.remember(context, uri)
        onPicked(uri.toString())
    }
    return remember(launcher) { { launcher.launch(null) } }
}

actual fun writeMarkdownToExportFolder(filename: String, markdown: String): ExportFolderResult {
    val context = appContextOrNull()
        ?: return ExportFolderResult.Failed("Android context unavailable")
    val uriString = ExportFolderStore.currentUri()
        ?: return ExportFolderResult.Failed("No export folder selected")
    val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        ?: return ExportFolderResult.Failed("Export folder unavailable")
    val safeName = filename.replace('/', '-').replace('\\', '-')
    val existing = tree.findFile(safeName)
    val target = existing
        ?: tree.createFile("text/markdown", safeName)
        ?: return ExportFolderResult.Failed("Could not create $safeName")
    return try {
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { it.write(markdown) }
        } ?: return ExportFolderResult.Failed("Could not open output stream")
        ExportFolderResult.Ok(safeName)
    } catch (t: Throwable) {
        ExportFolderResult.Failed(t.message ?: t.toString())
    }
}
