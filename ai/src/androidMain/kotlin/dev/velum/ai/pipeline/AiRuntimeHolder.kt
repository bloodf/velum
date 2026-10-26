package dev.velum.ai.pipeline

import dev.velum.core.data.VelumRepository
import dev.velum.core.settings.SettingsRepository

/**
 * Process-wide hooks so [RecordingProcessWorker] can reach the app graph without a custom
 * WorkerFactory. Installed from Application.onCreate after AppContainer is built.
 */
object AiRuntimeHolder {
    @Volatile
    var processor: RecordingProcessor? = null

    @Volatile
    var repository: VelumRepository? = null

    @Volatile
    var settings: SettingsRepository? = null

    fun install(
        processor: RecordingProcessor,
        repository: VelumRepository,
        settings: SettingsRepository? = null,
    ) {
        this.processor = processor
        this.repository = repository
        this.settings = settings
    }
}
