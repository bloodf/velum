package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberJsonFileImporter(onPicked: (jsonText: String) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val dialog = FileDialog(null as Frame?, "Import Plaud JSON", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                name.endsWith(".json", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)
            }
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val text = File(dir.trimEnd('/', '\\'), file).readText()
                if (text.isNotBlank()) onPicked(text)
            }
        }
    }
}
