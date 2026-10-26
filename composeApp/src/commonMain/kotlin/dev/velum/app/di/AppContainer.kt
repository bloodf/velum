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
import dev.velum.ai.pipeline.AskAiChat
import dev.velum.ai.pipeline.AutoFlowRunner
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.ai.pipeline.createCoroutineRecordingJobScheduler
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.createMicCapture
import dev.velum.app.ui.DeviceViewModel
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
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureDeviceKeyImporter
import dev.velum.core.security.SecureStore
import dev.velum.core.settings.SettingsRepository
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
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val databaseHandle = createDatabase(driverFactory)
    val database = databaseHandle.database
    val repository = VelumRepository(databaseHandle)
    val settingsRepository = SettingsRepository(secureStore)
    val deviceKeyImporter: DeviceKeyImporter = SecureDeviceKeyImporter(secureStore)
    val aiClient = createOpenAiCompatibleClient()
    val settingsViewModel = SettingsViewModel(settingsRepository, aiClient)
    val deviceViewModel = DeviceViewModel(ble, deviceKeyImporter)
    val integrationAuth = IntegrationAuthStore(secureStore, integrationConfig)

    val localModelManager = createLocalModelManager(storeRoot = modelStoreRoot)
    val voiceProfileStore = VoiceProfileStore(modelStoreRoot)
    val localTranscriber = LocalTranscriber(localModelManager)
    val localDiarizer = LocalDiarizer(localModelManager, voiceProfileStore)
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
    )

    val recordingJobs: RecordingJobScheduler =
        recordingJobSchedulerFactory?.invoke(recordingProcessor, appScope)
            ?: createCoroutineRecordingJobScheduler(recordingProcessor, appScope)

    val askAiChat = AskAiChat(
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
    )

    /** Active phone mic session recording id, if any. */
    @Volatile
    var activeMicRecordingId: String? = null

    init {
        appScope.launch {
            settingsRepository.load()
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

    private fun formatRecordingTitle(epochSeconds: Long): String {
        val day = (epochSeconds / 86_400L)
        val tod = epochSeconds % 86_400L
        val h = tod / 3600
        val m = (tod % 3600) / 60
        val s = tod % 60
        fun two(n: Long) = if (n < 10) "0$n" else "$n"
        return "Recording $day ${two(h)}:${two(m)}:${two(s)}"
    }
}
