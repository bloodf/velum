package dev.velum.app.platform

import android.util.Log
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.core.data.VelumRepository
import java.io.File

/**
 * After process death, rewrite incomplete WAV headers and refresh matching
 * `status=recording` rows so the capture is not lost.
 *
 * Download persist temps use [DownloadedSessionAudioPersist.TEMP_SUFFIX] and are
 * never candidates here — see [DownloadedSessionAudioPersist.deleteStaleDownloadTemps].
 */
suspend fun recoverPartialMicRecordings(
    recordingsDirPath: String,
    repository: VelumRepository,
    onRecovered: (recordingId: String) -> Unit = {},
) {
    val dir = File(recordingsDirPath)
    if (!dir.isDirectory) return

    val candidates = dir.listFiles().orEmpty().filter { file ->
        isMicRecoveryCandidate(file.name)
    }
    for (file in candidates) {
        val finished = runCatching {
            WavWriter.recoverOrFinalize(file.absolutePath, sampleRateHz = 16_000)
        }.onFailure { t ->
            Log.e(TAG, "recover failed for ${file.name}", t)
        }.getOrNull() ?: continue

        val id = recordingIdFromWavName(File(finished.absolutePath).name) ?: continue
        val row = repository.getRecording(id) ?: continue
        if (row.status != "recording") {
            // Still fix header on disk; only rewrite DB for interrupted live sessions.
            continue
        }
        repository.updateLocalPath(
            id = id,
            localPath = finished.absolutePath,
            durationSeconds = finished.durationSeconds,
            sizeBytes = finished.sizeBytes,
        )
        // Queue transcription — audio survived process death; do not leave an empty transcript.
        repository.updateStatus(id, "queued")
        onRecovered(id)
        Log.i(TAG, "Recovered interrupted mic recording $id (${finished.sizeBytes} bytes)")
    }
}

/** Mic WAVs / `.wav.partial` only — never download `.download.partial` or `.ogg.partial`. */
internal fun isMicRecoveryCandidate(fileName: String): Boolean {
    val name = fileName.lowercase()
    if (DownloadedSessionAudioPersist.isDownloadTempName(name)) return false
    return name.endsWith(".wav") || name.endsWith(".wav.partial")
}

internal fun recordingIdFromWavName(fileName: String): String? {
    val base = fileName
        .removeSuffix(".partial")
        .removeSuffix(".PARTIAL")
        .removeSuffix(".wav")
        .removeSuffix(".WAV")
    return base.takeIf { it.startsWith("rec-") }
}

private const val TAG = "VelumWavRecover"
