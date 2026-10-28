package dev.velum.app.di

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
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.createMicCapture
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.writeBytesToPath
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.app.ui.SettingsViewModel
import dev.velum.ble.BleTransport
import dev.velum.ble.createBleTransport
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.calendar.createCalendarEventProvider
import dev.velum.core.data.DriverFactory
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
    val localTranscriber = LocalTranscriber(localModelManager)
    val localDiarizer = LocalDiarizer(
        localModelManager,
        voiceProfileStore,
        matchThreshold = { settingsRepository.settings.value.speakerMatchThreshold },
    )
    val localLlm = LocalLlmEngine(localModelManager)
    val apiTranscriber = OpenAiTranscriber(aiClient, settingsRepository)
    val apiDiarizer = OpenAiDiarizer()
    val apiLlm = OpenAiLlmEngine(aiClient, settingsRepository)

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
    ) {
        recordingJobs.enqueue(recordingId, templateId, retranscribe)
    }

    suspend fun createLocalRecording(
        title: String? = null,
        source: String = "phone",
        localPath: String? = null,
        status: String = "ready",
    ): String {
        val id = "rec-${epochSecondsNow()}-${(1000..9999).random()}"
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
        val id = "rec-${epochSecondsNow()}-${(1000..9999).random()}"
        val ext = when {
            looksLikeOgg(session.decryptedBytes) -> "ogg"
            looksLikeWav(session.decryptedBytes) -> "wav"
            else -> "bin"
        }
        val path = "${recordingsDirectoryPath()}/$id.$ext"
        writeBytesToPath(path, session.decryptedBytes)
        val started = if (session.startedAtEpochSeconds > 1_000_000_000L) {
            session.startedAtEpochSeconds
        } else {
            epochSecondsNow()
        }
        repository.insertRecording(
            id = id,
            sessionId = session.sessionId,
            folderId = null,
            title = formatRecordingTitle(started),
            source = "note_pro",
            deviceSerial = session.deviceSerial,
            startedAt = started,
            durationSeconds = 0,
            sizeBytes = session.sizeBytes,
            localPath = path,
            transcript = null,
            summaryMarkdown = null,
            status = "queued",
        )
        enqueueProcessing(id, templateId = null, retranscribe = true)
        return id
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
        val locale = settingsRepository.preferences.value.appLanguage
            .takeIf { it != "system" }
            ?: "pt-BR"
        return VelumDateTime.formatFriendlyTitle(epochSeconds, locale)
    }
}
