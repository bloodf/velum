package dev.velum.app.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.ui.DeviceViewModel
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.InMemorySecureStore
import dev.velum.protocol.DeviceIdentityKeys
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #407: a recording deleted for good while its session transfers stays deleted, and Re-download still works. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class DeleteDuringTransferTest {
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )
    private val session = 1_700_000_000L
    private val encrypted = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, Random(21).nextBytes(40_000))

    private class Importer(private val keys: DeviceIdentityKeys) : DeviceKeyImporter {
        override suspend fun hasKeys() = true
        override suspend fun loadKeys() = keys
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    /** Holds the end of a transfer open, ignoring cancellation, as a BLE write that doesn't notice a cancel would. */
    private class TransferGate {
        @Volatile var held: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<Unit>()
        suspend fun pass() {
            val gate = held ?: return
            reached.complete(Unit)
            withContext(NonCancellable) { gate.await() }
        }
    }

    private inner class Fixture(val container: AppContainer, val vm: DeviceViewModel, val gate: TransferGate) {
        suspend fun row() = container.repository.findDeviceSessionRecording(session, keys.serialNumber)

        suspend fun download() {
            vm.downloadSession(session)
            vm.awaitDownloads()
        }

        /** A re-download of a session already in the library, held at the end of its transfer. */
        suspend fun redownloadHeld(): CompletableDeferred<Unit> {
            val release = CompletableDeferred<Unit>()
            gate.held = release
            vm.downloadSession(session)
            withTimeout(20_000) { gate.reached.await() }
            return release
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-delete-models").toString())
        val partials = Files.createTempDirectory("velum-delete-partials").toFile()
        val files = Files.createTempDirectory("velum-delete-files").toFile()
        installRecordingFilesDir(files.path)
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
        val gate = TransferGate()
        val owner = ViewModelStore()
        val fake = FakePlaudPeripheral(createSessionCipher(), keys, encrypted, sessionId = session, requirePublicKey = false, chunkSize = 200)
        val vm = DeviceViewModel(
            FakeBleTransport(fake),
            Importer(keys),
            persistDownloadedSession = { audio ->
                gate.pass()
                container.persistDownloadedSession(audio)
            },
            localeTag = { "en" },
            openPartialTransfer = { id, sn -> PartialTransferFileStore.open(partials, id, sn) },
            deletePartialTransfers = { PartialTransferFileStore.deleteAll(partials) },
        )
        owner.put("device", vm)
        try {
            runBlocking {
                vm.connect("fake-note-pro")
                withTimeout(20_000) { while (vm.sessionUi.value.sessions.isEmpty()) delay(5) }
                withTimeout(120_000) { Fixture(container, vm, gate).body() }
            }
        } finally {
            owner.clear()
            Dispatchers.resetMain()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            partials.deleteRecursively()
            installRecordingFilesDir(null)
            files.deleteRecursively()
        }
    }

    @Test fun a_recording_deleted_during_its_transfer_stays_deleted_and_re_download_works() = fixture {
        download()
        val first = assertNotNull(row()).id
        val release = redownloadHeld()

        container.deleteRecordingForever(first, vm)
        release.complete(Unit)
        vm.awaitDownloads()

        assertNull(row(), "the transfer must not bring the recording back")
        assertEquals(0, container.repository.storageUsage().recordingCount)
        assertNull(vm.sessionUi.value.downloadingSessionId)

        gate.held = null
        download()
        assertNotEquals(first, assertNotNull(row(), "an explicit Re-download must still work").id)
    }

    @Test fun a_download_still_waiting_to_start_is_stopped_too() = fixture {
        download()
        val first = assertNotNull(row()).id
        val release = redownloadHeld()
        vm.cancelDownload() // still unwinding, because the gate ignores cancellation
        gate.held = null
        vm.downloadSession(session) // queued behind it; not yet the session shown as downloading
        assertNull(vm.sessionUi.value.downloadingSessionId)

        container.deleteRecordingForever(first, vm)
        release.complete(Unit)
        vm.awaitDownloads()

        assertNull(row(), "the queued download must not bring the recording back")
    }

    @Test fun deleting_a_recording_of_another_session_leaves_the_transfer_running() = fixture {
        download()
        val other = assertNotNull(row()).copy(id = "rec-other", session_id = session + 1)
        container.repository.insertDeviceSessionRecording(other)
        val release = redownloadHeld()

        container.deleteRecordingForever(other.id, vm)
        release.complete(Unit)
        vm.awaitDownloads()

        assertNotNull(row(), "this session's transfer must finish")
        assertNotNull(vm.sessionUi.value.lastDownloadedRecordingId, "the re-download completed, it was not cancelled")
    }
}
