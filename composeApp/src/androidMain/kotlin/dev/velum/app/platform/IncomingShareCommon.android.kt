package dev.velum.app.platform

actual fun markShareHandled(path: String) {
    appContextOrNull()?.let { IncomingShare.markHandled(it, path) }
}

actual suspend fun awaitIncomingSharePath(): SharedFile? = IncomingShare.next()
