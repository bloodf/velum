package dev.velum.app.platform

actual fun recordingsDirectoryPath(): String = "/tmp/velum-recordings"

actual fun deleteFileAtPath(absolutePath: String): Boolean = false

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean = false
