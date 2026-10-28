package dev.velum.app.platform

actual object SessionDownloadKeepAlive {
    actual fun start(): SessionDownloadKeepAliveStart {
        val context = appContextOrNull() ?: return SessionDownloadKeepAliveStart.Failed
        return when (SessionDownloadService.start(context)) {
            SessionDownloadServiceStartResult.Started -> SessionDownloadKeepAliveStart.Started
            SessionDownloadServiceStartResult.DeniedBluetooth ->
                SessionDownloadKeepAliveStart.DeniedBluetooth
            SessionDownloadServiceStartResult.Failed -> SessionDownloadKeepAliveStart.Failed
        }
    }

    actual fun stop() {
        val context = appContextOrNull() ?: return
        SessionDownloadService.stop(context)
    }

    actual fun setPromoteFailureHandler(handler: ((message: String) -> Unit)?) {
        SessionDownloadService.promoteFailureHandler = handler
    }
}
