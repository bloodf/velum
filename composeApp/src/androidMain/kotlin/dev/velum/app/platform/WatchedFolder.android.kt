package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatchedFolderEntry(
    val displayName: String,
    val uriString: String,
    val absoluteCopyPath: String,
    val lastModifiedMs: Long,
    val fileKey: String = "",
)

/** Persistable SAF tree for USB OTG / SD / sync folders. */
object WatchedFolderStore {
    private const val PREFS = "velum_watched_folders"
    private const val KEY_URIS = "tree_uris"

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URIS, "")
            .orEmpty()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun remember(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (list(context) + treeUri.toString()).distinct()
        prefs.edit().putString(KEY_URIS, next.joinToString("\n")).apply()
    }

    // Own prefs file, excluded from backup and device transfer: the database is not transferred, so carried-over
    // "already imported" keys would block files that have no recording here.
    private const val IMPORTED_PREFS = "velum_watched_imported"
    private const val KEY_IMPORTED = "imported_files"

    /** Identity of one source file version: a changed size or time is a new file. */
    fun fileKey(uriString: String, lastModifiedMs: Long, length: Long) = "$uriString|$lastModifiedMs|$length"

    internal fun importedKeys(context: Context): Set<String> =
        context.getSharedPreferences(IMPORTED_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IMPORTED, emptySet()).orEmpty()

    fun markImported(context: Context, key: String) {
        val prefs = context.getSharedPreferences(IMPORTED_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_IMPORTED, importedKeys(context) + key).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URIS, "")
            .apply()
        context.getSharedPreferences(IMPORTED_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun scanNewAudio(context: Context, treeUriString: String): List<WatchedFolderEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        val sources = ArrayList<WatchedSource>()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name.orEmpty()
                    if (ImportAudioFormats.isSupportedFileName(name)) {
                        sources += WatchedSource(child.uri.toString(), name, child.lastModified(), child.length()) {
                            context.contentResolver.openInputStream(child.uri)
                        }
                    }
                }
            }
        }
        walk(tree)
        val destRoot = File(context.cacheDir, "imports/watched").apply { mkdirs() }
        return copyUnimported(sources, importedKeys(context), destRoot)
    }
}

/** One file found in a watched tree, before anything is copied. */
internal class WatchedSource(
    val uri: String,
    val name: String,
    val lastModifiedMs: Long,
    val length: Long,
    val open: () -> java.io.InputStream?,
)

/**
 * Copies every source whose [WatchedFolderStore.fileKey] is not in [imported] into [destRoot], newest first. A file
 * that was already imported is not read at all: its cache copy was deleted after the import, so copying it again
 * would re-download the whole folder on every sync.
 */
internal fun copyUnimported(sources: List<WatchedSource>, imported: Set<String>, destRoot: File): List<WatchedFolderEntry> {
    val out = ArrayList<WatchedFolderEntry>()
    for (src in sources) {
        val key = WatchedFolderStore.fileKey(src.uri, src.lastModifiedMs, src.length)
        if (key in imported) continue
        val dest = File(destRoot, watchedCopyName(src.uri, src.lastModifiedMs, src.name))
        // One unreadable file (card pulled mid-copy, revoked grant) must not stop the others.
        val copied = runCatching { copyWholeFile(dest, src.length, src.open) }.getOrDefault(false)
        if (!copied) continue
        removeLegacyWatchedCopy(destRoot, src.lastModifiedMs, src.name, dest)
        out += WatchedFolderEntry(
            displayName = src.name,
            uriString = src.uri,
            absoluteCopyPath = dest.absolutePath,
            lastModifiedMs = src.lastModifiedMs,
            fileKey = key,
        )
    }
    return out.sortedByDescending { it.lastModifiedMs }
}

/**
 * Makes [dest] a complete copy of the source, or leaves nothing at [dest]. Copies to a temp file, checks the byte
 * count against [expectedLength] (when known) and renames only then, so an interrupted copy can never be taken
 * for the real file. A [dest] that is already complete is kept; a wrong-size one (from an older version, which
 * copied in place) is replaced. Returns true when [dest] is complete and non-empty.
 */
internal fun copyWholeFile(dest: File, expectedLength: Long, open: () -> java.io.InputStream?): Boolean {
    val known = expectedLength > 0L
    if (dest.exists() && dest.length() > 0L && (!known || dest.length() == expectedLength)) return true
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    tmp.delete()
    try {
        val input = open() ?: return false
        input.use { src -> FileOutputStream(tmp).use { out -> src.copyTo(out) } }
        if (tmp.length() == 0L || (known && tmp.length() != expectedLength)) return false
        dest.delete()
        return tmp.renameTo(dest)
    } finally {
        tmp.delete()
    }
}

@Composable
fun rememberWatchedFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { WatchedFolderStore.remember(context, uri) }
            onPicked(uri.toString())
        }
    }
    return remember(launcher) { { launcher.launch(null) } }
}

/**
 * Name of the private copy of a watched-folder file. The document URI is hashed in, so two folders (or sub-folders)
 * holding a same-named file with the same modification time no longer share, and overwrite, one copy (#319).
 */
internal fun watchedCopyName(documentUri: String, lastModified: Long, name: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(documentUri.encodeToByteArray())
    val short = digest.take(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    // Sanitised like every other imported name: a long or odd document name must not exceed the 255-byte limit.
    return "${lastModified}_${short}_${sanitizeImportFileName(name)}"
}

/**
 * Before the document-URI hash, a copy was named `<lastModified>_<name>`. Once the new copy exists the old one is
 * dead weight (and would otherwise double the folder), so remove it. Never removes [current] itself.
 */
internal fun removeLegacyWatchedCopy(destRoot: File, lastModified: Long, name: String, current: File): Boolean {
    val legacy = File(destRoot, "${lastModified}_$name")
    return legacy.isFile && legacy.canonicalFile != current.canonicalFile && legacy.delete()
}
