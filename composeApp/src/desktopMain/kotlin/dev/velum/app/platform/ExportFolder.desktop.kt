package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

actual object ExportFolderStore {
    @Volatile
    private var uri: String? = null

    actual fun currentUri(): String? = uri

    actual fun clear() {
        uri = null
    }

    fun set(path: String) {
        uri = path
    }
}

@Composable
actual fun rememberExportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit {
    return remember {
        {
            val home = File(System.getProperty("user.home") ?: ".", "VelumExport")
            home.mkdirs()
            ExportFolderStore.set(home.absolutePath)
            onPicked(home.absolutePath)
        }
    }
}

actual fun writeMarkdownToExportFolder(filename: String, markdown: String): ExportFolderResult {
    val dirPath = ExportFolderStore.currentUri()
        ?: return ExportFolderResult.Failed("No export folder selected")
    val dir = File(dirPath)
    if (!dir.isDirectory && !dir.mkdirs()) {
        return ExportFolderResult.Failed("Cannot create $dirPath")
    }
    val file = File(dir, filename.replace('/', '-'))
    return try {
        file.writeText(markdown)
        ExportFolderResult.Ok(file.absolutePath)
    } catch (t: Throwable) {
        ExportFolderResult.Failed(t.message ?: t.toString())
    }
}
