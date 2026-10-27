package dev.velum.app.platform

/** Path queued by Android VIEW/SEND intents; null on other platforms. */
expect fun consumeIncomingSharePath(): String?
