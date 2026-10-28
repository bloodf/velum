package dev.velum.app.platform

actual object DownloadProgressNotifier {
    actual fun ensureChannel() = Unit

    actual fun show(
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    ) = Unit

    actual fun update(kind: DownloadNotifyKind, progress: Float, body: String?) = Unit

    actual fun dismiss(kind: DownloadNotifyKind) = Unit

    actual fun setCancelHandler(kind: DownloadNotifyKind, handler: (() -> Unit)?) = Unit
}
