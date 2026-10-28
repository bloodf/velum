package dev.velum.app.export

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

actual fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (_: Throwable) {
        // Headless / no clipboard — ignore.
    }
}

actual fun shareBytes(filename: String, mime: String, bytes: ByteArray) {
    val safe = filename
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "velum-export.bin" }
    val out = File(System.getProperty("user.home"), safe)
    out.writeBytes(bytes)
}
