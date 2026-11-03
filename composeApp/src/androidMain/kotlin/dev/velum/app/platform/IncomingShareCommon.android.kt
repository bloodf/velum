package dev.velum.app.platform

actual suspend fun awaitIncomingSharePath(): String? = IncomingShare.next()
