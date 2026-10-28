package dev.velum.app.platform

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.velum.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep-alive foreground service for BLE / SoftAP session downloads.
 *
 * Does not perform I/O itself — [dev.velum.app.download.SessionDownloadManager]
 * (app-scoped) owns the transfer and drives [DownloadProgressNotifier] progress +
 * cancel. This service only holds a `connectedDevice` FGS so the process (and
 * that manager job) survives backgrounding, Doze, and ViewModel clear.
 *
 * Invariants:
 * - [start] checks [BLUETOOTH_CONNECT] before [startForegroundService].
 * - [startForeground] always runs before any [stopSelf] on the START path
 *   (including promotion failure) to avoid ForegroundServiceDidNotStartInTimeException.
 * - [stop] uses [Context.stopService]; [onDestroy] clears the foreground notification.
 */
class SessionDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> demoteAndStop()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (foregroundHeld.compareAndSet(true, false)) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
        super.onDestroy()
    }

    private fun handleStart() {
        val promoted = try {
            promoteToForeground()
        } catch (t: Throwable) {
            Log.w(TAG, "promoteToForeground threw; entering minimal FGS before stop", t)
            false
        }
        if (!promoted) {
            // MUST: startForeground before stopSelf so startForegroundService cannot time out.
            enterMinimalForegroundThenStop()
        }
    }

    /**
     * Enter foreground immediately so [startForegroundService] cannot time out.
     * Returns false if promotion fails (caller must still [startForeground] then stop).
     */
    private fun promoteToForeground(): Boolean {
        if (forcePromoteFailureForTests) {
            throw SecurityException("forced promote failure for tests")
        }
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
            Log.w(TAG, "startForeground failed; will minimal-promote then stop", t)
            foregroundHeld.set(false)
            false
        }
    }

    /**
     * Last-resort [startForeground] so a failed promote never leaves the service
     * without having entered the foreground, then tear down.
     */
    private fun enterMinimalForegroundThenStop() {
        try {
            DownloadProgressNotifier.ensureChannel()
            val notification = minimalForegroundNotification()
            ServiceCompat.startForeground(
                this,
                DownloadProgressNotifier.notificationId(DownloadNotifyKind.DeviceSession),
                notification,
                foregroundServiceType(),
            )
            foregroundHeld.set(true)
        } catch (t: Throwable) {
            Log.e(TAG, "minimal startForeground failed", t)
        }
        demoteAndStop()
    }

    private fun minimalForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, DownloadProgressNotifier.CHANNEL_ID)
            .setContentTitle(getString(R.string.session_download_fgs_title))
            .setContentText(getString(R.string.session_download_fgs_body))
            .setSmallIcon(R.drawable.ic_stat_velum)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, 0, true)
            .build()
    }

    private fun demoteAndStop() {
        if (foregroundHeld.compareAndSet(true, false)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "dev.velum.app.download.START"
        const val ACTION_STOP = "dev.velum.app.download.STOP"
        private const val TAG = "VelumDownloadFgs"

        private val foregroundHeld = AtomicBoolean(false)

        /** True while a startForeground promotion is held (test / diagnostics). */
        fun isForegroundHeld(): Boolean = foregroundHeld.get()

        /**
         * Test seam: when true, [promoteToForeground] throws so the failure path
         * (minimal startForeground then stop) can be exercised under Robolectric.
         */
        @Volatile
        internal var forcePromoteFailureForTests: Boolean = false

        /**
         * Test seam for [ForegroundServiceStartNotAllowedException] / denied starts.
         * When non-null, invoked instead of [ContextCompat.startForegroundService].
         */
        @Volatile
        internal var startForegroundServiceHook: ((Context, Intent) -> Unit)? = null

        fun hasBluetoothConnectPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun foregroundServiceType(): Int =
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }

        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!hasBluetoothConnectPermission(appContext)) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted; skipping download FGS")
                return
            }
            DownloadProgressNotifier.ensureChannel()
            val intent = Intent(appContext, SessionDownloadService::class.java)
                .setAction(ACTION_START)
            try {
                val hook = startForegroundServiceHook
                if (hook != null) {
                    hook(appContext, intent)
                } else {
                    ContextCompat.startForegroundService(appContext, intent)
                }
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException (API 31+/14+) and
                // IllegalStateException when the app is not allowed to start an FGS.
                Log.w(TAG, "startForegroundService denied; download continues without FGS", t)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.stopService(Intent(appContext, SessionDownloadService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopService failed", t)
            }
        }

        /** Test helper: clear the held flag between Robolectric cases. */
        internal fun resetForegroundHeldForTests() {
            foregroundHeld.set(false)
            forcePromoteFailureForTests = false
            startForegroundServiceHook = null
        }
    }
}
