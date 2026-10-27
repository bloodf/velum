package dev.velum.app.platform

import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** One-shot path handed from MainActivity share/VIEW intents into Compose. */
object IncomingShare {
    private val pendingPath = AtomicReference<String?>(null)

    fun takePendingPath(): String? = pendingPath.getAndSet(null)

    fun offerFromIntent(context: android.content.Context, intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            Intent.ACTION_VIEW -> intent.data
            else -> intent.data
        } ?: return
        val name = sanitizeImportFileName(uri.lastPathSegment)
        if (!ImportAudioFormats.isSupportedFileName(name) &&
            intent.type?.startsWith("audio/") != true &&
            intent.type != "application/ogg"
        ) {
            // Still copy — user explicitly opened with Velum.
        }
        val dest = File(context.cacheDir, "imports").apply { mkdirs() }
        val outFile = File(dest, "share_${System.currentTimeMillis()}_$name")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }
        if (outFile.exists() && outFile.length() > 0L) {
            pendingPath.set(outFile.absolutePath)
        }
    }
}
