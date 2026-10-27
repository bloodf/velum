package dev.velum.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Composable
actual fun rememberAudioImporter(onPicked: (path: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = sanitizeImportFileName(uri.lastPathSegment)
        val dest = File(context.cacheDir, "imports").apply { mkdirs() }
        val outFile = File(dest, "${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        if (outFile.exists() && outFile.length() > 0L) {
            onPicked(outFile.absolutePath)
        }
    }
    return remember(launcher) {
        {
            launcher.launch(ImportAudioFormats.mimeTypes)
        }
    }
}

internal fun sanitizeImportFileName(raw: String?): String {
    val base = raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    val cleaned = base
        .replace("..", "")
        .replace(Regex("[\\\\/]"), "")
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        .take(120)
    return cleaned.ifBlank { "${UUID.randomUUID()}.audio" }
}
