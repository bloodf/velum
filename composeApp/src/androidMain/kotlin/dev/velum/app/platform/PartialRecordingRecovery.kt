package dev.velum.app.platform

import android.util.Log
import dev.velum.core.data.VelumRepository
import java.io.File

/** Diagnostics contain local paths for an owner-facing recovery UI; logs contain counts only. */
data class PartialMicRecoveryReport(
    val recoveredIds: List<String> = emptyList(),
    val deferred: List<WavRecoveryResult> = emptyList(),
    val retainedPartials: List<String> = emptyList(),
    val skippedActive: Int = 0,
    val skippedCompleted: Int = 0,
    val skippedUnrelated: Int = 0,
)

/** Recover only interrupted owned rows; never select between different files automatically. */
suspend fun recoverPartialMicRecordings(
    recordingsDirPath: String,
    repository: VelumRepository,
    onRecovered: suspend (recordingId: String) -> Unit = {},
    activePath: () -> String? = {
        when (val state = MicRecordingService.state.value) {
            is MicSessionState.Starting -> state.session.absolutePath
            is MicSessionState.Recording -> state.absolutePath
            is MicSessionState.Paused -> state.absolutePath
            else -> null
        }
    },
    protectedRecording: (String) -> Boolean = { false },
): PartialMicRecoveryReport {
    val dir = File(recordingsDirPath)
    if (!dir.isDirectory) return PartialMicRecoveryReport()
    val candidates = dir.listFiles().orEmpty().filter {
        it.name.endsWith(".wav", true) || it.name.endsWith(".wav.partial", true)
    }
    val groups = candidates.mapNotNull { file -> recordingIdFromWavName(file.name)?.let { it to file } }.groupBy({ it.first }, { it.second })
    val recovered = mutableListOf<String>()
    val deferred = mutableListOf<WavRecoveryResult>()
    val retained = candidates.filter { it.name.endsWith(".partial", true) }.map { it.absolutePath }
    var skippedActive = 0
    var skippedCompleted = 0
    var skippedUnrelated = 0
    for ((id, files) in groups) {
        val row = repository.getRecording(id) ?: continue
        // Do not rewrite imported/finished/failed recordings during startup.
        if (row.source != "phone") { skippedUnrelated++; continue }
        if (row.status != "recording") { skippedCompleted++; continue }
        if (protectedRecording(id)) { skippedActive++; continue }
        val current = activePath()?.let { File(it).absoluteFile.toPath().normalize().toString() }
        val outputs = files.map { file ->
            val path = file.absoluteFile.toPath().normalize().toString()
            if (path.endsWith(".partial", true)) path.dropLast(8) else path
        }.distinct()
        if (current != null && (current in outputs || recordingIdFromWavName(File(current).name) == id)) { skippedActive++; continue }
        val partials = files.filter { it.name.endsWith(".partial", true) }
        if (outputs.size != 1 || partials.size > 1) {
            files.forEach { deferred += WavRecoveryResult.Conflict(it.absolutePath, outputs.first()) }
            continue
        }
        // Inspect the partial first. A conflict blocks the entire row, including its final file.
        val candidate = partials.singleOrNull() ?: files.single()
        val outcome = WavWriter.recoverWithReport(candidate.absolutePath, 16_000)
        if (outcome !is WavRecoveryResult.Recovered) { deferred += outcome; continue }
        if (protectedRecording(id) || repository.getRecording(id) != row) { skippedActive++; continue }
        val finished = outcome.finish
        repository.updateLocalPath(id, finished.absolutePath, finished.durationSeconds, finished.sizeBytes)
        repository.updateStatus(id, "queued")
        onRecovered(id)
        recovered += id
    }
    if (deferred.isNotEmpty()) Log.w(TAG, "WAV recovery deferred for ${deferred.size} file group(s); existing files were preserved.")
    return PartialMicRecoveryReport(recovered, deferred, retained, skippedActive, skippedCompleted, skippedUnrelated)
}

internal fun recordingIdFromWavName(fileName: String): String? {
    val withoutPartial = if (fileName.endsWith(".partial", true)) fileName.dropLast(8) else fileName
    val base = if (withoutPartial.endsWith(".wav", true)) withoutPartial.dropLast(4) else return null
    return base.takeIf { it.startsWith("rec-") }
}

private const val TAG = "VelumWavRecover"
