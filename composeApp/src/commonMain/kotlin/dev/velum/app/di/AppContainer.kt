package dev.velum.app.di

import dev.velum.ai.pipeline.resolveTemplateForEnqueue
import dev.velum.ai.createOpenAiCompatibleClient
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.local.LocalDiarizer
import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.createLocalModelManager
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.ai.openai.OpenAiDiarizer
import dev.velum.ai.openai.OpenAiLlmEngine
import dev.velum.ai.openai.OpenAiTranscriber
import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.ai.pipeline.AskAiChat
import dev.velum.ai.pipeline.AutoFlowRunner
import dev.velum.ai.pipeline.NoOpThermalPolicy
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.ai.pipeline.ThermalPolicy
import dev.velum.ai.pipeline.createCoroutineRecordingJobScheduler
import dev.velum.ai.integrations.WebhookDispatcher
import dev.velum.app.devicewalk.DeviceWalkDiarizer
import dev.velum.app.devicewalk.DeviceWalkLlm
import dev.velum.app.devicewalk.DeviceWalkMode
import dev.velum.app.devicewalk.DeviceWalkTranscriber
import dev.velum.app.platform.ExportFolderResult
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.clearWatchedFolders
import dev.velum.app.platform.deleteFileIfPresent
import dev.velum.app.platform.deleteImportCopies
import dev.velum.app.platform.deleteDeviceTransferTemps
import dev.velum.app.platform.deleteRecordingsDirectory
import dev.velum.app.platform.deleteShareExports
import dev.velum.app.platform.releaseFolderGrants
import dev.velum.app.platform.writeMarkdownToExportFolder
import dev.velum.core.integrations.MarkdownNoteExport
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.createMicCapture
import dev.velum.app.platform.DOWNLOAD_PART_SUFFIX
import dev.velum.app.platform.deleteFileAtPath
import dev.velum.app.platform.deleteFileIfPresent
import dev.velum.app.platform.isAppOwnedAudioPath
import dev.velum.app.platform.isSamePath
import dev.velum.app.platform.isSupersededOwnAudio
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.streamToFileAtomically
import dev.velum.app.i18n.recordingTitle
import dev.velum.app.i18n.resolveLanguageTag
import dev.velum.app.platform.systemLanguageTag
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadWipeResult
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.app.ui.SettingsViewModel
import dev.velum.ble.BleTransport
import dev.velum.ble.createBleTransport
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.calendar.createCalendarEventProvider
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.RecordingIds
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.WipeGate
import dev.velum.core.data.createDatabase
import dev.velum.core.db.Recording
import dev.velum.core.data.epochSecondsNow
import dev.velum.core.integrations.IntegrationAuthStore
import dev.velum.core.integrations.IntegrationConfig
import dev.velum.core.integrations.IntegrationRegistry
import dev.velum.core.integrations.WebhookSettingsStore
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureDeviceKeyImporter
import dev.velum.core.security.SecureStore
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.time.VelumDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.velum.ai.pipeline.deleteAllPcmSpill
import dev.velum.ai.pipeline.shareMarkdownBody
import dev.velum.core.settings.SettingsLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AppContainer(
    val secureStore: SecureStore,
    driverFactory: DriverFactory,
    val ble: BleTransport = createBleTransport(),
    private val modelStoreRoot: ModelStoreDir = defaultModelStoreRoot(),
    val integrationConfig: IntegrationConfig = IntegrationConfig.defaults(),
    val calendarProvider: CalendarEventProvider = createCalendarEventProvider(),
    /**
     * Android passes WorkManager; null keeps the in-process coroutine fallback (JVM/desktop/iOS).
     */
    recordingJobSchedulerFactory: ((RecordingProcessor, CoroutineScope) -> RecordingJobScheduler)? = null,
    val micCapture: MicCapture = createMicCapture(),
    thermalPolicy: ThermalPolicy = NoOpThermalPolicy,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Replaces the last wipe step (the database rewrite) in tests, to count it or make it fail. Null in the app. */
    internal var compactDatabaseForTest: (suspend () -> Boolean)? = null

    /** Stops background work that writes user data during "Wipe all data"; see [wipeAllData]. */
    val wipeGate = WipeGate()

    val databaseHandle = createDatabase(driverFactory)
    val database = databaseHandle.database
    // A failed repository init must not abandon an open database (StrictMode LeakedClosableViolation).
    val repository = try {
        VelumRepository(databaseHandle)
    } catch (t: Throwable) {
        runCatching { databaseHandle.driver.close() }
        throw t
    }
    /**
     * In-memory FirstRun gate. Initial value is read once on the container
     * construction path (Android: [Dispatchers.IO] in VelumApplication) — never
     * from composition. [markFirstRunDone] clears the flow synchronously so
     * activity recreate cannot re-show FirstRun before the DB write lands (#106).
     */
    private val _firstRunPending = MutableStateFlow(
        runBlocking { !repository.isFirstRunDone() },
    )
    val firstRunPending: StateFlow<Boolean> = _firstRunPending.asStateFlow()

    /**
     * Runs [block] in the app's own scope instead of the caller's, so work started from a screen finishes when that screen
     * is recreated (rotation, theme or locale change). The caller may stop waiting; the work goes on.
     */
    fun <T> runDetached(block: suspend () -> T): kotlinx.coroutines.Deferred<T> = appScope.async { block() }

    /**
     * The share import currently running or just finished and not yet reported to the user. Held here, not by the screen,
     * because the screen's collector is cancelled at every ON_STOP and a result nobody saw would otherwise vanish.
     * Read and written on the main thread only (from the Compose effect).
     */
    var pendingShare: kotlinx.coroutines.Deferred<dev.velum.app.importing.ImportResult>? = null

    /** True when [pendingShare] is a share found pending after a restart: reported with a snackbar, never by navigating. */
    var pendingShareRecovered: Boolean = false

    /** One share import at a time across every instance of the activity (the pipeline is recreated per composition). */
    val shareImportMutex = Mutex()

    /** Dismiss FirstRun in memory immediately; persist on [appScope]. */
    fun markFirstRunDone() {
        _firstRunPending.value = false
        appScope.launch {
            repository.markFirstRunDone()
        }
    }

    val settingsRepository = SettingsRepository(secureStore)
    val deviceKeyImporter: DeviceKeyImporter = SecureDeviceKeyImporter(secureStore)
    val aiClient = createOpenAiCompatibleClient()
    val settingsViewModel = SettingsViewModel(settingsRepository, aiClient)
    val deviceViewModel = DeviceViewModel(
        ble = ble,
        keyImporter = deviceKeyImporter,
        preferWifiFastPath = { settingsRepository.preferences.value.preferWifiFastPath },
        persistDownloadedSession = { session -> persistDownloadedSession(session) },
        librarySessionIds = { ids, serial -> repository.librarySessionIds(ids, serial) },
        localeTag = {
            resolveLanguageTag(settingsRepository.preferences.value.appLanguage, systemLanguageTag())
        },
    )
    val integrationAuth = IntegrationAuthStore(secureStore, integrationConfig)
    val webhookSettings = WebhookSettingsStore(secureStore)
    val integrationRegistry = IntegrationRegistry(
        webhook = webhookSettings,
        exportFolderConfigured = { !ExportFolderStore.currentUri().isNullOrBlank() },
        googleCalendarClientConfigured = integrationConfig.googleCalendarReady(),
    )
    val webhookDispatcher = WebhookDispatcher(
        repository = repository,
        settings = webhookSettings,
        wipeGate = wipeGate,
    )

    val localModelManager = createLocalModelManager(storeRoot = modelStoreRoot)
    val voiceProfileStore = VoiceProfileStore(modelStoreRoot, secureStore = secureStore)
    private val deviceWalk = DeviceWalkMode.isEnabled()
    val localTranscriber =
        if (deviceWalk) DeviceWalkTranscriber else LocalTranscriber(localModelManager)
    val localDiarizer =
        if (deviceWalk) {
            DeviceWalkDiarizer
        } else {
            LocalDiarizer(
                localModelManager,
                voiceProfileStore,
                matchThreshold = { settingsRepository.settings.value.speakerMatchThreshold },
            )
        }
    val localLlm = if (deviceWalk) DeviceWalkLlm else LocalLlmEngine(localModelManager)
    val apiTranscriber =
        if (deviceWalk) DeviceWalkTranscriber else OpenAiTranscriber(aiClient, settingsRepository)
    val apiDiarizer = if (deviceWalk) DeviceWalkDiarizer else OpenAiDiarizer()
    val apiLlm = if (deviceWalk) DeviceWalkLlm else OpenAiLlmEngine(aiClient, settingsRepository)

    val aiRouter = AiTaskRouter(
        settings = settingsRepository.settings,
        localTranscriber = localTranscriber,
        apiTranscriber = apiTranscriber,
        localDiarizer = localDiarizer,
        apiDiarizer = apiDiarizer,
        localLlm = localLlm,
        apiLlm = apiLlm,
    )

    val recordingProcessor = RecordingProcessor(
        aiRouter = aiRouter,
        repository = repository,
        settings = settingsRepository,
        thermal = thermalPolicy,
        wipeGate = wipeGate,
        pcmSpillRoot = modelStoreRoot,
        voiceProfiles = voiceProfileStore,
    )

    val recordingJobs: RecordingJobScheduler =
        recordingJobSchedulerFactory?.invoke(recordingProcessor, appScope)
            ?: createCoroutineRecordingJobScheduler(recordingProcessor, appScope)

    val askAiChat = AskAiChat(
        aiRouter = aiRouter,
        repository = repository,
        settings = settingsRepository,
    )
    val askAcross = AskAcrossRecordings(
        aiRouter = aiRouter,
        repository = repository,
        settings = settingsRepository,
    )

    val autoFlowRunner = AutoFlowRunner(
        repository = repository,
        processor = recordingProcessor,
        scope = appScope,
        notifyEnabled = { settingsRepository.preferences.value.autoflowNotify },
        calendar = calendarProvider,
        jobScheduler = recordingJobs,
        wipeGate = wipeGate,
    )

    val micRecordings = dev.velum.app.platform.MicRecordingController(micCapture) { id ->
        !wipeGate.isWiping && repository.getRecording(id)?.let { it.source == "phone" && it.status == "recording" } == true
    }
    val activeMicRecordingId: String? get() = micRecordings.owner.value?.recordingId

    init {
        // Earlier versions also kept every speaker embedding in plaintext, and a recording deleted while its
        // diarization ran can leave its embeddings behind (#320).
        appScope.launch(Dispatchers.IO) {
            // Each step on its own, so one failure does not skip the others.
            runCatching { voiceProfileStore.sweepTemporaryFiles() }
                .onFailure { println("VelumStartup: voice temp sweep failed: ${it::class.simpleName}") }
            runCatching { voiceProfileStore.purgePlaintextClusterCache() }
                .onFailure { println("VelumStartup: voice cache purge failed: ${it::class.simpleName}") }
            runCatching {
                // Listed before reading the recordings: a recording is stored before its processing caches anything.
                val cached = voiceProfileStore.cachedRecordingIds()
                val live = repository.recordingIds()
                (cached - live).forEach { id -> runCatching { voiceProfileStore.deleteClusterCache(id) } }
            }.onFailure { println("VelumStartup: voice cache orphan sweep failed: ${it::class.simpleName}") }
            // Under the wipe gate: a wipe cancels it, so it can't recreate a profile after the wipe.
            runCatching { wipeGate.guard { voiceProfileStore.migrateLegacyPlaintextProfiles() } }
                .onFailure { println("VelumStartup: voice profile migration failed: ${it::class.simpleName}") }
        }
        // Template catalogs lazily read packaged assets; warm them off the main thread so the
        // composition-time lookups (template names, AutoFlow library) never hit the disk (StrictMode).
        appScope.launch(Dispatchers.IO) {
            runCatching { dev.velum.core.templates.BuiltinTemplateCatalog.All }
                .onFailure { println("VelumStartup: builtin template warm-up failed: ${it::class.simpleName}") }
            runCatching { dev.velum.core.autoflow.AutoFlowTemplateCatalog.All }
                .onFailure { println("VelumStartup: AutoFlow template warm-up failed: ${it::class.simpleName}") }
        }
        appScope.launch {
            settingsRepository.loadState.collect { state ->
                when (state) {
                    dev.velum.core.settings.SettingsLoadState.Ready -> autoFlowRunner.start()
                    dev.velum.core.settings.SettingsLoadState.Unavailable -> autoFlowRunner.stop()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Deletes a recording for good. False when a step failed: the recording stays, though its audio may already be
     * gone, and trying again finishes the job. Not cancellable, so leaving the screen can't stop it halfway. It waits
     * for a running device save, so it sees the file that save writes. A running or queued download of this recording's
     * session is cancelled first, and that session's partial is removed, since the audio could be recovered from it.
     */
    suspend fun deleteRecordingForever(id: String, device: DeviceViewModel = deviceViewModel): Boolean = try {
        withContext(NonCancellable + Dispatchers.IO) {
            // Before the row goes: a cancelled download can't save, and a running one would find no row and add it back (#407).
            val row = repository.getRecording(id)
            row?.session_id?.let(device::cancelDownloadOf)
            downloadPersistMutex.withLock {
                // First: if it stays, the recording stays too, and trying again finishes the job.
                if (row?.session_id?.let { device.deletePartialOf(it, row.device_serial) } == false) {
                    println("VelumDelete: a recording's partial download could not be deleted")
                    return@withLock false
                }
                val audio = repository.getRecording(id)?.local_path
                val others = repository.getAllRecordingFileRefs().filter { it.first != id }.mapNotNull { it.second }
                // Copies first: if one fails, the recording still has its audio.
                val deleted = (repository.importCopies(id) + listOfNotNull(audio))
                    .filter { path -> isAppOwnedAudioPath(path) && others.none { it == path || isSamePath(it, path) } }
                    .all { deleteFileIfPresent(it) }
                if (deleted) {
                    repository.deleteRecordingHard(id)
                    // The row is gone, so this can't fail the delete. Startup removes caches with no recording.
                    runCatching { voiceProfileStore.deleteClusterCache(id) }
                        .onFailure { println("VelumDelete: cluster cache delete failed: ${it::class.simpleName}") }
                } else {
                    println("VelumDelete: a recording's audio could not be deleted")
                }
                deleted
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        println("VelumDelete: deleting a recording failed: ${e::class.simpleName}")
        false
    }

    fun enqueueProcessing(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
        apiModelOverride: ApiModelOverride? = null,
    ) {
        recordingJobs.enqueue(recordingId, templateId, retranscribe, apiModelOverride)
    }

    /**
     * Enqueues the job for a row already saved as `queued`. If that fails or is cancelled the row would wait for a
     * job that does not exist, so it is marked failed (Retry shows) before the error continues (#415).
     */
    suspend fun enqueueSavedRecording(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
        apiModelOverride: ApiModelOverride? = null,
    ) {
        try {
            enqueueProcessing(recordingId, templateId, retranscribe, apiModelOverride)
        } catch (t: Throwable) {
            markQueuedFailed(recordingId)
            throw t
        }
    }

    /**
     * [enqueueSavedRecording] for a row whose AutoFlow template and model come from the matching flow. The match and
     * the template lookup are inside the same guard, so a cancel there cannot strand the row either. With
     * [markFailedOnCancel] false a cancellation leaves the row queued (startup recovery: the next start retries it).
     */
    suspend fun enqueueSavedWithAutoflow(
        recordingId: String,
        source: String,
        durationSeconds: Long,
        title: String,
        markFailedOnCancel: Boolean = true,
    ) {
        try {
            val match = repository.findMatchingAutoflow(source = source, durationSeconds = durationSeconds, title = title)
            enqueueProcessing(
                recordingId = recordingId,
                templateId = resolveTemplateForEnqueue(repository, match?.template_id),
                retranscribe = true,
                apiModelOverride = match?.let { AutoFlowResolve.modelOverride(it) },
            )
        } catch (t: Throwable) {
            if (markFailedOnCancel || t !is kotlinx.coroutines.CancellationException) markQueuedFailed(recordingId)
            throw t
        }
    }

    private suspend fun markQueuedFailed(recordingId: String) {
        withContext(NonCancellable) { runCatching { repository.updateStatusIfEquals(recordingId, "queued", "failed") } }
    }

    /**
     * Startup: a row left `queued` with no job (the process died, or enqueueing failed, after the row was saved) gets
     * its job again, with the template and model of the AutoFlow that matches it. A row that already has work is left
     * alone, so a job still held (WorkManager) or running (in-process) is never replaced. Waits for the settings to
     * load first: a job started on unloaded defaults would pick the wrong model and language. Under the wipe gate: a
     * wipe in progress recreates no work. A template picked by hand for a lost job cannot be recovered. Returns how
     * many rows were handed to the scheduler.
     */
    suspend fun requeueStrandedRecordings(): Int {
        settingsRepository.loadState.first {
            it == SettingsLoadState.Ready || it == SettingsLoadState.Unavailable
        }
        return wipeGate.guard {
            var handed = 0
            repository.queuedRecordingIds().forEach { id ->
                val row = repository.getRecording(id)?.takeIf { it.status == "queued" } ?: return@forEach
                if (recordingJobs.hasActiveWork(id)) return@forEach
                try {
                    enqueueSavedWithAutoflow(id, row.source, row.duration_seconds, row.title, markFailedOnCancel = false)
                    handed++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("VelumStartup: requeue of a queued recording failed: ${e::class.simpleName}")
                }
            }
            handed
        } ?: 0
    }

    suspend fun createLocalRecording(
        title: String? = null,
        source: String = "phone",
        localPath: String? = null,
        status: String = "ready",
        id: String = RecordingIds.newId(),
    ): String {
        val now = epochSecondsNow()
        // Under the gate: a wipe either refuses the row or waits for it, so it can't outlive the wipe.
        wipeGate.guard {
            repository.insertRecording(
                id = id,
                sessionId = null,
                folderId = null,
                title = title ?: formatRecordingTitle(now),
                source = source,
                deviceSerial = null,
                startedAt = now,
                durationSeconds = 0,
                sizeBytes = 0,
                localPath = localPath,
                transcript = null,
                summaryMarkdown = null,
                status = status,
            )
        } ?: throw WipeInProgressException()
        return id
    }

    /** Serializes persist so check-then-write on the (session, serial) key cannot interleave (#113). */
    private val downloadPersistMutex = Mutex()

    /**
     * "Wipe all data". Work that writes user data (downloads, processing, imports, exports, webhooks, the mic)
     * is stopped first and can't start again until the wipe ends, including when a step fails. The device keys
     * go as soon as the downloads stop, so a step that fails later can't leave them for a download to use.
     */
    internal suspend fun wipeAllData(
        device: DeviceViewModel = deviceViewModel,
        joinTimeout: Duration = WIPE_JOIN_TIMEOUT,
    ): DownloadWipeResult =
        wipeGate.wiping(joinTimeout) { workStopped ->
            device.holdingDownloads {
                var complete = true
                // Every step runs even if an earlier one failed: a locked keystore must not leave the rest behind.
                suspend fun step(name: String, action: suspend () -> Boolean) {
                    val done = try {
                        action()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        println("VelumWipe: $name failed: ${e::class.simpleName}")
                        false
                    }
                    if (!done) complete = false
                }
                // Until proven otherwise: if this step throws, a download may still be writing.
                var downloads = DownloadWipeResult.StillRunning
                step("downloads") { downloads = device.wipePartialDownloads(); true }
                val nothingWriting = workStopped && downloads != DownloadWipeResult.StillRunning
                // Before any data goes: if the process dies mid-wipe, no pending share copy may be offered again.
                step("pending shares") { withContext(Dispatchers.IO) { dev.velum.app.platform.clearPendingShares() }; true }
                step("device keys") { deviceKeyImporter.clearKeys(); true }
                step("mic") { stopMicCapture() }
                // Before the rows: a worker WorkManager starts in between is refused, and consumes its work.
                step("queued jobs") { recordingJobs.cancelAll(); true }
                step("recordings") {
                    val left = mutableSetOf<String>()
                    // Throws at its very end if the search index could not be cleared; step() turns that into Incomplete.
                    repository.wipeAllUserData { path -> if (deleteFileIfPresent(path)) left -= path else left += path }
                    left.isEmpty()
                }
                step("voice data") { withContext(Dispatchers.IO) { voiceProfileStore.wipeAll() } }
                step("watched folders") { withContext(Dispatchers.IO) { clearWatchedFolders() }; true }
                step("export folder") { withContext(Dispatchers.IO) { ExportFolderStore.clear() }; true }
                step("folder grants") { withContext(Dispatchers.IO) { releaseFolderGrants() } }
                step("import copies") { withContext(Dispatchers.IO) { deleteImportCopies() } }
                step("share exports") { withContext(Dispatchers.IO) { deleteShareExports() } }
                // A job or download that has not stopped may own these; StillRunning reports that case.
                if (nothingWriting) {
                    step("decoded audio") {
                        withContext(Dispatchers.IO) {
                            val current = deleteAllPcmSpill(recordingProcessor.pcmSpillRoot)
                            // Earlier versions spilled under the default root.
                            deleteAllPcmSpill(defaultModelStoreRoot()) && current
                        }
                    }
                    step("recordings folder") { withContext(Dispatchers.IO) { deleteRecordingsDirectory() } }
                    step("transfer temps") { withContext(Dispatchers.IO) { deleteDeviceTransferTemps() } }
                }
                step("api keys") {
                    // Settings may not have loaded (locked keystore). Try once so the in-memory state is right;
                    // clearing is by key prefix and does not depend on it, so a failed load is not a failed step.
                    if (settingsRepository.loadState.value != SettingsLoadState.Ready) {
                        try {
                            settingsRepository.load()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Still locked: the removal below works without it.
                        }
                    }
                    settingsRepository.clearAllApiKeys()
                    true
                }
                step("webhook") { webhookSettings.clear(); true }
                step("integrations") { integrationAuth.clearTokens(); true }
                // Last: every step above that deletes rows has run, and VACUUM rewrites the file without them.
                step("database") { (compactDatabaseForTest ?: repository::compactAfterWipe)() }
                when {
                    !complete -> DownloadWipeResult.Incomplete
                    !nothingWriting -> DownloadWipeResult.StillRunning
                    else -> downloads
                }
            }
        }

    /** The note to hand to another app for [recordingId], read now: null if a wipe is running or the recording is gone. */
    suspend fun noteToShare(recordingId: String): SharedNote? = wipeGate.guard {
        repository.getRecording(recordingId)?.let { row ->
            SharedNote(row.title, row.summary_markdown.orEmpty(), shareMarkdownBody(row.title, row.summary_markdown, row.transcript))
        }
    }

    /** Ends a mic recording in progress and deletes its file, which may not be on a recording row yet. */
    private suspend fun stopMicCapture(): Boolean = micRecordings.underLock {
        // Under the controller's lock: a start that passed its check before the wipe finishes first, then stops here.
        val session = micCapture.sessions.value.session ?: return@underLock true
        if (micCapture.isCapturing) micCapture.stop(session)
        micRecordings.owner.value?.let { micRecordings.release(it) }
        withContext(Dispatchers.IO) { deleteFileIfPresent(session.absolutePath) }
    }

    /** AutoFlow's export to folder. Null when the recording is gone; a wipe stops or refuses it. */
    suspend fun exportRecordingToFolder(
        recordingId: String,
        write: (filename: String, markdown: String) -> ExportFolderResult = ::writeMarkdownToExportFolder,
    ): ExportFolderResult? = wipeGate.guard {
        val rec = repository.getRecording(recordingId) ?: return@guard null
        val speakers = repository.getSpeakers(recordingId).mapNotNull { it.display_name ?: it.label }
        val note = MarkdownNoteExport.Note(
            title = rec.title,
            startedAtEpochSeconds = rec.started_at,
            durationSeconds = rec.duration_seconds,
            source = rec.source,
            speakers = speakers,
            tags = listOf("velum"),
            summaryMarkdown = rec.summary_markdown,
            transcriptMarkdown = rec.transcript,
        )
        val markdown = MarkdownNoteExport.render(note)
        withContext(Dispatchers.IO) { write(MarkdownNoteExport.filename(rec.title, rec.started_at), markdown) }
    }

    /**
     * One library row per device session (#113). A re-download atomically replaces the existing
     * row's audio and keeps its id, title, transcript, summary and template; a failed write leaves
     * the existing row and file untouched.
     */
    suspend fun persistDownloadedSession(
        session: DownloadedSessionAudio,
    ): String = downloadPersistMutex.withLock {
        // Without a real serial dedup is off (null key): never risk merging sessions of two devices.
        val serial = session.deviceSerial?.takeIf { it.isNotBlank() }
        val existing = repository.findDeviceSessionRecording(session.sessionId, serial)
        val id = existing?.id ?: RecordingIds.newId()
        val dir = recordingsDirectoryPath()
        // Stream into a fsynced .part and atomically rename once complete (#86), so a crash,
        // cancellation or full disk never leaves a truncated recording under its final name.
        // A cancellation or failure after the rename but before the row exists would leave a decrypted file under
        // its final name that no row points to, so a wipe (which deletes by row) would not remove it.
        var finalPath: String? = null
        try {
            val (path, sizeBytes) = withContext(Dispatchers.IO) {
                streamToFileAtomically(
                    partPath = "$dir/$id$DOWNLOAD_PART_SUFFIX",
                    headBytes = 12,
                    finalPathFor = { head ->
                        val ext = when {
                            looksLikeOgg(head) -> "ogg"
                            looksLikeWav(head) -> "wav"
                            else -> "bin"
                        }
                        "$dir/$id.$ext"
                    },
                ) { sink -> session.writeDecryptedAudio(sink) }.also { finalPath = it.first }
            }
            if (existing != null) {
                repository.replaceDeviceSessionAudio(id, path, sizeBytes, session.processingWarning)
                // Only this row's own superseded file in the recordings dir (e.g. .ogg -> .wav).
                val previousPath = existing.local_path
                if (previousPath != null && isSupersededOwnAudio(previousPath, path, dir, id)) deleteFileAtPath(previousPath)
                // Re-process only a row that never produced a transcript; never overwrite user content.
                if (existing.transcript.isNullOrBlank() && existing.summary_markdown.isNullOrBlank()) {
                    repository.updateStatus(id, "queued")
                    enqueueDownloadedSession(id, existing.title)
                }
                return@withLock id
            }
            val started = if (session.startedAtEpochSeconds > 1_000_000_000L) {
                session.startedAtEpochSeconds
            } else {
                epochSecondsNow()
            }
            val title = formatRecordingTitle(started)
            repository.insertDeviceSessionRecording(
                Recording(
                    id = id,
                    session_id = session.sessionId,
                    folder_id = null,
                    title = title,
                    source = "note_pro",
                    device_serial = serial,
                    started_at = started,
                    duration_seconds = 0,
                    size_bytes = sizeBytes,
                    local_path = path,
                    transcript = null,
                    summary_markdown = null,
                    status = "queued",
                    trashed = 0,
                    created_at = epochSecondsNow(),
                    processing_warning = session.processingWarning,
                ),
            )
            enqueueDownloadedSession(id, title)
            id
        } catch (t: Throwable) {
            val orphan = finalPath
            if (orphan != null && existing?.local_path?.let { isSamePath(it, orphan) } != true) {
                // Ask the database, not a flag: a cancel can land right after the row commits. If the check
                // itself fails the file is kept (unknown is not orphaned), and the original [t] is rethrown.
                runCatching {
                    withContext(NonCancellable) {
                        val rowPath = repository.getRecording(id)?.local_path
                        if (rowPath == null || !isSamePath(rowPath, orphan)) deleteFileAtPath(orphan)
                    }
                }
            }
            throw t
        }
    }

    private suspend fun enqueueDownloadedSession(id: String, title: String) {
        // AutoFlow is resolved before the first enqueue so KEEP cannot drop template/model.
        enqueueSavedWithAutoflow(id, source = "note_pro", durationSeconds = 0, title = title)
    }

    private fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    private fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()


    internal fun formatRecordingTitle(epochSeconds: Long): String {
        return recordingTitle(epochSeconds, settingsRepository.preferences.value.appLanguage, systemLanguageTag())
    }
}

/** How long a wipe waits for background work to stop before going ahead and reporting it. */
private val WIPE_JOIN_TIMEOUT = 15.seconds

/** A new recording row was refused because "Wipe all data" is running. */
class WipeInProgressException : IllegalStateException("Wipe all data is in progress")

/** What AutoFlow hands to another app, read when it is sent. */
data class SharedNote(val title: String, val summary: String, val markdown: String)
