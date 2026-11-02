package dev.velum.app.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private var filesDirPath: String? = null

// The recordings dir is created once per install: after that the path is returned without touching
// the disk, so composition (e.g. the live recording screen) never does a main-thread File.exists.
@Volatile
private var recordingsDir: String? = null

fun installRecordingFilesDir(path: String) {
    filesDirPath = path
    recordingsDir = null
}

actual fun recordingsDirectoryPath(): String {
    recordingsDir?.let { return it }
    val base = filesDirPath
        ?: throw IllegalStateException("Recording files dir not installed")
    val dir = File(base, "recordings")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath.also { recordingsDir = it }
}

/**
 * Temp file + fsync + atomic rename: a failed or interrupted write never truncates or replaces an
 * existing file. The directory fsync (rename durability) is best-effort; not every platform allows it.
 */
actual fun writeBytesToPath(absolutePath: String, bytes: ByteArray) {
    val file = File(absolutePath)
    file.parentFile?.mkdirs()
    val temp = File("$absolutePath.download-tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.delete()
    }
    file.parentFile?.let { dir -> runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } } }
}

actual fun deleteFileAtPath(absolutePath: String): Boolean =
    runCatching { File(absolutePath).takeIf { it.isFile }?.delete() == true }.getOrDefault(false)

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean =
    runCatching {
        val previous = File(previousPath).canonicalFile
        previous != File(newPath).canonicalFile &&
            previous.parentFile == File(dir).canonicalFile &&
            previous.name.startsWith("$id.")
    }.getOrDefault(false)
