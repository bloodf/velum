package dev.velum.app.platform

actual suspend fun awaitIncomingSharePath(): SharedFile? = null

actual fun markShareHandled(path: String) = Unit
