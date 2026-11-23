@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/** Stable Swift export. Only OS-owned copies in Documents/Inbox are accepted. */
object IosIncomingShare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun receive(url: String) {
        val generation = inbox.generation()
        scope.launch { inbox.receive(url, generation) }
    }
}

private fun directory(kind: ULong) =
    NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String

private val inbox by lazy {
    IosInboxIntake("${directory(NSDocumentDirectory)}/Inbox", "${directory(NSCachesDirectory)}/imports")
}

actual suspend fun awaitIncomingSharePath(): SharedFile? = inbox.next()
actual fun markShareHandled(path: String) = inbox.handled(path)
actual fun markShareAttempt(path: String) = inbox.attempt(path)
actual fun clearPendingShares() = inbox.clear()

@Serializable
internal data class InboxRecord(val path: String, val source: String, val time: Long, val attempts: Int = 0)

@Serializable
private data class InboxState(val pending: List<InboxRecord> = emptyList(), val seen: List<String> = emptyList())

/** All mutations, including publication after a copy, share a lock with wipe. */
internal class IosInboxIntake(
    sourceRoot: String,
    cacheRoot: String,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val key: String = "velum.inbox.v1",
    private val copyFile: (String, String) -> Unit = { source, destination -> copyInboxFile(source, destination) },
) {
    private val originalSourceRoot = sourceRoot
    private val originalCacheRoot = cacheRoot
    private val sourceRoot = canonical(sourceRoot) ?: sourceRoot
    private val cacheRoot = canonical(cacheRoot) ?: cacheRoot
    private val lock = NSRecursiveLock()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var epoch = 0L
    private var state = defaults.stringForKey(key)?.let {
        runCatching { Json.decodeFromString<InboxState>(it) }.getOrNull()
    } ?: InboxState()
    private val delivered = mutableSetOf<String>()
    private val recovered = state.pending.map { it.path }.toSet()
    private val copying = mutableMapOf<String, String>()

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private fun save(next: InboxState) {
        defaults.setObject(Json.encodeToString(next), forKey = key)
        check(defaults.synchronize()) { "Cannot persist incoming share" }
        state = next
    }

    fun generation(): Long = locked { epoch }

    internal fun receive(raw: String, generation: Long = generation()): Boolean {
        val reservation = locked {
            if (generation != epoch || state.pending.size + copying.size >= 8 || !rootIsUnlinked(originalSourceRoot) || !rootIsUnlinked(originalCacheRoot)) return@locked null
            val url = NSURL.URLWithString(raw) ?: return@locked null
            if (!url.isFileURL() || !url.host.isNullOrEmpty() || url.query != null || url.fragment != null) return@locked null
            val rawPath = url.path ?: return@locked null
            if (rawPath.split('/').any { it == ".." || it == "." }) return@locked null
            val path = rawPath.replaceFirst(directory(NSDocumentDirectory), canonical(directory(NSDocumentDirectory)) ?: directory(NSDocumentDirectory))
            if (!directRegularFile(path, sourceRoot)) return@locked null
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return@locked null
            val identity = "$path:${attributes[NSFileSystemFileNumber]}:${attributes[NSFileModificationDate]}:${attributes[NSFileSize]}"
            if (identity in copying || identity in state.seen || state.pending.any { it.source == identity }) return@locked null
            val files = NSFileManager.defaultManager
            if (!files.createDirectoryAtPath(cacheRoot, true, null, null)) return@locked null
            if (!safeRoot(cacheRoot)) return@locked null
            val dest = "$cacheRoot/inbox-${NSUUID().UUIDString}_${sanitizeImportFileName(url.lastPathComponent)}"
            copying[identity] = dest
            Triple(path, identity, dest)
        } ?: return false
        val (path, identity, dest) = reservation
        var copied = false
        var published = false
        try {
            copyFile(path, dest)
            copied = true
            return locked {
                if (generation != epoch || copying[identity] != dest || state.pending.size >= 8 ||
                    !rootIsUnlinked(originalSourceRoot) || !rootIsUnlinked(originalCacheRoot) ||
                    !directRegularFile(dest, cacheRoot)) return@locked false
                val record = InboxRecord(dest, identity, (NSDate().timeIntervalSince1970 * 1000).toLong())
                save(state.copy(pending = state.pending + record, seen = (state.seen + identity).takeLast(64)))
                published = true
                wake.trySend(Unit)
                true
            }
        } catch (_: Exception) {
            return false
        } finally {
            locked {
                if (copying[identity] == dest) copying.remove(identity)
                if (copied && !published && directRegularFile(dest, cacheRoot)) {
                    NSFileManager.defaultManager.removeItemAtPath(dest, null)
                }
            }
        }
    }

    suspend fun next(): SharedFile {
        while (true) {
            val result = locked {
                val candidate = state.pending.firstOrNull { it.path !in delivered }
                if (candidate == null) null else if (candidate.attempts >= 3 || !directRegularFile(candidate.path, cacheRoot)) {
                    handled(candidate.path)
                    SharedFile.CopyFailed
                } else {
                    delivered += candidate.path
                    SharedFile.Ready(candidate.path, candidate.time.takeIf { candidate.path in recovered })
                }
            }
            if (result != null) return result
            wake.receive()
        }
    }

    fun attempt(path: String) = locked {
        val record = state.pending.firstOrNull { it.path == path }
        check(record != null && directRegularFile(path, cacheRoot)) { "Incoming share was cleared" }
        save(state.copy(pending = state.pending.map { if (it.path == path) it.copy(attempts = it.attempts + 1) else it }))
    }

    fun handled(path: String) = locked {
        if (state.pending.none { it.path == path }) return@locked
        save(state.copy(pending = state.pending.filterNot { it.path == path }))
        delivered.remove(path)
        if (directRegularFile(path, cacheRoot)) NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    fun clear() = locked {
        epoch++
        val pending = state.pending
        save(state.copy(pending = emptyList()))
        delivered.clear()
        pending.forEach { if (directRegularFile(it.path, cacheRoot)) NSFileManager.defaultManager.removeItemAtPath(it.path, null) }
    }
}

private fun canonical(path: String): String? =
    NSURL.fileURLWithPath(path).URLByStandardizingPath?.URLByResolvingSymlinksInPath?.path

private fun rootIsUnlinked(root: String): Boolean =
    NSFileManager.defaultManager.attributesOfItemAtPath(root, null)?.get(NSFileType) != NSFileTypeSymbolicLink

private fun safeRoot(root: String): Boolean =
    NSFileManager.defaultManager.attributesOfItemAtPath(root, null)?.get(NSFileType) == NSFileTypeDirectory &&
        canonical(root) == NSURL.fileURLWithPath(root).URLByStandardizingPath?.path

private fun directRegularFile(path: String, root: String): Boolean =
    path.split('/').none { it == ".." || it == "." } && safeRoot(root) &&
        path.substringBeforeLast('/') == root && canonical(path) == path &&
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeRegular
