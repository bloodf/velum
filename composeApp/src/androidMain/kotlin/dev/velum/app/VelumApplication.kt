package dev.velum.app

import android.app.Application
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.ShareTextHolder
import dev.velum.app.platform.installMicCaptureContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.core.calendar.installCalendarContext
import dev.velum.core.data.DriverFactory
import dev.velum.core.integrations.IntegrationConfig
import dev.velum.core.security.AndroidSecureStore
import java.io.File

/**
 * Process-scoped application graph. Built once in [onCreate] so WorkManager workers
 * and Activities share the same [AppContainer] / [AiRuntimeHolder] hooks.
 *
 * Foreground-service recording is deferred (FOREGROUND_SERVICE + notification chrome).
 */
class VelumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShareTextHolder.context = applicationContext
        installCalendarContext(applicationContext)
        installMicCaptureContext(applicationContext)
        installRecordingFilesDir(filesDir.absolutePath)
        val modelsDir = File(filesDir, "models")
        val built = AppContainer(
            secureStore = AndroidSecureStore(applicationContext),
            driverFactory = DriverFactory(applicationContext),
            modelStoreRoot = ModelStoreDir(modelsDir.absolutePath),
            integrationConfig = IntegrationConfig.fromEnv { key -> System.getenv(key) },
            recordingJobSchedulerFactory = { _, _ ->
                createWorkManagerRecordingJobScheduler(applicationContext)
            },
        )
        container = built
        AiRuntimeHolder.install(built.recordingProcessor, built.repository)
    }

    companion object {
        @Volatile
        lateinit var container: AppContainer
            private set
    }
}
