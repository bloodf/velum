package dev.velum.app.di

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadWipeResult
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
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The whole "Wipe all data" action against a download still saving a recording into the real repository. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class WipeAllDataTest {
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

    private var dataWiped = false

    private fun wipeDuringSave(joinTimeout: kotlin.time.Duration, check: suspend (AppContainer, DeviceViewModel, DownloadWipeResult, String) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-wipe-all").toString())
        val partials = Files.createTempDirectory("velum-wipe-partials").toFile()
        val files = Files.createTempDirectory("velum-wipe-files").toFile()
        installRecordingFilesDir(files.path)
        // No processing: a job left running would write to the database after this test closes it.
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
            onDataWiped = { dataWiped = true },
        )
        val owner = ViewModelStore()
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<String>()
        val fake = FakePlaudPeripheral(createSessionCipher(), keys, encrypted, sessionId = session, requirePublicKey = false, chunkSize = 200)
        val vm = DeviceViewModel(
            FakeBleTransport(fake),
            Importer(keys),
            persistDownloadedSession = { audio ->
                // Decrypted before the cancel; the slow part is the save itself (disk and database).
                val audioBytes = java.io.ByteArrayOutputStream().also { out -> audio.writeDecryptedAudio { out.write(it) } }.toByteArray()
                val decrypted = audio.copy(writeDecryptedAudio = { sink -> sink(audioBytes); audioBytes.size.toLong() })
                saving.complete(Unit)
                withContext(NonCancellable) {
                    delay(1_000)
                    try {
                        container.persistDownloadedSession(decrypted).also { id ->
                            saved.complete(assertNotNull(container.repository.getRecording(id)?.local_path))
                        }
                    } catch (e: Throwable) {
                        saved.completeExceptionally(e)
                        throw e
                    }
                }
            },
            localeTag = { "en" },
            openPartialTransfer = { id, sn -> PartialTransferFileStore.open(partials, id, sn) },
            deletePartialTransfers = { PartialTransferFileStore.deleteAll(partials) },
            wipeJoinTimeout = joinTimeout,
        )
        owner.put("device", vm)
        try {
            runBlocking {
                vm.connect("fake-note-pro")
                withTimeout(20_000) { while (vm.sessionUi.value.sessions.isEmpty()) delay(5) }
                vm.downloadSession(session)
                withTimeout(20_000) { saving.await() }
                vm.cancelDownload()
                vm.downloadSession(session)
                val result = container.wipeAllData(vm)
                withTimeout(30_000) { vm.awaitDownloads() }
                check(container, vm, result, withTimeout(1_000) { saved.await() })
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

    @Test fun no_recording_survives_a_wipe_during_a_save() = wipeDuringSave(15.seconds) { container, _, result, path ->
        assertEquals(DownloadWipeResult.Clean, result)
        assertEquals(0, container.repository.storageUsage().recordingCount)
        assertTrue(dataWiped, "the platform wipe hook (Android's boot language copy, #348) must run")
        assertFalse(File(path).exists(), "the recording's audio survived the wipe")
    }
}
