package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

data class WatchedFolderEntry(
    val displayName: String,
    val uriString: String,
    val absoluteCopyPath: String,
    val lastModifiedMs: Long,
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

    fun scanNewAudio(context: Context, treeUriString: String): List<WatchedFolderEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        val out = ArrayList<WatchedFolderEntry>()
        val destRoot = File(context.cacheDir, "imports/watched").apply { mkdirs() }
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name.orEmpty()
                    if (ImportAudioFormats.isSupportedFileName(name)) {
                        val dest = File(destRoot, "${child.lastModified()}_$name")
                        if (!dest.exists()) {
                            context.contentResolver.openInputStream(child.uri)?.use { input ->
                                FileOutputStream(dest).use { output -> input.copyTo(output) }
                            }
                        }
                        if (dest.exists() && dest.length() > 0L) {
                            out += WatchedFolderEntry(
                                displayName = name,
                                uriString = child.uri.toString(),
                                absoluteCopyPath = dest.absolutePath,
                                lastModifiedMs = child.lastModified(),
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

@Composable
fun rememberWatchedFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        WatchedFolderStore.remember(context, uri)
        onPicked(uri.toString())
    }
    return remember(launcher) { { launcher.launch(null) } }
}
