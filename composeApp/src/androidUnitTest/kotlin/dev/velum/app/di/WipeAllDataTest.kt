package dev.velum.app.di

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.platform.ExportFolderResult
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.syncWatchedFolders
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.core.settings.ApiModelOverride
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import dev.velum.app.platform.MicInputSource
import dev.velum.app.platform.MicMarker
import dev.velum.app.platform.MicMarkerResult
import dev.velum.app.platform.MicPauseResult
import dev.velum.app.platform.MicSessionIdentity
import dev.velum.app.platform.MicSessionState
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.MicStopResult
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.sha256File
import dev.velum.app.platform.watchedFolderCount
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
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
        assertFalse(File(path).exists(), "the recording's audio survived the wipe")
    }

    /** A capture that is recording into a real file until stopped. */
    private class RecordingCapture(path: String) : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Recording(path, 0, token = 1))
        override val isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Recording
        override val isPaused = false
        override val markers = emptyList<MicMarker>()
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        var stops = 0
        val startEntered = CompletableDeferred<Unit>()
        var startGate: CompletableDeferred<Unit>? = null
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            startEntered.complete(Unit)
            startGate?.await()
            val session = MicSessionIdentity(2, absolutePath)
            sessions.value = MicSessionState.Recording(absolutePath, 0, session.token)
            return MicStartResult.Started(session)
        }
        override suspend fun stop(): MicStopResult {
            stops++
            val path = (sessions.value as MicSessionState.Recording).absolutePath
            sessions.value = MicSessionState.Stopped(path, 1, 0, token = 1)
            return MicStopResult.Stopped(path, 1, 0)
        }
        override suspend fun pause() = MicPauseResult.Unsupported
        override suspend fun resume() = MicPauseResult.Unsupported
        override suspend fun addMarker(label: String) = MicMarkerResult.Unsupported
    }

    /** Fails the API-key step of a wipe, the way a locked keystore does. */
    private class FailingApiKeyStore(private val delegate: SecureStore = InMemorySecureStore()) : SecureStore by delegate {
        override suspend fun remove(key: String) {
            if (key == SecureKeys.AI_API_KEY) throw SecureStoreUnavailableException(IllegalStateException("synthetic"))
            delegate.remove(key)
        }
    }

    /** Fails clearing the device keys, the way a locked keystore does. */
    private class FailingDeviceKeyStore(private val delegate: SecureStore = InMemorySecureStore()) : SecureStore by delegate {
        override suspend fun remove(key: String) {
            if (key.startsWith("device.")) throw SecureStoreUnavailableException(IllegalStateException("synthetic"))
            delegate.remove(key)
        }
    }

    private fun withContainer(
        store: SecureStore = InMemorySecureStore(),
        mic: MicCapture? = null,
        jobs: ((RecordingProcessor, CoroutineScope) -> RecordingJobScheduler)? = null,
        block: suspend (AppContainer, Context) -> Unit,
    ) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        installAppContext(context)
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-wipe-paths").toString())
        val container = if (mic == null) {
            AppContainer(store, DriverFactory(context), modelStoreRoot = root, recordingJobSchedulerFactory = jobs)
        } else {
            AppContainer(store, DriverFactory(context), modelStoreRoot = root, recordingJobSchedulerFactory = jobs, micCapture = mic)
        }
        try {
            runBlocking { block(container, context) }
        } finally {
            Dispatchers.resetMain()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private suspend fun AppContainer.addRecording(id: String, source: String = "import", status: String = "ready") =
        repository.insertRecording(
            id = id, sessionId = null, folderId = null, title = "Synthetic", source = source, deviceSerial = null,
            startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "synthetic transcript", summaryMarkdown = "## Synthetic", status = status,
        )

    @Test fun an_import_does_not_run_during_a_wipe() = withContainer { container, _ ->
        val audio = Files.createTempFile("velum-wipe-import", ".wav").toFile().apply { writeBytes(ByteArray(64) { 1 }) }
        // Without the gate this import would stop at the dedupe lookup and report a duplicate.
        container.repository.setMeta("import_sha256_${sha256File(audio.path)}", "rec-earlier")
        val result = container.wipeGate.wiping(1.seconds) { AudioImportPipeline(container).importFile(audio.path) }
        assertEquals(ImportResult.Failed("wipe_in_progress"), result)
        audio.delete()
    }

    @Test fun an_autoflow_export_does_not_run_during_a_wipe() = withContainer { container, _ ->
        container.addRecording("rec-export")
        var written = false
        val result = container.wipeGate.wiping(1.seconds) {
            container.exportRecordingToFolder("rec-export") { _, _ -> written = true; ExportFolderResult.Ok("synthetic") }
        }
        assertEquals(null, result)
        assertFalse(written, "an export was written during the wipe")
    }

    @Test fun a_wipe_removes_import_hashes_and_tombstones() = withContainer { container, _ ->
        container.repository.setMeta("import_sha256_synthetic", "rec-gone")
        container.repository.setMeta("import_deleted_synthetic", "1")
        container.wipeAllData()
        assertEquals(null, container.repository.getMeta("import_sha256_synthetic"))
        assertEquals(null, container.repository.getMeta("import_deleted_synthetic"))
    }

    @Test fun a_wipe_releases_every_folder_grant() = withContainer { container, context ->
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASynthetic")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        assertEquals(1, context.contentResolver.persistedUriPermissions.size)
        container.wipeAllData()
        assertEquals(emptyList(), context.contentResolver.persistedUriPermissions, "the app kept access to the user's folder")
    }

    @Test fun a_watched_folder_sync_after_a_wipe_imports_nothing() = withContainer { container, context ->
        context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit()
            .putString("tree_uris", "content://synthetic/tree").commit()
        container.wipeAllData()
        assertEquals(emptyList(), syncWatchedFolders(AudioImportPipeline(container)))
    }

    @Test fun a_wipe_deletes_shared_exports() = withContainer { container, context ->
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        File(share, "synthetic-transcript.md").writeText("synthetic")
        container.wipeAllData()
        assertFalse(share.exists(), "an exported transcript survived the wipe")
    }

    @Test fun a_wipe_drops_queued_processing_before_it_deletes_the_rows() {
        var rowsAtCancel: Long? = null
        lateinit var wiped: AppContainer
        val jobs = object : RecordingJobScheduler {
            override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?) = Unit
            override fun cancelAll() { rowsAtCancel = runBlocking { wiped.repository.storageUsage().recordingCount.toLong() } }
        }
        withContainer(jobs = { _, _ -> jobs }) { container, _ ->
            wiped = container
            container.addRecording("rec-queued")
            container.wipeAllData()
            assertEquals(1L, rowsAtCancel, "queued work was not dropped before the rows went")
        }
    }

    @Test fun a_wipe_that_fails_at_the_keys_leaves_queued_processing_alone() {
        var cancelled = false
        val jobs = object : RecordingJobScheduler {
            override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?) = Unit
            override fun cancelAll() { cancelled = true }
        }
        withContainer(store = FailingDeviceKeyStore(), jobs = { _, _ -> jobs }) { container, _ ->
            container.addRecording("rec-queued", status = "queued")
            assertFailsWith<SecureStoreUnavailableException> { container.wipeAllData() }
            assertFalse(cancelled, "queued work was cancelled but its recording stayed in the library")
            assertNotNull(container.repository.getRecording("rec-queued"))
        }
    }

    @Test fun a_wipe_stops_the_mic_and_deletes_its_file() {
        val audio = Files.createTempFile("velum-wipe-mic", ".wav").toFile().apply { writeBytes(ByteArray(64)) }
        val mic = RecordingCapture(audio.path)
        withContainer(mic = mic) { container, _ ->
            container.wipeAllData()
            assertEquals(1, mic.stops, "the wipe left the mic recording")
            assertFalse(audio.exists(), "the mic recording's file survived the wipe")
        }
    }

    @Test fun the_mic_cannot_start_during_a_wipe() {
        val mic = RecordingCapture("/synthetic/unused.wav").apply { sessions.value = MicSessionState.Idle }
        withContainer(mic = mic) { container, _ ->
            container.addRecording("rec-mic", source = "phone", status = "recording")
            val result = container.wipeGate.wiping(1.seconds) {
                container.micRecordings.startOrAttach("rec-mic", "/synthetic/rec-mic.wav")
            }
            assertIs<MicStartResult.Failed>(result)
        }
    }

    @Test fun a_wipe_forgets_watched_folders_import_copies_and_the_export_folder() = withContainer { container, context ->
        context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit()
            .putString("tree_uris", "content://synthetic/tree").commit()
        context.getSharedPreferences("velum_export_folder", Context.MODE_PRIVATE).edit()
            .putString("tree_uri", "content://synthetic/export").commit()
        val copies = File(context.cacheDir, "imports").apply { mkdirs() }
        File(copies, "synthetic.wav").writeBytes(ByteArray(8))
        assertEquals(1, watchedFolderCount())

        container.wipeAllData()

        assertEquals(0, watchedFolderCount(), "watched folders would import again after the wipe")
        assertFalse(copies.exists(), "copies of imported audio survived the wipe")
        assertEquals(null, ExportFolderStore.currentUri())
    }

    @Test fun device_keys_are_gone_even_when_a_later_wipe_step_fails() = withContainer(FailingApiKeyStore()) { container, _ ->
        container.deviceKeyImporter.importKeys(
            serialNumber = "881FAKE1",
            serialSignatureBase64 = kotlin.io.encoding.Base64.encode(ByteArray(256) { 7 }),
            bindToken = "synthetic-token",
            publicKeyPem = pems.first,
            privateKeyPem = pems.second,
        )
        assertTrue(container.deviceKeyImporter.hasKeys())
        assertFailsWith<SecureStoreUnavailableException> { container.wipeAllData() }
        assertFalse(container.deviceKeyImporter.hasKeys(), "device keys survived a wipe that failed later")
    }

    @Test fun a_mic_start_already_past_its_check_is_stopped_by_the_wipe() {
        val audio = Files.createTempFile("velum-wipe-mic-race", ".wav").toFile().apply { writeBytes(ByteArray(64)) }
        val mic = RecordingCapture(audio.path).apply {
            sessions.value = MicSessionState.Idle
            startGate = CompletableDeferred()
        }
        withContainer(mic = mic) { container, _ ->
            container.addRecording("rec-race", source = "phone", status = "recording")
            coroutineScope {
                val start = async(Dispatchers.Default) { container.micRecordings.startOrAttach("rec-race", audio.path) }
                withTimeout(10_000) { mic.startEntered.await() }
                val wipe = async(Dispatchers.Default) { container.wipeAllData() }
                delay(300) // the wipe is now waiting for the start
                mic.startGate!!.complete(Unit)
                start.await()
                wipe.await()
            }
            assertEquals(1, mic.stops, "a mic start that was already past its check kept recording after the wipe")
            assertFalse(mic.isCapturing)
            assertFalse(audio.exists(), "the recording's file survived the wipe")
        }
    }

    @Test fun no_mic_draft_row_is_written_during_a_wipe() {
        val mic = RecordingCapture("/synthetic/unused.wav").apply { sessions.value = MicSessionState.Idle }
        withContainer(mic = mic) { container, _ ->
            val dir = Files.createTempDirectory("velum-wipe-draft").toFile()
            val draft = container.wipeGate.wiping(1.seconds) {
                dev.velum.app.platform.startPhoneMicDraft(container, directory = dir.path, id = "rec-draft")
            }
            assertIs<MicStartResult.Failed>(draft.result)
            assertEquals(null, container.repository.getRecording("rec-draft"), "an empty mic row survived the wipe")
            dir.deleteRecursively()
        }
    }

    @Test fun a_shared_file_refused_by_a_wipe_is_deleted() = withContainer { container, _ ->
        val copy = Files.createTempFile("velum-wipe-share", ".wav").toFile().apply { writeBytes(ByteArray(64) { 1 }) }
        val result = container.wipeGate.wiping(1.seconds) { AudioImportPipeline(container).importShared(copy.path) }
        assertIs<ImportResult.Failed>(result)
        assertFalse(copy.exists(), "the shared file's copy survived the wipe")
    }
}
