package dev.velum.ai.pipeline

import dev.velum.core.data.VelumRepository

/**
 * Process-wide hooks so [RecordingProcessWorker] can reach the app graph without a custom
 * WorkerFactory. Installed from MainActivity after [dev.velum.app.di.AppContainer] is built.
 */
object AiRuntimeHolder {
    @Volatile
    var processor: RecordingProcessor? = null

    @Volatile
    var repository: VelumRepository? = null

    fun install(processor: RecordingProcessor, repository: VelumRepository) {
        this.processor = processor
        this.repository = repository
    }
}
