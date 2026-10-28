package dev.velum.app.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.velum.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep-alive foreground service for BLE / SoftAP session downloads.
 *
 * Does not perform I/O itself — [DeviceViewModel] owns the transfer and drives
 * [DownloadProgressNotifier] progress + cancel. This service only holds a
 * `connectedDevice` FGS so the process survives backgrounding and Doze.
 *
 * Invariants:
 * - [startForeground] runs on the main thread immediately in [onStartCommand].
 * - Start failures (Android 14+ background restrictions, missing notification
 *   permission) are swallowed; the download continues without FGS protection.
 * - Stop removes the foreground notification and calls [stopSelf].
 */
class SessionDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!promoteToForeground()) {
                    stopSelf()
                }
            }
            ACTION_STOP -> demoteAndStop()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundHeld.set(false)
        super.onDestroy()
    }

    /**
     * Enter foreground immediately so [startForegroundService] cannot time out.
     * Returns false if promotion fails (caller should [stopSelf]).
     */
    private fun promoteToForeground(): Boolean {
        DownloadProgressNotifier.ensureChannel()
        val title = getString(R.string.session_download_fgs_title)
        val body = getString(R.string.session_download_fgs_body)
        val cancelLabel = getString(R.string.download_cancel)
        val notification = DownloadProgressNotifier.buildNotification(
            context = this,
            kind = DownloadNotifyKind.DeviceSession,
            title = title,
            body = body,
            progress = 0f,
            cancelLabel = cancelLabel,
            asForegroundService = true,
        )
        return try {
            ServiceCompat.startForeground(
                this,
                DownloadProgressNotifier.notificationId(DownloadNotifyKind.DeviceSession),
                notification,
                foregroundServiceType(),
            )
            foregroundHeld.set(true)
            true
        } catch (t: Throwable) {
            // SecurityException (POST_NOTIFICATIONS) or FGS type/policy failures.
            Log.w(TAG, "startForeground failed; download continues without FGS", t)
            foregroundHeld.set(false)
            false
        }
    }

    private fun demoteAndStop() {
        if (foregroundHeld.compareAndSet(true, false)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Pre-U: connectedDevice type did not exist; 0 lets ServiceCompat
            // use the legacy startForeground(id, notification) overload.
            0
        } else {
            0
        }

    companion object {
        const val ACTION_START = "dev.velum.app.download.START"
        const val ACTION_STOP = "dev.velum.app.download.STOP"
        private const val TAG = "VelumDownloadFgs"

        private val foregroundHeld = AtomicBoolean(false)

        /** True while a startForeground promotion is held (test / diagnostics). */
        fun isForegroundHeld(): Boolean = foregroundHeld.get()

        fun start(context: Context) {
            val appContext = context.applicationContext
            DownloadProgressNotifier.ensureChannel()
            val intent = Intent(appContext, SessionDownloadService::class.java)
                .setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException (API 31+/14+) and
                // IllegalStateException when the app is not allowed to start an FGS.
                Log.w(TAG, "startForegroundService denied; download continues without FGS", t)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.startService(
                    Intent(appContext, SessionDownloadService::class.java).setAction(ACTION_STOP),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "stop service failed", t)
            }
        }

        /** Test helper: clear the held flag between Robolectric cases. */
        internal fun resetForegroundHeldForTests() {
            foregroundHeld.set(false)
        }
    }
}
