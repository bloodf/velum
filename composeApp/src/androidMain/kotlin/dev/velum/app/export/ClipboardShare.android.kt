package dev.velum.app.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.velum.app.platform.ShareTextHolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

actual fun copyToClipboard(text: String) {
    val context = ShareTextHolder.context ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Velum", text))
}

actual fun shareBytes(filename: String, mime: String, bytes: ByteArray) {
    val context = ShareTextHolder.context
        ?: error("ShareTextHolder.context not set")
    val safeName = filename
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "velum-export.bin" }
    val uri = runBlocking(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, safeName)
        out.writeBytes(bytes)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            out,
        )
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, safeName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, safeName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
