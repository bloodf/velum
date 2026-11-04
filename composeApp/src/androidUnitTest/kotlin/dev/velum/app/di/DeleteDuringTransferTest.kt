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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private inner class Fixture(val container: AppContainer, val vm: DeviceViewModel, val gate: TransferGate, val partials: java.io.File) {
        fun partialFiles() = partials.listFiles().orEmpty().filter { it.name.endsWith(".partial") || it.name.endsWith(".offset") }
        fun openPartial() = PartialTransferFileStore.open(partials, session, keys.serialNumber)

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

    private fun fixture(partialDeleteFails: Boolean = false, body: suspend Fixture.() -> Unit) {
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
            deletePartialTransfer = { id, sn -> !partialDeleteFails && PartialTransferFileStore.delete(partials, id, sn) },
            deletePartialTransfers = { PartialTransferFileStore.deleteAll(partials) },
        )
        owner.put("device", vm)
        try {
            runBlocking {
                vm.connect("fake-note-pro")
                withTimeout(20_000) { while (vm.sessionUi.value.sessions.isEmpty()) delay(5) }
                withTimeout(120_000) { Fixture(container, vm, gate, partials).body() }
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
        val first = assertNotNull(row())
        val release = redownloadHeld()
        assertTrue(partialFiles().isNotEmpty(), "the held transfer has a partial")

        assertTrue(container.deleteRecordingForever(first.id, vm))
        assertTrue(partialFiles().isEmpty(), "the audio must not stay recoverable from the partial")
        release.complete(Unit)
        vm.awaitDownloads()

        assertNull(row(), "the transfer must not bring the recording back")
        assertFalse(java.io.File(assertNotNull(first.local_path)).exists(), "nor its audio")
        assertEquals(0, container.repository.storageUsage().recordingCount)
        assertNull(vm.sessionUi.value.downloadingSessionId)

        // Nothing is left to resume from, so the Re-download starts from zero and reads the device again.
        assertEquals(0L, openPartial().run { length().also { close() } })
        gate.held = null
        download()
        assertNotEquals(first.id, assertNotNull(row(), "an explicit Re-download must still work").id)
    }

    @Test fun a_partial_with_no_download_running_is_removed_too() = fixture {
        download()
        val first = assertNotNull(row())
        openPartial().run { write(ByteArray(10)); length(); close() }
        assertTrue(partialFiles().isNotEmpty())

        assertTrue(container.deleteRecordingForever(first.id, vm))

        assertTrue(partialFiles().isEmpty())
    }

    @Test fun a_partial_that_stays_fails_the_delete_and_keeps_the_recording() = fixture(partialDeleteFails = true) {
        download()
        val first = assertNotNull(row())
        openPartial().run { write(ByteArray(10)); length(); close() }

        assertFalse(container.deleteRecordingForever(first.id, vm), "must not say deleted while the partial stays")

        assertNotNull(row(), "the recording stays, so trying again can finish the job")
        assertTrue(java.io.File(assertNotNull(first.local_path)).exists(), "with its audio")
        assertTrue(partialFiles().isNotEmpty())
    }

    @Test fun a_full_disk_does_not_stop_a_delete_with_no_partial() = fixture {
        download()
        val first = assertNotNull(row())
        assertTrue(partials.setWritable(false), "this test needs a directory that refuses new files")
        try {
            assertTrue(container.deleteRecordingForever(first.id, vm))
        } finally {
            partials.setWritable(true)
        }
        assertNull(row())
    }

    @Test fun a_download_still_waiting_to_start_is_stopped_too() = fixture {
        download()
        val first = assertNotNull(row()).id
        val release = redownloadHeld()
        vm.cancelDownload() // still unwinding, because the gate ignores cancellation
        gate.held = null
        vm.downloadSession(session) // queued behind it; not yet the session shown as downloading
        assertNull(vm.sessionUi.value.downloadingSessionId)

        assertTrue(container.deleteRecordingForever(first, vm))
        release.complete(Unit)
        vm.awaitDownloads()

        assertNull(row(), "the queued download must not bring the recording back")
    }

    @Test fun with_two_downloads_queued_deleting_the_earlier_one_stops_it() = fixture {
        download()
        val first = assertNotNull(row()).id
        val release = redownloadHeld()
        vm.cancelDownload()
        gate.held = null
        vm.downloadSession(session)
        vm.downloadSession(session + 1) // the newest request is another session

        assertTrue(container.deleteRecordingForever(first, vm))
        release.complete(Unit)
        vm.awaitDownloads()

        assertNull(row(), "the earlier queued download must not bring the recording back")
    }

    @Test fun with_two_downloads_queued_deleting_the_later_one_leaves_the_earlier_running() = fixture {
        download()
        val other = assertNotNull(row()).copy(id = "rec-other", session_id = session + 1)
        container.repository.insertDeviceSessionRecording(other)
        val release = redownloadHeld()
        vm.cancelDownload()
        gate.held = null
        vm.downloadSession(session)
        vm.downloadSession(other.session_id!!)

        assertTrue(container.deleteRecordingForever(other.id, vm))
        release.complete(Unit)
        vm.awaitDownloads()

        assertNotNull(vm.sessionUi.value.lastDownloadedRecordingId, "this session's queued download must still finish")
    }

    @Test fun deleting_a_recording_of_another_session_leaves_the_transfer_running() = fixture {
        download()
        val other = assertNotNull(row()).copy(id = "rec-other", session_id = session + 1)
        container.repository.insertDeviceSessionRecording(other)
        val release = redownloadHeld()

        assertTrue(container.deleteRecordingForever(other.id, vm))
        release.complete(Unit)
        vm.awaitDownloads()

        assertNotNull(row(), "this session's transfer must finish")
        assertNotNull(vm.sessionUi.value.lastDownloadedRecordingId, "the re-download completed, it was not cancelled")
    }
}
