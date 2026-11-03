package dev.velum.app.platform

actual suspend fun awaitIncomingSharePath(): SharedFile? = IncomingShare.next()
