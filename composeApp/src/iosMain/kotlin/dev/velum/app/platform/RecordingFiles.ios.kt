@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

private val files get() = NSFileManager.defaultManager
private const val legacyRecordings = "/tmp/velum-recordings"

actual fun recordingsDirectoryPath(): String {
    val base = searchDirectory(NSApplicationSupportDirectory)
        ?: error("Application Support directory unavailable")
    val path = "$base/velum/recordings"
    check(!rootHasLink(path)) { "Recordings directory contains a symbolic link" }
    check(files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)) {
        "Cannot create recordings directory"
    }
    return path
}

private fun searchDirectory(directory: ULong): String? =
    NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String

private fun canonical(path: String): String? {
    if (!path.startsWith("/") || '\u0000' in path) return null
    return NSURL.fileURLWithPath(path).URLByStandardizingPath?.URLByResolvingSymlinksInPath?.path
}

private fun ownedRoots(): List<String> = listOfNotNull(
    searchDirectory(NSApplicationSupportDirectory)?.let { "$it/velum/recordings" },
    searchDirectory(NSCachesDirectory)?.let { "$it/imports" },
    legacyRecordings,
)

// Resolve system aliases such as /tmp, but reject links and traversal below an owned root.
private fun rootHasLink(root: String): Boolean =
    listOf(root, root.takeIf { it.endsWith("/velum/recordings") }?.substringBeforeLast('/'))
        .filterNotNull().any { files.attributesOfItemAtPath(it, error = null)?.get("NSFileType") == "NSFileTypeSymbolicLink" }

private fun ownedRoot(path: String): String? {
    if (!path.startsWith("/") || '\u0000' in path || path.split('/').any { it == ".." }) return null
    val resolved = canonical(path) ?: return null
    return ownedRoots().firstOrNull { root ->
        val resolvedRoot = canonical(root) ?: return@firstOrNull false
        val spelling = listOf(root, resolvedRoot).firstOrNull { path.startsWith("$it/") }
            ?: return@firstOrNull false
        if (rootHasLink(root) || !resolved.startsWith("$resolvedRoot/")) return@firstOrNull false
        val parts = path.removePrefix("$spelling/").split('/').filter { it.isNotEmpty() && it != "." }
        var current = spelling
        if (files.attributesOfItemAtPath(current, error = null)?.get("NSFileType") == "NSFileTypeSymbolicLink") {
            return@firstOrNull false
        }
        parts.all { part ->
            current += "/$part"
            files.attributesOfItemAtPath(current, error = null)?.get("NSFileType") != "NSFileTypeSymbolicLink"
        }
    }
}

actual fun deleteFileAtPath(absolutePath: String): Boolean {
    if (ownedRoot(absolutePath) == null) return false
    if (files.attributesOfItemAtPath(absolutePath, error = null)?.get("NSFileType") != "NSFileTypeRegular") return false
    return files.removeItemAtPath(absolutePath, error = null)
}

actual fun isSamePath(a: String, b: String): Boolean =
    canonical(a)?.let { it == canonical(b) } ?: false

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean {
    if (id.isEmpty() || '/' in id || ownedRoot(previousPath) == null) return false
    val previous = canonical(previousPath) ?: return false
    val replacement = canonical(newPath) ?: return false
    return previous != replacement && previous.substringBeforeLast('/') == canonical(dir) &&
        previous.substringAfterLast('/').startsWith("$id.")
}

actual fun deleteFileIfPresent(absolutePath: String): Boolean {
    if (ownedRoot(absolutePath) == null) return false
    return if (files.fileExistsAtPath(absolutePath)) deleteFileAtPath(absolutePath) else true
}

private fun deleteRecordingTree(path: String): Boolean {
    val type = files.attributesOfItemAtPath(path, error = null)?.get("NSFileType") ?: return true
    if (type == "NSFileTypeSymbolicLink") return false
    if (type == "NSFileTypeDirectory") {
        val children = files.contentsOfDirectoryAtPath(path, error = null) ?: return false
        if (!children.map { deleteRecordingTree("$path/$it") }.all { it }) return false
    }
    return files.removeItemAtPath(path, error = null)
}

actual fun deleteRecordingsDirectory(): Boolean = ownedRoots()
    .filter { !it.endsWith("/imports") }
    .map { !rootHasLink(it) && deleteRecordingTree(it) }
    .all { it }

/** iOS has no file-backed device transfer. */
actual fun deleteDeviceTransferTemps(): Boolean = true

actual fun isAppOwnedAudioPath(absolutePath: String): Boolean = ownedRoot(absolutePath) != null

actual fun isImportCacheCopy(absolutePath: String): Boolean {
    val root = ownedRoot(absolutePath) ?: return false
    if (!root.endsWith("/imports")) return false
    val path = canonical(absolutePath) ?: return false
    val imports = canonical(root) ?: return false
    val parent = path.substringBeforeLast('/')
    return files.attributesOfItemAtPath(path, error = null)?.get("NSFileType") != "NSFileTypeDirectory" &&
        (parent == imports || parent == "$imports/watched")
}
