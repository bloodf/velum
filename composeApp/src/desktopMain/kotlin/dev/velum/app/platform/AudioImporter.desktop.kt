package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame

@Composable
actual fun rememberAudioImporter(onPicked: (path: String) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val dialog = FileDialog(null as Frame?, "Import audio", FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                onPicked(dir.trimEnd('/', '\\') + "/" + file)
            }
        }
    }
}
