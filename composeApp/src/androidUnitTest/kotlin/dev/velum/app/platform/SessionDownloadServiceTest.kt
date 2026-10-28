package dev.velum.app.platform

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        controller.destroy()
    }

    @Test
    fun stopCommand_clearsForegroundAndStops() {
        val start = Intent(app, SessionDownloadService::class.java)
            .setAction(SessionDownloadService.ACTION_START)
        val controller = Robolectric.buildService(SessionDownloadService::class.java, start)
            .create()
            .startCommand(0, 1)
        assertTrue(SessionDownloadService.isForegroundHeld())

        val stop = Intent(app, SessionDownloadService::class.java)
            .setAction(SessionDownloadService.ACTION_STOP)
        controller.withIntent(stop).startCommand(0, 2)

        assertFalse(SessionDownloadService.isForegroundHeld())
        val shadow = Shadows.shadowOf(controller.get())
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        controller.destroy()
    }

    @Test
    fun companionStart_dispatchesStartForegroundServiceIntent() {
        SessionDownloadService.start(app)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SessionDownloadService.ACTION_START, started!!.action)
        assertEquals(
            SessionDownloadService::class.java.name,
            started.component?.className,
        )
    }

    @Test
    fun companionStop_dispatchesStopServiceIntent() {
        SessionDownloadService.stop(app)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SessionDownloadService.ACTION_STOP, started!!.action)
    }
}
