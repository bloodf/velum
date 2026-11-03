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

    private const val KEY_IMPORTED = "imported_files"

    /** Identity of one source file version: a changed size or time is a new file. */
    fun fileKey(uriString: String, lastModifiedMs: Long, length: Long) = "$uriString|$lastModifiedMs|$length"

    private fun importedKeys(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IMPORTED, emptySet()).orEmpty()

    fun markImported(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_IMPORTED, importedKeys(context) + key).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URIS, "")
            .remove(KEY_IMPORTED)
            .apply()
    }

    fun scanNewAudio(context: Context, treeUriString: String): List<WatchedFolderEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        val out = ArrayList<WatchedFolderEntry>()
        val destRoot = File(context.cacheDir, "imports/watched").apply { mkdirs() }
        val imported = importedKeys(context)
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name.orEmpty()
                    if (ImportAudioFormats.isSupportedFileName(name)) {
                        val key = fileKey(child.uri.toString(), child.lastModified(), child.length())
                        // Already imported: the cache copy is deleted after an import, so do not copy it again.
                        if (key in imported) return@forEach
                        val dest = File(destRoot, "${child.lastModified()}_$name")
                        // One unreadable file (card pulled mid-copy, revoked grant) must not stop the others.
                        val copied = runCatching {
                            copyWholeFile(dest, child.length()) { context.contentResolver.openInputStream(child.uri) }
                        }.getOrDefault(false)
                        if (copied) {
                            out += WatchedFolderEntry(
                                displayName = name,
                                uriString = child.uri.toString(),
                                absoluteCopyPath = dest.absolutePath,
                                lastModifiedMs = child.lastModified(),
                                fileKey = key,
                            )
                        }
                    }
                }
            }
        }
        walk(tree)
        return out.sortedByDescending { it.lastModifiedMs }
    }
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
