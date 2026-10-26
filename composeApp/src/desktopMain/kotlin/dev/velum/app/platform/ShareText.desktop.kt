package dev.velum.app.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

actual fun shareText(title: String, text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (_: Throwable) {
        val safe = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "velum-export" }
        val out = File(System.getProperty("user.home"), "$safe.md")
        out.writeText(text)
    }
}
