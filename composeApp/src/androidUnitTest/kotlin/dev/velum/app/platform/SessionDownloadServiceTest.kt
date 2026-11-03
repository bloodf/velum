package dev.velum.app.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionDownloadServiceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val controller = Robolectric.buildService(SessionDownloadService::class.java)
    private val service: SessionDownloadService get() = controller.get()
    private val types = mutableListOf<Int>()
    private var notification: Notification? = null
    private var failing = emptySet<Int>()
    private var startId = 0

    @Before fun prepare() {
        installAppContext(app)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        controller.create()
        service.promote = { _, _, built, type ->
            types += type
            if (type in failing) throw SecurityException("synthetic")
            notification = built
        }
        DownloadProgressNotifier.show(DownloadNotifyKind.DeviceSession, "Velum download", "Session 7", 0.25f, "Cancel")
    }

    @After fun cleanup() {
        DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
        SessionDownloadService.stop()
    }

    private fun startCommand() = service.onStartCommand(Intent(app, SessionDownloadService::class.java), 0, ++startId)

    @Test fun connected_device_type_and_a_notification_that_names_the_download() {
        startCommand()
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE), types)
        val shown = assertNotNull(notification)
        assertEquals("Velum download", shown.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Session 7", shown.extras.getString(NotificationCompat.EXTRA_TEXT))
        assertEquals(25, shown.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertEquals("Cancel", shown.actions.single().title)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun without_bluetooth_permission_it_uses_data_sync() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        startCommand()
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC), types)
    }

    @Test fun a_refused_connected_device_promotion_falls_back_to_data_sync() {
        failing = setOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startCommand()
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
            types,
        )
        assertNotNull(notification)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun refused_on_every_type_it_stops_without_throwing() {
        failing = setOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        startCommand()
        assertEquals(2, types.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stop_before_the_start_command_still_promotes_then_stops() {
        SessionDownloadService.start(app)
        SessionDownloadService.stop()
        DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
        startCommand()
        assertEquals(1, types.size, "startForegroundService requires the promotion even when no longer wanted")
        assertNotNull(notification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stop_after_promotion_removes_the_foreground_and_stops() {
        SessionDownloadService.start(app)
        startCommand()
        assertFalse(shadowOf(service).isStoppedBySelf)
        SessionDownloadService.stop()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun timeout_cancels_the_download_and_stops() {
        var cancelled = 0
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession) { cancelled++ }
        SessionDownloadService.start(app)
        startCommand()
        service.onTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertEquals(1, cancelled)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun merged_manifest_declares_both_types_and_their_permissions() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, SessionDownloadService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toList()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in requested)
    }
}
