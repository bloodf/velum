package dev.velum.app.platform

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #85 on a real Android: the system accepts the promotion and removes the service again, which Robolectric does
 * not enforce. No download or recorder involved; hardware behaviour stays with the Note Pro checks.
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.platform.SessionDownloadServiceDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class SessionDownloadServiceDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION") // getRunningServices still reports the caller's own services.
    private fun service(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .firstOrNull { it.service.className == SessionDownloadService::class.java.name }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            assertTrue("Timed out: $what", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun holdsAndReleases() {
        ActivityScenario.launch(MainActivity::class.java).use {
            DownloadProgressNotifier.show(DownloadNotifyKind.DeviceSession, "Velum", "Synthetic session", 0f, "Cancel")
            await("service in the foreground") { service()?.foreground == true }
            DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
            await("service stopped") { service() == null }
            assertFalse(SessionDownloadService.wanted)
        }
    }

    @Test fun promotes_to_connected_device_and_stops() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        holdsAndReleases()
    }

    @Test fun promotes_to_data_sync_without_bluetooth_and_stops() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.revokeRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        holdsAndReleases()
    }
}
