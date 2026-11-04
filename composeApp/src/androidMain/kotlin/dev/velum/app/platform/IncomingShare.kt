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

    // receive() cancelled right after an element was taken (the screen stops) must not lose or delete that share: requeue it.
    private val paths: Channel<SharedFile> = Channel(Channel.UNLIMITED, onUndeliveredElement = { shared -> paths.trySend(shared) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PREFS = "velum_pending_shares"
    private const val KEY = "pending"

    /** A pending share older than this is dropped with its copy: the user has moved on. */
    internal const val MAX_PENDING_AGE_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Entries are "<epochMs>|<absolute path>". Synchronous commit so the marker is on disk before the path is offered: a kill right
    // after the copy must still find it. IO thread only.
    private val lock = Any()

    private fun mark(context: Context, path: String, nowMs: Long) = synchronized(lock) {
        val p = prefs(context)
        p.edit().putStringSet(KEY, (p.getStringSet(KEY, emptySet()).orEmpty()) + "$nowMs|$path").commit()
    }

    /** Forgets [path]; call once the share was handled (imported, duplicate, failed, refused). */
    internal fun markHandled(context: Context, path: String) = synchronized(lock) {
        val p = prefs(context)
        val now = p.getStringSet(KEY, emptySet()).orEmpty()
        p.edit().putStringSet(KEY, now.filterNot { it.substringAfter('|') == path }.toSet()).commit()
    }

    /**
     * Paths of copies still marked pending (read after [recoverPending] has expired the stale ones). A startup sweep of
     * orphaned import copies must skip these: recovery owns their expiry and deletion. IO thread only.
     */
    internal fun pendingPaths(context: Context): List<String> = synchronized(lock) {
        prefs(context).getStringSet(KEY, emptySet()).orEmpty().map { it.substringAfter('|', "") }.filter { it.isNotEmpty() }
    }

    /**
     * Once per process start (IO thread): re-offers shares copied by a process that died before importing them, relying on
     * the import's hash dedupe if one had in fact finished. Entries whose copy is gone (a wipe, a cache clear) or older than
     * [MAX_PENDING_AGE_MS] are dropped, and an expired copy is deleted. Returns the paths re-offered.
     */
    fun recoverPending(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val p = prefs(context)
        val entries = p.getStringSet(KEY, emptySet()).orEmpty()
        val keep = mutableSetOf<String>()
        val offered = mutableListOf<String>()
        for (entry in entries) {
            val at = entry.substringBefore('|').toLongOrNull()
            val path = entry.substringAfter('|', "")
            val file = File(path)
            when {
                at == null || path.isEmpty() -> Unit // corrupt: drop
                nowMs - at > MAX_PENDING_AGE_MS -> file.delete()
                !file.isFile || file.length() == 0L -> Unit
                else -> { keep += entry; offered += path }
            }
        }
        p.edit().putStringSet(KEY, keep).commit()
        offered.forEach { enqueue(SharedFile.Ready(it)) }
        offered
    }

    /** Queues a finished share. Used by the copy and by tests. */
    internal fun enqueue(shared: SharedFile) {
        paths.trySend(shared)
    }

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
    }.getOrDefault(true) // fail closed: if the provider lookup throws, do not import

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

    /** A provider-supplied name made safe to store and show: no control or invisible formatting characters, at most 120 chars. */
    internal fun cleanName(raw: String?): String? =
        raw?.let(dev.velum.app.importing.ImportTitles::cleanDisplayName)?.take(MAX_DISPLAY_NAME)?.ifBlank { null }

    /** Copies [uri] into `cacheDir/imports` (via [copyToCache]); a failed or empty copy leaves no file and returns null. */
    internal fun copyShare(context: Context, uri: Uri): String? {
        // A provider query: IO thread only. Another app controls the name, so cap it and drop control characters.
        val displayName = cleanName(displayNameOf(context, uri))
        val name = sanitizeImportFileName(displayName, "${java.util.UUID.randomUUID()}.audio")
        val dest = File(context.cacheDir, "imports").apply { mkdirs() }
        val outFile = File(dest, "share_${System.currentTimeMillis()}_$name")
        if (!copyToCache({ context.contentResolver.openInputStream(uri) }, outFile)) {
            android.util.Log.w("VelumShare", "share copy failed") // never the URI or the exception message
            return null
        }
        dev.velum.app.importing.ImportDisplayNames.put(outFile.absolutePath, displayName)
        mark(context, outFile.absolutePath, System.currentTimeMillis())
        return outFile.absolutePath
    }
}
