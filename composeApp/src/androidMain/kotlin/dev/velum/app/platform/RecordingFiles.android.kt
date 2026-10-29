package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

actual fun writeBytesAtomically(
    absolutePath: String,
    bytes: ByteArray,
    beforeRename: () -> Unit,
) {
    val dest = File(absolutePath)
    val parent = dest.parentFile ?: error("destination has no parent")
    parent.mkdirs()
    val tmp = File(parent, dest.name + DOWNLOAD_PARTIAL_SUFFIX)
    try {
        tmp.writeBytes(bytes)
        beforeRename()
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } catch (t: Throwable) {
        runCatching { if (tmp.exists()) tmp.delete() }
        throw t
    }
}

actual fun deleteFileAtPath(absolutePath: String): Boolean =
    runCatching { File(absolutePath).takeIf { it.isFile }?.delete() == true }.getOrDefault(false)

private const val DOWNLOAD_PARTIAL_SUFFIX = ".download.partial"
