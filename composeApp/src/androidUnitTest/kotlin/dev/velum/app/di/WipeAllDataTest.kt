package dev.velum.app.di

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.platform.ExportFolderResult
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.FakeDocumentsProvider
import dev.velum.app.platform.releaseGrants
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
import dev.velum.app.platform.recordingsDirectoryPath
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
import kotlin.time.Duration.Companion.milliseconds
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
import org.robolectric.Robolectric
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
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } },
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

    /** Reads fail while the keystore is locked; removing an entry only touches preferences, so it succeeds. */
    private class LockedReadsStore(private val delegate: SecureStore = InMemorySecureStore()) : SecureStore by delegate {
        override suspend fun getString(key: String): String? =
            throw SecureStoreUnavailableException(IllegalStateException("synthetic"))
    }

    /** Fails removing the API key family, the way a store that cannot commit does. */
    private class FailingPrefixStore(private val delegate: SecureStore = InMemorySecureStore()) : SecureStore by delegate {
        override suspend fun removeWithPrefix(prefix: String) {
            throw SecureStoreUnavailableException(IllegalStateException("synthetic"))
        }
    }

    /** Fails clearing the device keys, the way a locked keystore does. */
    private class FailingDeviceKeyStore(private val delegate: SecureStore = InMemorySecureStore()) : SecureStore by delegate {
        override suspend fun remove(key: String) {
            if (key.startsWith("device.")) throw SecureStoreUnavailableException(IllegalStateException("synthetic"))
            delegate.remove(key)
        }
    }

    private var lastRoot: ModelStoreDir? = null
    private fun root() = lastRoot!!

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
        lastRoot = root
        val files = Files.createTempDirectory("velum-wipe-files").toFile()
        installRecordingFilesDir(files.path)
        val container = if (mic == null) {
            AppContainer(store, DriverFactory(context), modelStoreRoot = root, recordingJobSchedulerFactory = jobs)
        } else {
            AppContainer(store, DriverFactory(context), modelStoreRoot = root, recordingJobSchedulerFactory = jobs, micCapture = mic)
        }
        try {
            runBlocking {
                // Otherwise the wipe can't vouch for the API keys. A store that can't be read stays unloaded.
                runCatching { container.settingsRepository.load() }
                block(container, context)
            }
        } finally {
            Dispatchers.resetMain()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            installRecordingFilesDir(null)
            files.deleteRecursively()
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

    // #423: the last wipe step rewrites the database file. The real modes (FTS, WAL, secure_delete) are covered in :core
    // and by the emulator scan; Robolectric's framework SQLite reports no FTS and a non-WAL journal.
    @Test fun a_wipe_runs_the_database_rewrite_as_a_step() = withContainer { container, _ ->
        var runs = 0
        container.compactDatabaseForTest = { runs++; true }
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertEquals(1, runs)
    }

    @Test fun a_failed_database_rewrite_makes_the_wipe_incomplete_and_the_other_steps_still_ran() = withContainer { container, _ ->
        container.compactDatabaseForTest = { false }
        container.repository.insertRecording(
            id = "rec-x", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null, startedAt = 1,
            durationSeconds = 0, sizeBytes = 0, localPath = null, transcript = "words", summaryMarkdown = null, status = "ready",
        )
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
        assertEquals(null, container.repository.getRecording("rec-x"), "the rows were deleted before the failing step")
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
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        val tree = FakeDocumentsProvider.treeUri()
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit()
            .putString("tree_uris", tree.toString()).commit()
        container.wipeAllData()
        assertEquals(emptyList(), syncWatchedFolders(AudioImportPipeline(container)))
        assertEquals(emptyList(), context.contentResolver.persistedUriPermissions, "the app kept access to the folder")
    }

    @Test fun a_read_only_folder_grant_is_released() = withContainer { container, context ->
        val tree = FakeDocumentsProvider.treeUri()
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertEquals(emptyList(), context.contentResolver.persistedUriPermissions)
    }

    @Test fun a_refused_grant_release_does_not_stop_the_others() = withContainer { _, context ->
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val first = Uri.parse("content://${FakeDocumentsProvider.AUTHORITY}/tree/first")
        val second = Uri.parse("content://${FakeDocumentsProvider.AUTHORITY}/tree/second")
        context.contentResolver.takePersistableUriPermission(first, flags)
        context.contentResolver.takePersistableUriPermission(second, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val asked = mutableListOf<Pair<Uri, Int>>()
        releaseGrants(context.contentResolver.persistedUriPermissions.toList()) { uri, granted ->
            asked += uri to granted
            if (asked.size == 1) throw SecurityException("synthetic")
        }
        assertEquals(2, asked.size, "a refusal stopped the remaining releases")
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, asked.toMap()[second], "released with access it does not have")
        for ((uri, granted) in asked) context.contentResolver.releasePersistableUriPermission(uri, granted)
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
            override suspend fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?): dev.velum.ai.pipeline.EnqueueResult = dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED
            override fun cancelAll() { rowsAtCancel = runBlocking { wiped.repository.storageUsage().recordingCount.toLong() } }
        }
        withContainer(jobs = { _, _ -> jobs }) { container, _ ->
            wiped = container
            container.addRecording("rec-queued")
            container.wipeAllData()
            assertEquals(1L, rowsAtCancel, "queued work was not dropped before the rows went")
        }
    }

    @Test fun a_wipe_that_fails_at_the_rows_reports_it_and_runs_the_rest() = withContainer { container, context ->
        container.addRecording("rec-queued", status = "queued")
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        File(share, "synthetic.md").writeText("synthetic")
        container.databaseHandle.driver.execute(
            null,
            "CREATE TRIGGER synthetic_failure BEFORE DELETE ON recording BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
            0,
        )
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
        assertFalse(share.exists(), "the steps after the failure did not run")
    }

    @Test fun a_failed_step_outranks_work_still_running() = withContainer(FailingDeviceKeyStore()) { container, _ ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val job = async(Dispatchers.Default) {
                container.wipeGate.guard { withContext(NonCancellable) { started.complete(Unit); release.await() } }
            }
            started.await()
            try {
                // StillRunning's message says secrets were wiped, which is not so here.
                assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData(joinTimeout = 100.milliseconds))
            } finally {
                release.complete(Unit)
            }
            job.await()
        }
    }

    @Test fun api_keys_are_removed_and_the_wipe_is_clean_when_the_keystore_is_locked() {
        // The real store: reads fail while locked, so settings never load, and removing an entry still succeeds.
        val delegate = InMemorySecureStore()
        runBlocking {
            delegate.putString(SecureKeys.AI_API_KEY, "synthetic-legacy")
            delegate.putString(SecureKeys.apiKey("default"), "synthetic-default")
            delegate.putString(SecureKeys.apiKey("never-listed"), "synthetic-unlisted")
        }
        withContainer(LockedReadsStore(delegate)) { container, _ ->
            assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
            assertFalse(delegate.contains(SecureKeys.AI_API_KEY), "legacy key survived")
            assertFalse(delegate.contains(SecureKeys.apiKey("default")), "profile key survived")
            assertFalse(delegate.contains(SecureKeys.apiKey("never-listed")), "unlisted profile key survived")
        }
    }

    @Test fun a_prefix_removal_that_truly_fails_keeps_the_wipe_incomplete() = withContainer(FailingPrefixStore()) { container, _ ->
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
    }

    @Test fun audio_no_row_points_at_is_deleted() = withContainer { container, _ ->
        val recordings = File(recordingsDirectoryPath()).apply { mkdirs() }
        val crashedSave = File(recordings, "rec-x.ogg").apply { writeBytes(ByteArray(8)) }
        val crashedMic = File(recordings, "rec-y.wav.partial").apply { writeBytes(ByteArray(8)) }
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertFalse(crashedSave.exists(), "a saved file with no row survived")
        assertFalse(crashedMic.exists(), "a crashed mic recording survived")
    }

    @Test fun a_wipe_deletes_a_dead_transfers_temp_file() = withContainer { container, _ ->
        val temp = File.createTempFile("velum-ble-session-", ".bin").apply { writeBytes(ByteArray(8)) }
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertFalse(temp.exists(), "a transfer's temp file survived")
    }

    @Test fun a_locked_keystore_does_not_stop_the_rest_of_the_wipe() = withContainer(FailingDeviceKeyStore()) { container, context ->
        container.addRecording("rec-a")
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        File(share, "synthetic.md").writeText("synthetic")
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
        assertEquals(null, container.repository.getRecording("rec-a"), "the recordings stayed")
        assertFalse(share.exists(), "the exports stayed")
    }

    @Test fun audio_that_cannot_be_deleted_is_reported() = withContainer { container, _ ->
        // A non-empty directory at the path can't be deleted as a file.
        val stuck = Files.createTempDirectory("velum-wipe-stuck").toFile()
        File(stuck, "inner").writeText("synthetic")
        container.repository.insertRecording(
            id = "rec-stuck", sessionId = null, folderId = null, title = "Synthetic", source = "import", deviceSerial = null,
            startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = stuck.path,
            transcript = null, summaryMarkdown = null, status = "ready",
        )
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
        stuck.deleteRecursively()
    }

    @Test fun exports_that_cannot_be_deleted_are_reported() = withContainer { container, context ->
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        File(share, "synthetic.md").writeText("synthetic")
        share.setWritable(false)
        try {
            assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
        } finally {
            share.setWritable(true)
            share.deleteRecursively()
        }
    }

    @Test fun a_clean_wipe_reports_clean() = withContainer { container, _ ->
        container.addRecording("rec-a")
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
    }

    @Test fun a_wipe_deletes_decoded_audio() = withContainer { container, _ ->
        assertEquals(root().absolutePath, container.recordingProcessor.pcmSpillRoot.absolutePath, "the processor spills elsewhere")
        val spill = container.recordingProcessor.pcmSpillRoot.resolve("tmp").resolve("pcm-rec")
        spill.createDirectories()
        spill.resolve("audio.pcm").writeBytes(ByteArray(8))
        val quarantine = File(recordingsDirectoryPath(), ".orphaned").apply { mkdirs() }
        File(quarantine, "rec.wav").writeBytes(ByteArray(8))
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertFalse(spill.exists(), "decoded audio from a dead process survived")
        assertFalse(quarantine.exists(), "quarantined audio survived")
    }


    @Test fun a_wipe_leaves_what_a_job_that_has_not_stopped_may_own() = withContainer { container, _ ->
        val spill = root().resolve("tmp").resolve("pcm-live")
        spill.createDirectories()
        val recordings = File(recordingsDirectoryPath()).apply { mkdirs() }
        val writing = File(recordings, "rec-live.wav").apply { writeBytes(ByteArray(8)) }
        val temp = File.createTempFile("velum-ble-session-", ".bin").apply { writeBytes(ByteArray(8)) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val job = async(Dispatchers.Default) {
                container.wipeGate.guard { withContext(NonCancellable) { started.complete(Unit); release.await() } }
            }
            started.await()
            try {
                assertEquals(DownloadWipeResult.StillRunning, container.wipeAllData(joinTimeout = 100.milliseconds))
                assertTrue(spill.exists(), "the wipe deleted a directory a live job owns")
                assertTrue(writing.exists(), "the wipe deleted a file a live job may be writing")
                assertTrue(temp.exists(), "the wipe deleted a live transfer's temp file")
            } finally {
                release.complete(Unit)
                temp.delete()
            }
            job.await()
        }
    }

    @Test fun a_wipe_clears_the_old_spill_root_too() = withContainer { container, _ ->
        val old = defaultModelStoreRoot().resolve("tmp").resolve("pcm-old")
        old.createDirectories()
        old.resolve("audio.pcm").writeBytes(ByteArray(8))
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertFalse(old.exists(), "decoded audio under the old root survived")
    }

    @Test fun a_wipe_does_not_follow_links_out_of_the_recordings_folder() = withContainer { container, _ ->
        val outside = Files.createTempDirectory("velum-wipe-outside").toFile()
        val kept = File(outside, "keep.wav").apply { writeBytes(ByteArray(8)) }
        val recordings = File(recordingsDirectoryPath()).apply { mkdirs() }
        val link = File(recordings, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertTrue(kept.exists(), "the wipe followed a link and deleted what it points at")
        assertFalse(recordings.exists())
        outside.deleteRecursively()
    }

    @Test fun a_symlinked_recordings_folder_loses_the_link_not_its_target() = withContainer { container, _ ->
        val outside = Files.createTempDirectory("velum-wipe-target").toFile()
        val kept = File(outside, "keep.wav").apply { writeBytes(ByteArray(8)) }
        val recordings = File(recordingsDirectoryPath())
        recordings.deleteRecursively()
        recordings.parentFile!!.mkdirs()
        Files.createSymbolicLink(recordings.toPath(), outside.toPath())
        assertEquals(DownloadWipeResult.Clean, container.wipeAllData())
        assertTrue(kept.exists(), "the wipe deleted the contents of a symlinked folder")
        assertFalse(Files.exists(recordings.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        outside.deleteRecursively()
    }

    @Test fun a_wipe_that_cannot_stop_downloads_keeps_the_recordings_folder() = withContainer { container, _ ->
        val recordings = File(recordingsDirectoryPath()).apply { mkdirs() }
        val writing = File(recordings, "rec-live.wav").apply { writeBytes(ByteArray(8)) }
        // Cancelling asks for the locale first, so this makes the downloads step throw.
        val broken = DeviceViewModel(container.ble, localeTag = { error("synthetic") })
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData(device = broken))
        assertTrue(writing.exists(), "the folder went while a download may be writing to it")
    }

    @Test fun a_note_is_read_when_it_is_sent() = withContainer { container, _ ->
        container.addRecording("rec-note")
        assertEquals("## Synthetic", assertNotNull(container.noteToShare("rec-note")).summary)
        container.wipeGate.wiping(1.seconds) {
            assertEquals(null, container.noteToShare("rec-note"), "a note left the app during the wipe")
        }
        container.wipeAllData()
        assertEquals(null, container.noteToShare("rec-note"), "an action waiting behind a snackbar sent deleted text")
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
        assertEquals(DownloadWipeResult.Incomplete, container.wipeAllData())
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

    @Test fun a_shared_file_refused_by_a_wipe_is_deleted() = withContainer { container, context ->
        val copy = File(context.cacheDir, "imports/share.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 1 }) }
        val result = container.wipeGate.wiping(1.seconds) { AudioImportPipeline(container).importShared(copy.path) }
        assertIs<ImportResult.Failed>(result)
        assertFalse(copy.exists(), "the shared file's copy survived the wipe")
    }
}
