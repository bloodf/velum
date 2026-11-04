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
import dev.velum.app.platform.deleteImportCopies
import dev.velum.app.platform.deleteShareExports
import dev.velum.app.platform.releaseFolderGrants
import dev.velum.app.platform.writeMarkdownToExportFolder
import dev.velum.core.integrations.MarkdownNoteExport
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.createMicCapture
import dev.velum.app.platform.DOWNLOAD_PART_SUFFIX
import dev.velum.app.platform.deleteFileAtPath
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class AppContainer(
    val secureStore: SecureStore,
    driverFactory: DriverFactory,
    val ble: BleTransport = createBleTransport(),
    modelStoreRoot: ModelStoreDir = defaultModelStoreRoot(),
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
    )

    val micRecordings = dev.velum.app.platform.MicRecordingController(micCapture) { id ->
        !wipeGate.isWiping && repository.getRecording(id)?.let { it.source == "phone" && it.status == "recording" } == true
    }
    val activeMicRecordingId: String? get() = micRecordings.owner.value?.recordingId

    init {
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

    fun enqueueProcessing(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
        apiModelOverride: ApiModelOverride? = null,
    ) {
        recordingJobs.enqueue(recordingId, templateId, retranscribe, apiModelOverride)
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
    internal suspend fun wipeAllData(device: DeviceViewModel = deviceViewModel): DownloadWipeResult =
        wipeGate.wiping(WIPE_JOIN_TIMEOUT) { workStopped ->
            device.holdingDownloads {
                val downloads = device.wipePartialDownloads()
                deviceKeyImporter.clearKeys()
                stopMicCapture()
                // Only now: if an earlier step fails, recordings stay and still need their queued work.
                recordingJobs.cancelAll()
                repository.wipeAllUserData { path -> deleteFileAtPath(path) }
                withContext(Dispatchers.IO) {
                    voiceProfileStore.wipeAll()
                    clearWatchedFolders()
                    deleteImportCopies()
                    deleteShareExports()
                    ExportFolderStore.clear()
                    releaseFolderGrants()
                }
                settingsRepository.clearAllApiKeys()
                webhookSettings.clear()
                integrationAuth.clearTokens()
                if (workStopped) downloads else DownloadWipeResult.StillRunning
            }
        }

    /** Ends a mic recording in progress and deletes its file, which may not be on a recording row yet. */
    private suspend fun stopMicCapture() = micRecordings.underLock {
        // Under the controller's lock: a start that passed its check before the wipe finishes first, then stops here.
        val session = micCapture.sessions.value.session ?: return@underLock
        if (micCapture.isCapturing) micCapture.stop(session)
        micRecordings.owner.value?.let { micRecordings.release(it) }
        withContext(Dispatchers.IO) { deleteFileAtPath(session.absolutePath) }
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
        // Resolve AutoFlow before first enqueue so KEEP cannot drop template/model.
        val match = repository.findMatchingAutoflow(
            source = "note_pro",
            durationSeconds = 0,
            title = title,
        )
        enqueueProcessing(
            recordingId = id,
            templateId = resolveTemplateForEnqueue(repository, match?.template_id),
            retranscribe = true,
            apiModelOverride = match?.let { AutoFlowResolve.modelOverride(it) },
        )
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


    private fun formatRecordingTitle(epochSeconds: Long): String {
        return recordingTitle(epochSeconds, settingsRepository.preferences.value.appLanguage, systemLanguageTag())
    }
}

/** How long a wipe waits for background work to stop before going ahead and reporting it. */
private val WIPE_JOIN_TIMEOUT = 15.seconds

/** A new recording row was refused because "Wipe all data" is running. */
class WipeInProgressException : IllegalStateException("Wipe all data is in progress")
