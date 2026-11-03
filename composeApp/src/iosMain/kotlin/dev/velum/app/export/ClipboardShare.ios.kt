package dev.velum.app.export

actual fun copyToClipboard(text: String) {
    // iOS host deferred.
}

actual suspend fun shareBytes(filename: String, mime: String, bytes: ByteArray) {
    // iOS host deferred.
}
