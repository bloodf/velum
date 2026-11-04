package dev.velum.app.platform

import java.io.File

actual fun recordingsDirectoryPath(): String {
    val dir = File(System.getProperty("user.home"), ".velum/recordings")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}

actual fun deleteFileAtPath(absolutePath: String): Boolean =
    runCatching { File(absolutePath).takeIf { it.isFile }?.delete() == true }.getOrDefault(false)

actual fun isSamePath(a: String, b: String): Boolean =
    runCatching { File(a).canonicalFile == File(b).canonicalFile }.getOrDefault(false)

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean =
    runCatching {
        val previous = File(previousPath).canonicalFile
        previous != File(newPath).canonicalFile &&
            previous.parentFile == File(dir).canonicalFile &&
            previous.name.startsWith("$id.")
    }.getOrDefault(false)

actual fun deleteFileIfPresent(absolutePath: String): Boolean = runCatching {
    val file = File(absolutePath)
    if (file.isFile) file.delete()
    !file.exists()
}.getOrDefault(false)

actual fun deleteRecordingsDirectory(): Boolean {
    val recordings = File(recordingsDirectoryPath())
    return deleteTreeWithoutFollowingLinks(recordings)
}

actual fun deleteDeviceTransferTemps(): Boolean {
    val temp = File(System.getProperty("java.io.tmpdir") ?: return false)
    return temp.listFiles().orEmpty()
        .filter { it.name.startsWith("velum-ble-session-") }
        .map { it.delete() || !it.exists() }
        .all { it }
}
