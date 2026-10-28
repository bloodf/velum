package dev.velum.app.platform

import java.io.File

private var filesDirPath: String? = null

fun installRecordingFilesDir(path: String) {
    filesDirPath = path
}

actual fun recordingsDirectoryPath(): String {
    val base = filesDirPath
        ?: throw IllegalStateException("Recording files dir not installed")
    val dir = File(base, "recordings")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}

actual fun writeBytesToPath(absolutePath: String, bytes: ByteArray) {
    val file = File(absolutePath)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
}
