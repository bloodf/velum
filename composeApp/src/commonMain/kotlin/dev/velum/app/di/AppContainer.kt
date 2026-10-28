package dev.velum.app.di

import dev.velum.ai.audio.DownloadedSessionAudioPersist
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
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.createMicCapture
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.systemLanguageTag
import dev.velum.app.ui.DeviceViewModel
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
import dev.velum.core.data.createDatabase
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    val databaseHandle = createDatabase(driverFactory)
    val database = databaseHandle.database
    val repository = VelumRepository(databaseHandle)
    val settingsRepository = SettingsRepository(secureStore)
    val deviceKeyImporter: DeviceKeyImporter = SecureDeviceKeyImporter(secureStore)
    val aiClient = createOpenAiCompatibleClient()
    val settingsViewModel = SettingsViewModel(settingsRepository, aiClient)
    val deviceViewModel = DeviceViewModel(
        ble = ble,
        keyImporter = deviceKeyImporter,
        preferWifiFastPath = { settingsRepository.preferences.value.preferWifiFastPath },
        persistDownloadedSession = { session -> persistDownloadedSession(session) },
        localeTag = {
            when (settingsRepository.preferences.value.appLanguage) {
                "en" -> "en"
                "pt-BR" -> "pt-BR"
                else -> systemLanguageTag()
            }
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

    /** Active phone mic session recording id, if any. */
    @Volatile
    var activeMicRecordingId: String? = null

    init {
        appScope.launch {
            runCatching { settingsRepository.load() }
            // Keystore may be unavailable in Robolectric; never crash process start.
            runCatching { voiceProfileStore.ensureKey() }
            autoFlowRunner.start()
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
    ): String {
        val id = RecordingIds.newId()
        val now = epochSecondsNow()
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
        return id
    }

    suspend fun persistDownloadedSession(session: DownloadedSessionAudio): String {
        val id = RecordingIds.newId()
        // Stream to temp + atomic rename — never materialize a full WAV copy of opaque PCM.
        val written = DownloadedSessionAudioPersist.persistToDirectory(
            decryptedBytes = session.decryptedBytes,
            destDirectory = ModelStoreDir(recordingsDirectoryPath()),
            fileBaseName = id,
            sampleRateHz = session.sampleRateHz,
            channels = session.channels,
        )
        val started = if (session.startedAtEpochSeconds > 1_000_000_000L) {
            session.startedAtEpochSeconds
        } else {
            epochSecondsNow()
        }
        val title = formatRecordingTitle(started)
        repository.insertRecording(
            id = id,
            sessionId = session.sessionId,
            folderId = null,
            title = title,
            source = "note_pro",
            deviceSerial = session.deviceSerial,
            startedAt = started,
            durationSeconds = 0,
            sizeBytes = written.sizeBytes,
            localPath = written.absolutePath,
            transcript = null,
            summaryMarkdown = null,
            status = "queued",
        )
        // Resolve AutoFlow before first enqueue so KEEP cannot drop template/model.
        val match = repository.findMatchingAutoflow(
            source = "note_pro",
            durationSeconds = 0,
            title = title,
        )
        enqueueProcessing(
            recordingId = id,
            templateId = match?.template_id,
            retranscribe = true,
            apiModelOverride = match?.let { AutoFlowResolve.modelOverride(it) },
        )
        return id
    }

    private fun formatRecordingTitle(epochSeconds: Long): String {
        val locale = settingsRepository.preferences.value.appLanguage
            .takeIf { it != "system" }
            ?: "pt-BR"
        return VelumDateTime.formatFriendlyTitle(epochSeconds, locale)
    }
}
