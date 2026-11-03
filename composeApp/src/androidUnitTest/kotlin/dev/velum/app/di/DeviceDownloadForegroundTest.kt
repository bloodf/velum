package dev.velum.app.di

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.SessionDownloadService
import dev.velum.app.platform.installAppContext
import dev.velum.app.ui.DeviceViewModel
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceIdentityKeys
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #85: the real view-model path holds the service for exactly one download. Synthetic identity, simulated peripheral. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeviceDownloadForegroundTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )

    private inner class Importer : DeviceKeyImporter {
        override suspend fun hasKeys() = true
        override suspend fun loadKeys() = keys
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    private fun transport(): FakeBleTransport {
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, byteArrayOf(1, 2, 3))
        return FakeBleTransport(FakePlaudPeripheral(createSessionCipher(), keys, file, batteryPercent = 50, requirePublicKey = false))
    }

    /** Download work leaves for Dispatchers.IO, so the virtual clock alone cannot wait for it. */
    private fun TestScope.awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for $what")
            advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    private fun downloadNotificationText(): String? =
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { manager ->
            shadowOf(manager).allNotifications.singleOrNull()?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        }

    private fun scenario(persist: suspend (CompletableDeferred<Unit>) -> String, check: suspend TestScope.(DeviceViewModel, Long, CompletableDeferred<Unit>) -> Unit) = runTest {
        installAppContext(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val reached = CompletableDeferred<Unit>()
        try {
            val vm = DeviceViewModel(
                transport(),
                Importer(),
                localeTag = { "en" },
                persistDownloadedSession = { persist(reached) },
                openPartialTransfer = { _, _ -> null },
            )
            owner.put("device", vm)
            vm.connect("fake-note-pro"); advanceUntilIdle()
            val sessionId = vm.sessionUi.value.sessions.first().sessionId
            assertFalse(SessionDownloadService.wanted)
            vm.downloadSession(sessionId)
            check(vm, sessionId, reached)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    private fun TestScope.assertHeldWhileDownloading(vm: DeviceViewModel, sessionId: Long, reached: CompletableDeferred<Unit>) {
        awaitUntil("persist") { reached.isCompleted }
        assertTrue(SessionDownloadService.wanted)
        val started = assertNotNull(shadowOf(app).nextStartedService, "the service must be started")
        assertEquals(SessionDownloadService::class.java.name, started.component?.className)
        assertEquals(DeviceUiCopy.downloadingSession("en", sessionId), downloadNotificationText())
        assertEquals(sessionId, vm.sessionUi.value.downloadingSessionId)
    }

    private fun TestScope.assertReleased() {
        awaitUntil("service release") { !SessionDownloadService.wanted }
        assertEquals(null, downloadNotificationText())
    }

    @Test fun completion_releases_the_service() {
        val finish = CompletableDeferred<Unit>()
        scenario({ reached -> reached.complete(Unit); finish.await(); "synthetic-recording" }) { vm, id, reached ->
            assertHeldWhileDownloading(vm, id, reached)
            finish.complete(Unit)
            assertReleased()
            assertEquals("synthetic-recording", vm.sessionUi.value.lastDownloadedRecordingId)
        }
    }

    @Test fun cancellation_releases_the_service() {
        scenario({ reached -> reached.complete(Unit); CompletableDeferred<String>().await() }) { vm, id, reached ->
            assertHeldWhileDownloading(vm, id, reached)
            vm.cancelDownload()
            assertReleased()
            assertEquals(DeviceUiCopy.downloadCancelled("en"), vm.sessionUi.value.statusMessage)
        }
    }

    @Test fun terminal_failure_releases_the_service() {
        val finish = CompletableDeferred<Unit>()
        scenario({ reached -> reached.complete(Unit); finish.await(); error("synthetic save failure") }) { vm, id, reached ->
            assertHeldWhileDownloading(vm, id, reached)
            finish.complete(Unit)
            assertReleased()
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
        }
    }
}
