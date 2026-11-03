package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Paths of files shared into the app via SEND/VIEW intents, handed from MainActivity into Compose (#329). */
object IncomingShare {
    private val paths = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The next copied share; suspends until one is ready. */
    suspend fun next(): String? = paths.receive()

    /**
     * The URI of a share this app should import, or null. Only SEND and VIEW, only `content://`, and never this app's own
     * provider: the activity is exported, so any app can send any intent, and `file://` or our own FileProvider would
     * make Velum copy its private files into the import cache.
     */
    internal fun acceptedUri(action: String?, uri: Uri?, ownAuthority: String): Uri? {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null
        if (uri == null || !"content".equals(uri.scheme, ignoreCase = true)) return null
        if (ownAuthority.equals(uri.authority, ignoreCase = true)) return null
        return uri
    }

    /** Never blocks the caller: the copy runs on IO and the path is queued when complete. */
    fun offerFromIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val raw = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri else intent.data
        val uri = acceptedUri(intent.action, raw, "${context.packageName}.fileprovider") ?: return
        val app = context.applicationContext
        scope.launch { copyShare(app, uri)?.let { paths.trySend(it) } }
    }

    /** Copies [uri] into `cacheDir/imports`; a failed or empty copy leaves no file and returns null. */
    internal fun copyShare(context: Context, uri: Uri): String? {
        val displayName = displayNameOf(context, uri) // a provider query: IO thread only
        val name = sanitizeImportFileName(displayName, "${java.util.UUID.randomUUID()}.audio")
        val dest = File(context.cacheDir, "imports").apply { mkdirs() }
        val outFile = File(dest, "share_${System.currentTimeMillis()}_$name")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (ok && outFile.exists() && outFile.length() > 0L) {
            dev.velum.app.importing.ImportDisplayNames.put(outFile.absolutePath, displayName)
            return outFile.absolutePath
        }
        outFile.delete()
        return null
    }
}
