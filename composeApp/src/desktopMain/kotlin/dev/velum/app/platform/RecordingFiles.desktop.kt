package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun recordingsDirectoryPath(): String {
    val dir = File(System.getProperty("user.home"), ".velum/recordings")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}

/** Temp file + atomic rename: a failed write never truncates or replaces an existing file. */
actual fun writeBytesToPath(absolutePath: String, bytes: ByteArray) {
    val file = File(absolutePath)
    file.parentFile?.mkdirs()
    val temp = File("$absolutePath.download-tmp")
    try {
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.delete()
    }
}

actual fun deleteFileAtPath(absolutePath: String): Boolean =
    runCatching { File(absolutePath).takeIf { it.isFile }?.delete() == true }.getOrDefault(false)
