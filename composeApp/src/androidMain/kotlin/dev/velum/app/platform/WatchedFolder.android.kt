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
    val sizeBytes: Long = 0,
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

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /** Copies one source at a time; its scratch copy lives only until [consume] returns. */
    suspend fun scanNewAudio(
        context: Context,
        treeUriString: String,
        checkActive: () -> Unit,
        consume: suspend (WatchedFolderEntry) -> Unit,
    ) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return
        val sources = ArrayList<DocumentFile>()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { child ->
                checkActive()
                if (child.isDirectory) walk(child)
                else if (child.isFile && ImportAudioFormats.isSupportedFileName(child.name.orEmpty())) sources += child
            }
        }
        walk(tree)
        val destRoot = File(context.cacheDir, "imports/watched").apply { mkdirs() }
        sources.sortedByDescending { it.lastModified() }.forEach { child ->
            checkActive()
            val name = child.name.orEmpty()
            val modified = child.lastModified()
            val size = child.length()
            val extension = name.substringAfterLast('.').lowercase()
            val dest = File(destRoot, "${java.util.UUID.randomUUID()}.$extension")
            try {
                val copied = runCatching {
                    copyWholeFile(dest, size) { context.contentResolver.openInputStream(child.uri) }
                }.getOrDefault(false)
                if (copied) {
                    removeLegacyWatchedCopy(destRoot, modified, name, dest)
                    checkActive()
                    consume(WatchedFolderEntry(name, child.uri.toString(), dest.absolutePath, modified, size))
                }
            } finally {
                dest.delete()
            }
        }
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
