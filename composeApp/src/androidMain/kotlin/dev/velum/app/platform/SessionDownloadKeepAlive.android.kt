package dev.velum.app.platform

actual object SessionDownloadKeepAlive {
    actual fun start() {
        val context = appContextOrNull() ?: return
        SessionDownloadService.start(context)
    }

    actual fun stop() {
        val context = appContextOrNull() ?: return
        SessionDownloadService.stop(context)
    }
}
