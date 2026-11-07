package dev.velum.app.platform

actual fun markShareHandled(path: String) {
    appContextOrNull()?.let { IncomingShare.markHandled(it, path) }
}

actual fun markShareAttempt(path: String) {
    appContextOrNull()?.let { IncomingShare.markAttempt(it, path) }
}

actual fun clearPendingShares() {
    appContextOrNull()?.let { IncomingShare.clearPending(it) }
}

actual suspend fun awaitIncomingSharePath(): SharedFile? = IncomingShare.next()
