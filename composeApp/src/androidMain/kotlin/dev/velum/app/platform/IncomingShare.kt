package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Paths of files shared into the app via SEND/VIEW intents, handed from MainActivity into Compose (#329). */
object IncomingShare {
    private const val MAX_DISPLAY_NAME = 120

    // A path nobody received (the process dies, or the channel is dropped) would strand its copy in the import cache.
    private val paths = Channel<SharedFile>(Channel.UNLIMITED, onUndeliveredElement = { if (it is SharedFile.Ready) File(it.path).delete() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The next copied share; suspends until one is ready. */
    suspend fun next(): SharedFile = paths.receive()

    /**
     * The URI of a share this app should import, or null. Only SEND and VIEW, only `content://`, and never this app's own
     * provider: the activity is exported, so any app can send any intent, and `file://` or our own FileProvider would
     * make Velum copy its private files into the import cache.
     */
    internal fun acceptedUri(action: String?, uri: Uri?, ownAuthority: String): Uri? {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null
        if (uri == null || !"content".equals(uri.scheme, ignoreCase = true)) return null
        // Android resolves `content://<userId>@authority/...` to `authority` (and decodes %40), so compare the part after '@'.
        if (ownAuthority.equals(uri.authority?.substringAfterLast('@'), ignoreCase = true)) return null
        return uri
    }

    /**
     * Call from `Activity.onCreate`. A non-null [savedInstanceState] means the system is recreating the activity
     * (rotation, process restore) and `intent` is the original launch intent: offering it again would re-copy and
     * re-import a file the user may have since trashed (#329).
     */
    fun offerOnCreate(context: Context, intent: Intent?, savedInstanceState: android.os.Bundle?) {
        // Backing out and reopening from Recents replays the original SEND/VIEW intent with this flag: not a new share.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (savedInstanceState == null) offerFromIntent(context, intent)
    }

    /** True when [uri]'s authority resolves to a provider of this package, whatever its spelling (user-id prefix, encoding). */
    private fun isOwnProvider(context: Context, uri: Uri): Boolean = runCatching {
        val authority = uri.authority?.substringAfterLast('@') ?: return@runCatching false
        context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName
    }.getOrDefault(false)

    /** Never blocks the caller: the copy runs on IO and the path is queued when complete. */
    fun offerFromIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        // A malformed extra from another app can throw (BadParcelableException); treat it as no share.
        @Suppress("DEPRECATION")
        val raw = runCatching { if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri else intent.data }.getOrNull()
        val uri = acceptedUri(intent.action, raw, "${context.packageName}.fileprovider") ?: return
        if (isOwnProvider(context, uri)) return
        val app = context.applicationContext
        scope.launch { paths.trySend(copyShare(app, uri)?.let(SharedFile::Ready) ?: SharedFile.CopyFailed) }
    }

    /** Copies [uri] into `cacheDir/imports` (via [copyToCache]); a failed or empty copy leaves no file and returns null. */
    internal fun copyShare(context: Context, uri: Uri): String? {
        // A provider query: IO thread only. Another app controls the name, so cap it and drop control characters.
        val displayName = displayNameOf(context, uri)?.filterNot { it.isISOControl() }?.take(MAX_DISPLAY_NAME)?.ifBlank { null }
        val name = sanitizeImportFileName(displayName, "${java.util.UUID.randomUUID()}.audio")
        val dest = File(context.cacheDir, "imports").apply { mkdirs() }
        val outFile = File(dest, "share_${System.currentTimeMillis()}_$name")
        if (!copyToCache({ context.contentResolver.openInputStream(uri) }, outFile)) {
            android.util.Log.w("VelumShare", "share copy failed") // never the URI or the exception message
            return null
        }
        dev.velum.app.importing.ImportDisplayNames.put(outFile.absolutePath, displayName)
        return outFile.absolutePath
    }
}
