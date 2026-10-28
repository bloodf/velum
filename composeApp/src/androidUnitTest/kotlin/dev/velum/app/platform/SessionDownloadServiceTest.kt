package dev.velum.app.platform

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionDownloadServiceTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        installAppContext(app)
        SessionDownloadService.resetForegroundHeldForTests()
        DownloadProgressNotifier.ensureChannel()
        Shadows.shadowOf(app).clearStartedServices()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @After
    fun tearDown() {
        SessionDownloadService.resetForegroundHeldForTests()
    }

    @Test
    fun startCommand_promotesToForeground_withDownloadNotificationId() {
        val intent = Intent(app, SessionDownloadService::class.java)
            .setAction(SessionDownloadService.ACTION_START)
        val controller = Robolectric.buildService(SessionDownloadService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        val shadow = Shadows.shadowOf(service)

        assertTrue(SessionDownloadService.isForegroundHeld())
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertEquals(
            DownloadProgressNotifier.notificationId(DownloadNotifyKind.DeviceSession),
            shadow.lastForegroundNotificationId,
        )
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            SessionDownloadService.lastStartForegroundTypeForTests,
        )
        controller.destroy()
    }

    @Test
    fun promoteFailure_usesShortServiceThenStopSelf_andSurfacesError() {
        var surfaced: String? = null
        SessionDownloadService.promoteFailureHandler = { surfaced = it }
        SessionDownloadService.forcePromoteFailureForTests = true
        val intent = Intent(app, SessionDownloadService::class.java)
            .setAction(SessionDownloadService.ACTION_START)
        val controller = Robolectric.buildService(SessionDownloadService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val shadow = Shadows.shadowOf(controller.get())

        // shortService startForeground must run before stopSelf (no crash on API 34+).
        assertEquals(
            DownloadProgressNotifier.notificationId(DownloadNotifyKind.DeviceSession),
            shadow.lastForegroundNotificationId,
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            SessionDownloadService.lastStartForegroundTypeForTests,
        )
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(SessionDownloadService.isForegroundHeld())
        assertNotNull(surfaced)
        assertTrue(surfaced!!.isNotBlank())
        controller.destroy()
    }

    @Test
    fun companionStart_whenBluetoothDenied_returnsDeniedWithoutStartingService() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val result = SessionDownloadService.start(app)
        assertEquals(SessionDownloadServiceStartResult.DeniedBluetooth, result)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun companionStart_dispatchesStartForegroundServiceIntent() {
        val result = SessionDownloadService.start(app)
        assertEquals(SessionDownloadServiceStartResult.Started, result)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SessionDownloadService.ACTION_START, started!!.action)
        assertEquals(
            SessionDownloadService::class.java.name,
            started.component?.className,
        )
    }

    @Test
    fun companionStart_swallowsForegroundServiceStartNotAllowedException() {
        var hooked = false
        SessionDownloadService.startForegroundServiceHook = { _, _ ->
            hooked = true
            throw ForegroundServiceStartNotAllowedException(
                "denied for test",
            )
        }
        val result = SessionDownloadService.start(app)
        assertTrue(hooked)
        assertEquals(SessionDownloadServiceStartResult.Failed, result)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun companionStop_usesStopService() {
        SessionDownloadService.stop(app)
        val stopped = Shadows.shadowOf(app).nextStoppedService
        assertNotNull(stopped)
        assertEquals(
            SessionDownloadService::class.java.name,
            stopped!!.component?.className,
        )
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun stopService_clearsForeground() {
        val start = Intent(app, SessionDownloadService::class.java)
            .setAction(SessionDownloadService.ACTION_START)
        val controller = Robolectric.buildService(SessionDownloadService::class.java, start)
            .create()
            .startCommand(0, 1)
        assertTrue(SessionDownloadService.isForegroundHeld())

        SessionDownloadService.stop(app)
        val stopped = Shadows.shadowOf(app).nextStoppedService
        assertNotNull(stopped)
        controller.destroy()
        assertFalse(SessionDownloadService.isForegroundHeld())
    }

    @Test
    fun foregroundServiceType_isConnectedDeviceOnApi29Plus() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            SessionDownloadService.connectedDeviceType(),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            SessionDownloadService.shortServiceType(),
        )
    }

    @Test
    fun notificationCancelBroadcast_invokesRegisteredHandler() {
        var cancelled = false
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession) {
            cancelled = true
        }
        DownloadProgressNotifier.onCancelBroadcast(DownloadNotifyKind.DeviceSession.name)
        assertTrue(cancelled)
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession, null)
    }
}
