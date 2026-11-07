package dev.velum.app.export

actual fun copyToClipboard(text: String): Boolean {
    // iOS host deferred.
    return false
}

actual suspend fun shareBytes(filename: String, mime: String, bytes: ByteArray) {
    // iOS host deferred.
}
