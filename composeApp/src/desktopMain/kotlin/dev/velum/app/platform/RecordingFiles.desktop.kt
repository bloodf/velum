package dev.velum.app.platform

import java.io.File

actual fun recordingsDirectoryPath(): String {
    val dir = File(System.getProperty("user.home"), ".velum/recordings")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}
