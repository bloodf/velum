package dev.velum.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual fun recordingsDirectoryPath(): String = "/tmp/velum-recordings"

actual fun deleteFileAtPath(absolutePath: String): Boolean = false

actual fun isSamePath(a: String, b: String): Boolean = a == b

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean = false

@OptIn(ExperimentalForeignApi::class)
actual fun deleteFileIfPresent(absolutePath: String): Boolean {
    val files = NSFileManager.defaultManager
    if (files.fileExistsAtPath(absolutePath)) files.removeItemAtPath(absolutePath, error = null)
    return !files.fileExistsAtPath(absolutePath)
}

actual fun isAppOwnedAudioPath(absolutePath: String): Boolean {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return false
    // No canonicalizing here: a path with ".." is not one the app wrote, so it isn't treated as owned.
    return absolutePath.startsWith("$caches/imports/") && ".." !in absolutePath
}

actual fun isImportCacheCopy(absolutePath: String): Boolean {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return false
    val imports = "$caches/imports/"
    if (!absolutePath.startsWith(imports) || ".." in absolutePath) return false
    // Direct children of imports/ and imports/watched/ only, never the directories or normalized audio.
    val name = absolutePath.removePrefix(imports).removePrefix("watched/")
    return name.isNotEmpty() && "/" !in name && name != "watched"
}
