package dev.velum.app.platform

actual object SessionDownloadKeepAlive {
    actual fun start(): SessionDownloadKeepAliveStart = SessionDownloadKeepAliveStart.Started

    actual fun stop() = Unit

    actual fun setPromoteFailureHandler(handler: ((message: String) -> Unit)?) = Unit
}
