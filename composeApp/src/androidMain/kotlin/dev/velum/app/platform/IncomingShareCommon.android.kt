package dev.velum.app.platform

actual fun consumeIncomingSharePath(): String? = IncomingShare.takePendingPath()
