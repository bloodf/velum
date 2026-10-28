package dev.velum.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.velum.app.R

actual object DownloadProgressNotifier {
    internal const val CHANNEL_ID = "velum_downloads"
    private const val ACTION_CANCEL = "dev.velum.app.download.CANCEL"
    private const val EXTRA_KIND = "kind"

    private val handlers = mutableMapOf<DownloadNotifyKind, (() -> Unit)?>()
    private val lastTitles = mutableMapOf<DownloadNotifyKind, Pair<String, String>>()

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
        post(context, kind, title, body, progress, cancelLabel)
    }

    actual fun update(kind: DownloadNotifyKind, progress: Float, body: String?) {
        val context = appContextOrNull() ?: return
        val (title, prevBody) = lastTitles[kind] ?: return
        val nextBody = body ?: prevBody
        lastTitles[kind] = title to nextBody
        val cancelLabel = context.getString(R.string.download_cancel)
        post(context, kind, title, nextBody, progress, cancelLabel)
    }

    actual fun dismiss(kind: DownloadNotifyKind) {
        val context = appContextOrNull() ?: return
        // When the session-download FGS holds this notification, stopForeground(REMOVE)
        // already cleared it — cancel is still safe / idempotent.
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(kind))
        }
        lastTitles.remove(kind)
        handlers.remove(kind)
    }

    actual fun setCancelHandler(kind: DownloadNotifyKind, handler: (() -> Unit)?) {
        if (handler == null) handlers.remove(kind) else handlers[kind] = handler
    }

    internal fun onCancelBroadcast(kindName: String) {
        val kind = runCatching { DownloadNotifyKind.valueOf(kindName) }.getOrNull() ?: return
        handlers[kind]?.invoke()
    }

    internal fun notificationId(kind: DownloadNotifyKind): Int = when (kind) {
        DownloadNotifyKind.DeviceSession -> 0x56454C10
        DownloadNotifyKind.LocalModel -> 0x56454C11
    }

    /**
     * Shared builder for ordinary progress posts and [SessionDownloadService]
     * [android.app.Service.startForeground]. Same id + cancel action either way.
     */
    internal fun buildNotification(
        context: Context,
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
        asForegroundService: Boolean = false,
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_velum)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct, progress <= 0f)
            .addAction(0, cancelLabel, cancelIntent)
        if (asForegroundService) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun post(
        context: Context,
        kind: DownloadNotifyKind,
        title: String,
        body: String,
        progress: Float,
        cancelLabel: String,
    ) {
        val notification = buildNotification(
            context = context,
            kind = kind,
            title = title,
            body = body,
            progress = progress,
            cancelLabel = cancelLabel,
            asForegroundService = kind == DownloadNotifyKind.DeviceSession &&
                SessionDownloadService.isForegroundHeld(),
        )
        // POST_NOTIFICATIONS may be denied — never crash the download path.
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(kind), notification)
        }
    }
}

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val kind = intent?.getStringExtra("kind") ?: return
        DownloadProgressNotifier.onCancelBroadcast(kind)
    }
}
