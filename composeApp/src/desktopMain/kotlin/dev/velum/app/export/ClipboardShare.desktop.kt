package dev.velum.app.export

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (_: Throwable) {
        // Headless / no clipboard — ignore.
    }
}

actual suspend fun shareBytes(filename: String, mime: String, bytes: ByteArray) {
    val safe = filename
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "velum-export.bin" }
    // The home folder, not a folder the user chose. Nothing tracks these files, so a wipe cannot remove them.
    val out = File(System.getProperty("user.home"), safe)
    withContext(Dispatchers.IO) { out.writeBytes(bytes) }
}
