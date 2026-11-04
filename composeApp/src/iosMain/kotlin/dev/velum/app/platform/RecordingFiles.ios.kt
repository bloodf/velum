package dev.velum.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

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

@OptIn(ExperimentalForeignApi::class)
actual fun deleteRecordingsDirectory(): Boolean {
    val files = NSFileManager.defaultManager
    val recordings = recordingsDirectoryPath()
    if (files.fileExistsAtPath(recordings)) files.removeItemAtPath(recordings, error = null)
    return !files.fileExistsAtPath(recordings)
}

/** iOS has no file-backed device transfer. */
actual fun deleteDeviceTransferTemps(): Boolean = true
