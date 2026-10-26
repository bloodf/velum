package dev.velum.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ShareTextHolder.context = applicationContext
        installCalendarContext(applicationContext)
        installMicCaptureContext(applicationContext)
        installRecordingFilesDir(applicationContext.filesDir.absolutePath)
        val modelsDir = File(applicationContext.filesDir, "models")
        val container = AppContainer(
            secureStore = AndroidSecureStore(applicationContext),
            driverFactory = DriverFactory(applicationContext),
            modelStoreRoot = ModelStoreDir(modelsDir.absolutePath),
            integrationConfig = IntegrationConfig.fromEnv { key -> System.getenv(key) },
            recordingJobSchedulerFactory = { _, _ ->
                createWorkManagerRecordingJobScheduler(applicationContext)
            },
        )
        AiRuntimeHolder.install(container.recordingProcessor, container.repository)
        setContent {
            App(container)
        }
    }
}
