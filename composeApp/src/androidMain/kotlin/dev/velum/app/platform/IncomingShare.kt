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

    /** A pending share older than this (or stamped this far in the future) is dropped with its copy: the user has moved on. */
    internal const val MAX_PENDING_AGE_MS = 24L * 60 * 60 * 1000

    /** An import that started this many times without finishing is a share that kills the process: drop it and its copy. */
    internal const val MAX_ATTEMPTS = 3

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Entries are "<epochMs>|<attempts>|<absolute path>". Every write is a synchronous commit so a marker is on disk before the
    // path is offered (a kill right after the copy must still find it). Preference I/O: IO thread only, never the main thread.
    private val lock = Any()

    /** Paths marked by this process. Recovery runs once at process start and must not re-offer what this process just copied. */
    private val markedThisProcess = mutableSetOf<String>()

    private class Entry(val at: Long, val attempts: Int, val path: String) {
        fun encode() = "$at|$attempts|$path"
    }

    private fun parse(raw: String): Entry? {
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3 || parts[2].isEmpty()) return null
        return Entry(parts[0].toLongOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
    }

    private fun entries(context: Context): List<Entry> = prefs(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::parse)

    private fun save(context: Context, list: Collection<Entry>) {
        if (!prefs(context).edit().putStringSet(KEY, list.map(Entry::encode).toSet()).commit()) {
            android.util.Log.w("VelumShare", "pending share state was not saved") // recovery after a kill may miss it
        }
    }

    /** Only copies this class made: directly in `cacheDir/imports` with a `share_` name. A path read back from disk is not trusted. */
    private fun isOwnCopy(context: Context, file: File): Boolean = runCatching {
        val dir = File(context.cacheDir, "imports").canonicalFile
        val canonical = file.canonicalFile
        canonical.parentFile == dir && canonical.name.startsWith("share_")
    }.getOrDefault(false)

    private fun mark(context: Context, path: String, nowMs: Long) = synchronized(lock) {
        markedThisProcess += path
        save(context, entries(context).filterNot { it.path == path } + Entry(nowMs, 0, path))
    }

    /** Counts an import start for [path], durably. A native crash or a kill mid-import never reaches [markHandled], so this is how it is noticed. */
    internal fun markAttempt(context: Context, path: String) = synchronized(lock) {
        save(context, entries(context).map { if (it.path == path) Entry(it.at, it.attempts + 1, it.path) else it })
    }

    /** Forgets [path]; call once the share was handled (imported, duplicate, failed, refused). */
    internal fun markHandled(context: Context, path: String) = synchronized(lock) {
        markedThisProcess -= path
        save(context, entries(context).filterNot { it.path == path })
    }

    /** Forgets every pending share (a wipe: audio the user wiped must not come back). Their copies are deleted by the wipe. */
    internal fun clearPending(context: Context) = synchronized(lock) {
        markedThisProcess.clear()
        if (!prefs(context).edit().remove(KEY).commit()) android.util.Log.w("VelumShare", "pending share state was not cleared")
    }

    /** Test hook: forget what this process marked, as a new process would. */
    internal fun resetProcessMemory() = synchronized(lock) { markedThisProcess.clear() }

    /**
     * Paths of copies still marked pending (read after [recoverPending] has expired the stale ones). A startup sweep of
     * orphaned import copies must skip these: recovery owns their expiry and deletion. IO thread only.
     */
    internal fun pendingPaths(context: Context): List<String> = synchronized(lock) { entries(context).map { it.path } }

    /**
     * Once per process start (IO thread): re-offers shares copied by a process that died before importing them, relying on
     * the import's hash dedupe if one had in fact finished. Dedupe covers a finished import only: one that died half way (row created, hash keys not yet written) can still duplicate until #411 makes the row and its keys one transaction, and a queued row with no job is #415. Dropped (and an owned copy deleted) when older than
     * [MAX_PENDING_AGE_MS] in either direction, or after [MAX_ATTEMPTS] imports that never finished. Dropped without deleting
     * anything when the path is not one of our own `share_` copies. Dropped when the copy is gone. Shares this process marked
     * itself are left alone. The offer is flagged recovered with its marker time, so the UI stays quiet and a deletion made
     * after the marker wins. Returns the paths re-offered.
     */
    fun recoverPending(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val keep = mutableListOf<Entry>()
        val offered = mutableListOf<Entry>()
        var capped = 0
        for (e in entries(context)) {
            val file = File(e.path)
            val own = isOwnCopy(context, file)
            val age = nowMs - e.at
            when {
                !own -> Unit // not ours: drop the entry, touch nothing
                e.path in markedThisProcess -> keep += e
                age > MAX_PENDING_AGE_MS || age < -MAX_PENDING_AGE_MS -> file.delete()
                e.attempts >= MAX_ATTEMPTS -> { capped++; file.delete() }
                !file.isFile || file.length() == 0L -> Unit
                else -> { keep += e; offered += e }
            }
        }
        if (capped > 0) android.util.Log.w("VelumShare", "dropped $capped share(s) whose import started $MAX_ATTEMPTS times without finishing")
        save(context, keep)
        offered.forEach { enqueue(SharedFile.Ready(it.path, recoveredAtMs = it.at)) }
        offered.map { it.path }
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
        // Flush the copy to disk before the marker says it exists.
        runCatching { java.io.FileOutputStream(outFile, true).use { it.fd.sync() } }
        mark(context, outFile.absolutePath, System.currentTimeMillis())
        return outFile.absolutePath
    }
}
