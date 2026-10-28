package dev.velum.app.platform

/**
 * Optional system notification for long downloads (device session / on-device model).
 * Android posts a cancelable progress notification; other targets are no-ops.
 */
enum class DownloadNotifyKind {
    DeviceSession,
    LocalModel,
}

expect object DownloadProgressNotifier {
    fun ensureChannel()

    fun show(
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    )

    fun update(kind: DownloadNotifyKind, progress: Float, body: String? = null)

    fun dismiss(kind: DownloadNotifyKind)

    fun setCancelHandler(kind: DownloadNotifyKind, handler: (() -> Unit)?)
}
