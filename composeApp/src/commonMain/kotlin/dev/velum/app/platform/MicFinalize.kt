package dev.velum.app.platform

import dev.velum.app.di.AppContainer

/**
 * Idempotent mic stop + local path update for a phone recording.
 *
 * Safe to call when capture already stopped (notification Stop, prior finalize):
 * recovers WAV header from disk when needed and clears [AppContainer.activeMicRecordingId].
 */
suspend fun finalizeMicRecording(
    container: AppContainer,
    recordingId: String,
    wavPath: String = "${recordingsDirectoryPath()}/$recordingId.wav",
) {
    val ownsSession = container.activeMicRecordingId == recordingId
    if (!ownsSession && !container.micCapture.isCapturing) {
        return
    }
    when (val stopped = container.micCapture.stop()) {
        is MicStopResult.Stopped -> {
            container.repository.updateLocalPath(
                id = recordingId,
                localPath = stopped.absolutePath,
                durationSeconds = stopped.durationSeconds,
                sizeBytes = stopped.sizeBytes,
            )
        }
        else -> {
            // Notification Stop may have already finalized; fix header from disk.
            WavWriter.recoverOrFinalize(wavPath)?.let { recovered ->
                container.repository.updateLocalPath(
                    id = recordingId,
                    localPath = recovered.absolutePath,
                    durationSeconds = recovered.durationSeconds,
                    sizeBytes = recovered.sizeBytes,
                )
            }
        }
    }
    if (container.activeMicRecordingId == recordingId || !container.micCapture.isCapturing) {
        container.activeMicRecordingId = null
    }
}
