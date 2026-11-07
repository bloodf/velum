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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process in the foreground while a device session downloads (#85, reused by #94). It does no I/O:
 * the transfer runs in the app-scoped DeviceViewModel, and [DownloadProgressNotifier] starts and stops this
 * service with the DeviceSession notification, so any transport under that lifecycle is covered.
 *
 * Prefers connectedDevice, which has no Android 15 time limit but needs BLUETOOTH_CONNECT. dataSync needs no
 * runtime permission, so it is the fallback, and its time limit cancels the download (the partial stays).
 */
class SessionDownloadService : Service() {
    internal var promote: (Service, Int, Notification, Int) -> Unit = { service, id, notification, type ->
        ServiceCompat.startForeground(service, id, notification, type)
    }
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            latestStartId = startId
            active = this
            // startForegroundService requires the promotion even if stop() already ran.
            val promoted = promoteToForeground()
            if (!promoted || !wanted) stopNow()
        }
        return START_NOT_STICKY
    }

    /** Android 15's dataSync limit: the service must stop within seconds or the app crashes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        synchronized(lock) {
            // A delayed timeout must not cancel the download owned by a newer start or instance.
            if (active !== this || startId != latestStartId || !wanted) return
            Log.w(TAG, "Foreground time limit reached; cancelling the download")
            wanted = false
            try {
                DownloadProgressNotifier.cancelDeviceSession()
            } finally {
                stopNow()
            }
        }
    }

    override fun onDestroy() {
        synchronized(lock) { if (active === this) active = null }
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean {
        val notification = DownloadProgressNotifier.deviceSessionNotification(this)
        val types = buildList {
            if (hasBluetoothConnect()) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        for (type in types) {
            try {
                promote(this, DownloadProgressNotifier.DEVICE_SESSION_NOTIFICATION_ID, notification, type)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground type $type refused: ${e::class.simpleName}")
            }
        }
        return false
    }

    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun stopNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e::class.simpleName}")
        }
        // A newer start still pending keeps the service alive; its command promotes again.
        stopSelfResult(latestStartId)
    }

    companion object {
        private const val TAG = "SessionDownloadService"
        private val lock = Any()
        private var active: SessionDownloadService? = null

        @Volatile internal var wanted = false
            private set

        /** A refused start leaves the download running as today, with the plain notification. */
        fun start(context: Context) {
            synchronized(lock) { wanted = true }
            try {
                ContextCompat.startForegroundService(context, Intent(context, SessionDownloadService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start: ${e::class.simpleName}")
            }
        }

        fun stop() {
            synchronized(lock) {
                wanted = false
                active?.stopNow()
            }
        }
    }
}
