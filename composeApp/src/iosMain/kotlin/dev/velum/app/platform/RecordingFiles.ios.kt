package dev.velum.app.platform

actual fun recordingsDirectoryPath(): String = "/tmp/velum-recordings"

actual fun deleteFileAtPath(absolutePath: String): Boolean = false

actual fun isSamePath(a: String, b: String): Boolean = a == b

actual fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean = false
