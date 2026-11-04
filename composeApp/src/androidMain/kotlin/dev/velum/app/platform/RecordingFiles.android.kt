package dev.velum.app.platform

import java.io.File

private var filesDirPath: String? = null

fun installRecordingFilesDir(path: String?) {
    filesDirPath = path
}

actual fun recordingsDirectoryPath(): String {
    val base = filesDirPath
        ?: throw IllegalStateException("Recording files dir not installed")
    // Disk-free on purpose: this is called from composition. A wipe/purge may delete the directory, so
    // every write site creates it (streamToFileAtomically, WavWriter, MicRecordingService do mkdirs).
    return File(base, "recordings").absolutePath
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

actual fun isAppOwnedAudioPath(absolutePath: String): Boolean = runCatching {
    val file = File(absolutePath).canonicalFile
    val roots = listOfNotNull(
        filesDirPath?.let { File(it, "recordings") },
        appContextOrNull()?.let { File(it.cacheDir, "imports") },
    )
    roots.any { file.startsWith(it.canonicalFile) && file != it.canonicalFile }
}.getOrDefault(false)

actual fun isImportCacheCopy(absolutePath: String): Boolean = runCatching {
    val imports = File(appContextOrNull()?.cacheDir ?: return false, "imports").canonicalFile
    val file = File(absolutePath).canonicalFile
    // Picked and shared copies sit directly in imports/, watched ones in imports/watched/. Normalized audio does not.
    !file.isDirectory && (file.parentFile == imports || file.parentFile == File(imports, "watched"))
}.getOrDefault(false)
