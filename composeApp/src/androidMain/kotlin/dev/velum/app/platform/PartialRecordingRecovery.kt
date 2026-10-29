package dev.velum.app.platform

import android.util.Log
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.data.VelumRepository
import java.io.File

/**
 * After process death, rewrite incomplete WAV headers and refresh matching
 * `status=recording` rows so the capture is not lost.
 *
 * Download persist temps live under [DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME]
 * with [DownloadedSessionAudioPersist.TEMP_SUFFIX] and are wiped by
 * [cleanupStaleDownloadArtifacts] before this runs.
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

/**
 * Startup cleanup for SoftAP/download crash debris.
 *
 * Must finish before [AppContainer] is published (`ready` / `awaitContainer`) so a live
 * SoftAP download cannot race an unconditional wipe of `.download-tmp`.
 *
 * Deletes:
 * - everything under `.download-tmp/`
 * - legacy `*.ogg.partial`, `*.download.partial`, and non-`rec-` `*.wav.partial` in recordings
 * - orphan `rec-*.wav.partial` with no `status=recording` library row (abandoned download)
 *
 * Never invents a library row or orphan `.wav` from download debris.
 */
suspend fun cleanupStaleDownloadArtifacts(
    recordingsDirPath: String,
    repository: VelumRepository,
): Int {
    val recordings = ModelStoreDir(recordingsDirPath)
    var removed = DownloadedSessionAudioPersist.deleteStaleDownloadTemps(recordings)
    val dir = File(recordingsDirPath)
    if (!dir.isDirectory) return removed
    for (file in dir.listFiles().orEmpty()) {
        if (!file.isFile) continue
        val name = file.name.lowercase()
        if (!name.endsWith(".wav.partial")) continue
        if (DownloadedSessionAudioPersist.isDownloadTempName(name)) continue
        // rec-*.wav.partial without an in-progress mic row is abandoned download debris.
        val id = recordingIdFromWavName(file.name) ?: run {
            if (file.delete()) removed++
            continue
        }
        val row = repository.getRecording(id)
        if (row == null || row.status != "recording") {
            if (file.delete()) removed++
        }
    }
    return removed
}

/**
 * Run [cleanup] to completion, then [publish]. Used by [dev.velum.app.VelumApplication]
 * so stale-download wipe cannot overlap downloads that start after the container is ready.
 */
internal suspend fun <T> cleanupStaleDownloadsThenPublish(
    cleanup: suspend () -> Unit,
    publish: () -> T,
): T {
    cleanup()
    return publish()
}

/** Mic WAVs / `rec-*.wav.partial` only — never download temps. */
internal fun isMicRecoveryCandidate(fileName: String): Boolean {
    val name = fileName.lowercase()
    if (DownloadedSessionAudioPersist.isDownloadTempName(name)) return false
    if (name == DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME) return false
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
