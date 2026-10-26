package dev.velum.app

import android.app.Application
import android.os.PowerManager
import android.util.Log
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.ThermalMonitor
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.MicRecordingService
import dev.velum.app.platform.ShareTextHolder
import dev.velum.app.platform.installMicCaptureContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.recoverPartialMicRecordings
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.core.calendar.installCalendarContext
import dev.velum.core.data.DriverFactory
import dev.velum.core.integrations.IntegrationConfig
import dev.velum.core.security.AndroidSecureStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped application graph. Built once in [onCreate] so WorkManager workers
 * and Activities share the same [AppContainer] / [AiRuntimeHolder] hooks.
 *
 * Microphone capture runs in [MicRecordingService] (FOREGROUND_SERVICE_TYPE_MICROPHONE).
 */
class VelumApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ShareTextHolder.context = applicationContext
        installCalendarContext(applicationContext)
        installMicCaptureContext(applicationContext)
        installRecordingFilesDir(filesDir.absolutePath)
        MicRecordingService.ensureNotificationChannel(this)
        val modelsDir = File(filesDir, "models")
        val powerManager = getSystemService(PowerManager::class.java)
        val built = AppContainer(
            secureStore = AndroidSecureStore(applicationContext),
            driverFactory = DriverFactory(applicationContext),
            modelStoreRoot = ModelStoreDir(modelsDir.absolutePath),
            integrationConfig = IntegrationConfig.fromEnv { key -> System.getenv(key) },
            recordingJobSchedulerFactory = { _, _ ->
                createWorkManagerRecordingJobScheduler(applicationContext)
            },
            thermalPolicy = ThermalMonitor.asPolicy(powerManager),
        )
        container = built
        AiRuntimeHolder.install(
            processor = built.recordingProcessor,
            repository = built.repository,
            settings = built.settingsRepository,
        )
        appScope.launch {
            runCatching {
                recoverPartialMicRecordings(
                    recordingsDirPath = recordingsDirectoryPath(),
                    repository = built.repository,
                )
            }.onFailure { t ->
                Log.e(TAG, "Partial mic recovery failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "VelumApp"

        @Volatile
        lateinit var container: AppContainer
            private set
    }
}
