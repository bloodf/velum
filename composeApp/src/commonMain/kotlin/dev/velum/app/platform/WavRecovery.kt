package dev.velum.app.platform

/** Recovery never overwrites a different final file or deletes the original partial. */
sealed interface WavRecoveryResult {
    val sourcePath: String
    data class Recovered(
        override val sourcePath: String,
        val finish: WavFinish,
        val partialPreserved: Boolean = false,
        val reusedFinal: Boolean = false,
    ) : WavRecoveryResult
    /** Also covers an uncertain/incomplete new destination; neither side is assumed disposable. */
    data class Conflict(override val sourcePath: String, val finalPath: String) : WavRecoveryResult
    data class Invalid(override val sourcePath: String) : WavRecoveryResult
    data class Missing(override val sourcePath: String) : WavRecoveryResult
    data class Changed(override val sourcePath: String) : WavRecoveryResult
    data class Unavailable(override val sourcePath: String, val operation: WavRecoveryOperation) : WavRecoveryResult
    data class Unsupported(override val sourcePath: String) : WavRecoveryResult
}

enum class WavRecoveryOperation { Read, RepairHeader, Copy, VerifyCopy, Publish }

internal fun WavRecoveryResult.finishedOrNull(): WavFinish? = (this as? WavRecoveryResult.Recovered)?.finish
internal expect fun recoverDetailedWav(absolutePath: String, sampleRateHz: Int): WavRecoveryResult
