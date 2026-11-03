package dev.velum.app.di

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.velum.app.MainActivity
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.platform.SessionDownloadService
import dev.velum.app.ui.DeviceViewModel
import dev.velum.ble.BleTransport
import dev.velum.ble.client.CrcStatus
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceIdentityKeys
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #94 on a real Android: a simulated download through the real view model and [SessionDownloadService], backgrounded
 * and with the screen off for most of the transfer, ends with the exact bytes and a verified CRC.
 *
 * What it shows: the service stays in the foreground with its notification, and the transfer keeps moving while the
 * activity is stopped and the screen is off.
 *
 * What it can't show:
 * - That the service is why it survives: instrumentation keeps the process alive regardless.
 * - Doze: the device is plugged in and only just went idle, so Doze never starts, and none is forced here.
 * - CPU suspend: an emulator doesn't suspend, and the service holds no wake lock.
 * - A real radio: the peripheral is in-process, so no BLE stack, link loss or OEM background limit is involved.
 * Those stay with the Note Pro checks in #85.
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.di.DownloadBackgroundDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class DownloadBackgroundDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val pems = rsaKeyPairToPem()
    private val serial = "881FAKE1"
    private val session = 1_700_000_000L
    private val keys = DeviceIdentityKeys(
        serialNumber = serial,
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )
    private val plaintext = Random(94).nextBytes(TRANSFER_BYTES)
    private val encrypted = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, plaintext)

    private class Importer(private val keys: DeviceIdentityKeys) : DeviceKeyImporter {
        override suspend fun hasKeys() = true
        override suspend fun loadKeys() = keys
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    /** Spreads the transfer over the screen-off window; a real link takes minutes, an in-process one milliseconds. */
    private class PacedTransport(private val delegate: BleTransport) : BleTransport by delegate {
        override fun notifications(): Flow<ByteArray> = delegate.notifications().onEach { delay(FRAME_DELAY_MS) }
    }

    @Suppress("DEPRECATION") // getRunningServices still reports the caller's own services.
    private fun serviceInForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .firstOrNull { it.service.className == SessionDownloadService::class.java.name }?.foreground == true

    private fun notificationShown(): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == DownloadProgressNotifier.DEVICE_SESSION_NOTIFICATION_ID }

    private fun finished(vm: DeviceViewModel) = vm.sessionUi.value.downloadingSessionId == null

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out: $what", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    @Test fun transfer_finishes_with_exact_bytes_and_verified_crc_while_backgrounded_with_the_screen_off() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val power = context.getSystemService(PowerManager::class.java)
        val store = File(context.cacheDir, "download-background-synthetic").apply { deleteRecursively() }
        val peripheral = FakePlaudPeripheral(
            createSessionCipher(), keys, encrypted, sessionId = session, requirePublicKey = false,
            stopCrc = FakePlaudPeripheral.StopCrc.Ccitt,
        )
        val saved = ByteArrayOutputStream()
        val screenWasOffAtSave = AtomicBoolean(false)
        val owner = ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val vm = DeviceViewModel(
                    PacedTransport(FakeBleTransport(peripheral)),
                    Importer(keys),
                    persistDownloadedSession = { audio ->
                        screenWasOffAtSave.set(!power.isInteractive)
                        audio.writeDecryptedAudio { saved.write(it) }
                        "rec-synthetic"
                    },
                    localeTag = { "en" },
                    openPartialTransfer = { id, sn -> PartialTransferFileStore.open(store, id, sn) },
                )
                owner.put("device", vm)
                vm.connect("fake-note-pro")
                await("session list") { vm.sessionUi.value.sessions.isNotEmpty() }

                vm.downloadSession(session)
                await("service in the foreground") { serviceInForeground() }
                await("transfer started") { (vm.sessionUi.value.downloadProgress ?: 0f) > 0f }

                device.pressHome()
                await("activity stopped") { scenario.state == Lifecycle.State.CREATED }
                device.sleep()
                await("screen off") { !power.isInteractive }
                val atScreenOff = vm.sessionUi.value.downloadProgress ?: 0f
                assertTrue("The transfer must still have most of its way to go, was at $atScreenOff", atScreenOff < 0.5f)

                var moved = false
                val deadline = SystemClock.uptimeMillis() + TRANSFER_TIMEOUT_MS
                while (true) {
                    val ui = vm.sessionUi.value // one snapshot: it can finish between two reads
                    if (ui.lastDownloadedRecordingId != null) break
                    assertTrue(ui.statusMessage, ui.downloadingSessionId != null)
                    assertTrue("Timed out with the screen off", SystemClock.uptimeMillis() < deadline)
                    assertFalse("The screen came back on, so the check no longer means anything", power.isInteractive)
                    if ((ui.downloadProgress ?: 0f) > atScreenOff) moved = true
                    // Finishing right after the snapshot releases both, which is not a failure.
                    assertTrue("The service left the foreground mid-transfer", serviceInForeground() || finished(vm))
                    assertTrue("The notification disappeared mid-transfer", notificationShown() || finished(vm))
                    SystemClock.sleep(500)
                }

                assertTrue("No progress was seen with the screen off", moved)
                assertTrue("The save must also happen with the screen off", screenWasOffAtSave.get())
                assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
                assertEquals(CrcStatus.Verified, vm.sessionUi.value.lastDownloadCrc)
                assertEquals(DeviceUiCopy.downloadedSession("en", session), vm.sessionUi.value.statusMessage)
                assertArrayEquals(plaintext, saved.toByteArray())
                assertEquals("One transfer from the start, no restart", listOf(0L), peripheral.syncStartOffsets)
                await("service released") { !SessionDownloadService.wanted && !serviceInForeground() }
                assertFalse(notificationShown())
            }
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            store.deleteRecursively()
        }
    }

    private companion object {
        /** With [FRAME_DELAY_MS] and the peripheral's 100-byte chunks, about 80 s of transfer (79.8 s on the JVM). */
        const val TRANSFER_BYTES = 400_000
        const val FRAME_DELAY_MS = 15L
        const val TRANSFER_TIMEOUT_MS = 8 * 60 * 1000L
    }
}
