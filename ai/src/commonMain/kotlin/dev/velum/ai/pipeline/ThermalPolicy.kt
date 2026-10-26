package dev.velum.ai.pipeline

/**
 * Common hook so [RecordingProcessor] can emit [dev.velum.ai.engine.JobProgress.PausedThermal]
 * between stages without linking Android PowerManager into commonMain.
 */
interface ThermalPolicy {
    fun isHot(): Boolean
    suspend fun awaitCool(onPaused: suspend (message: String) -> Unit)
}

object NoOpThermalPolicy : ThermalPolicy {
    override fun isHot(): Boolean = false
    override suspend fun awaitCool(onPaused: suspend (message: String) -> Unit) = Unit
}
