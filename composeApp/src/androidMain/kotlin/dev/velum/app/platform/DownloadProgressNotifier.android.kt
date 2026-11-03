package dev.velum.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.velum.app.R

actual object DownloadProgressNotifier {
    private const val CHANNEL_ID = "velum_downloads"
    private const val ACTION_CANCEL = "dev.velum.app.download.CANCEL"
    private const val EXTRA_KIND = "kind"

    private val handlers = mutableMapOf<DownloadNotifyKind, (() -> Unit)?>()
    private val lastTitles = mutableMapOf<DownloadNotifyKind, Pair<String, String>>()
    private var deviceSessionProgress = 0f

    internal const val DEVICE_SESSION_NOTIFICATION_ID = 0x56454C10

    actual fun ensureChannel() {
        val context = appContextOrNull() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.download_channel_desc)
                setShowBadge(false)
            },
        )
    }

    actual fun show(
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    ) {
        val context = appContextOrNull() ?: return
        ensureChannel()
        lastTitles[kind] = title to body
        if (kind == DownloadNotifyKind.DeviceSession) {
            deviceSessionProgress = progress
            SessionDownloadService.start(context)
        }
        post(context, kind, title, body, progress, cancelLabel)
    }

    actual fun update(kind: DownloadNotifyKind, progress: Float, body: String?) {
        val context = appContextOrNull() ?: return
        val (title, prevBody) = lastTitles[kind] ?: return
        val nextBody = body ?: prevBody
        lastTitles[kind] = title to nextBody
        if (kind == DownloadNotifyKind.DeviceSession) deviceSessionProgress = progress
        val cancelLabel = context.getString(R.string.download_cancel)
        post(context, kind, title, nextBody, progress, cancelLabel)
    }

    actual fun dismiss(kind: DownloadNotifyKind) {
        if (kind == DownloadNotifyKind.DeviceSession) SessionDownloadService.stop()
        val context = appContextOrNull() ?: return
        NotificationManagerCompat.from(context).cancel(notificationId(kind))
        lastTitles.remove(kind)
        handlers.remove(kind)
    }

    actual fun setCancelHandler(kind: DownloadNotifyKind, handler: (() -> Unit)?) {
        if (handler == null) handlers.remove(kind) else handlers[kind] = handler
    }

    internal fun cancelDeviceSession() {
        handlers[DownloadNotifyKind.DeviceSession]?.invoke()
    }

    /** What the service shows; falls back to the channel name if the download already ended. */
    internal fun deviceSessionNotification(context: Context): Notification {
        val fallback = context.getString(R.string.download_channel_name)
        val (title, body) = lastTitles[DownloadNotifyKind.DeviceSession] ?: (fallback to fallback)
        return build(
            context,
            DownloadNotifyKind.DeviceSession,
            title,
            body,
            deviceSessionProgress,
            context.getString(R.string.download_cancel),
        )
    }

    internal fun onCancelBroadcast(kindName: String) {
        val kind = runCatching { DownloadNotifyKind.valueOf(kindName) }.getOrNull() ?: return
        handlers[kind]?.invoke()
    }

    private fun post(
        context: Context,
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val notification = build(context, kind, title, body, progress, cancelLabel)
        try {
            NotificationManagerCompat.from(context).notify(notificationId(kind), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify.
        }
    }

    private fun build(
        context: Context,
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    ): Notification {
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            notificationId(kind),
            Intent(context, DownloadCancelReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_velum)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct, progress <= 0f)
            .addAction(0, cancelLabel, cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationId(kind: DownloadNotifyKind): Int = when (kind) {
        DownloadNotifyKind.DeviceSession -> DEVICE_SESSION_NOTIFICATION_ID
        DownloadNotifyKind.LocalModel -> 0x56454C11
    }
}

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val kind = intent?.getStringExtra("kind") ?: return
        DownloadProgressNotifier.onCancelBroadcast(kind)
    }
}
