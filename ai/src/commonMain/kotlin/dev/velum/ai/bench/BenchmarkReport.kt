package dev.velum.ai.bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What was measured: ASR over source audio, or LLM summarization of a fixed transcript. */
enum class BenchTask { Transcription, Summary }

/**
 * One model/workload measurement (#98). [inputDurationMs] is the source audio duration; for
 * [BenchTask.Summary] it is the duration of the audio the fixed transcript stands for.
 * [elapsedMs] is the wall-clock of the engine calls as the app makes them; the runtime builds its recognizer
 * inside each call, so model load is INCLUDED (once per ASR window, once for the summary).
 * [peakRamBytes] is the process peak RSS (VmHWM) since the pre-run reset.
 * [runtime] is the execution engine or runtime identifier (e.g. "local-asr", "local-llm").
 * [timeBoundary] specifies what was included in [elapsedMs].
 * [rtfUnit] specifies the units of [rtf] (ratio of elapsed time to source input duration).
 */
@Serializable
data class BenchResult(
    val task: BenchTask,
    val modelId: String,
    val workload: String,
    val inputDurationMs: Long,
    val elapsedMs: Long,
    val peakRamBytes: Long,
    val outputChars: Int,
    val runtime: String = "local-engine",
    val timeBoundary: String = "wall_clock_engine_call_sum",
    val rtfUnit: String = "ratio_elapsed_over_source_duration",
) {
    /** Real-time factor: elapsed time divided by source audio duration. Serialized into the report. */
    val rtf: Double = rtf(elapsedMs, inputDurationMs)
}

@Serializable
data class BenchEnvironment(
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
    val isEmulator: Boolean,
    val settings: Map<String, String>,
)

@Serializable
data class BenchReport(val environment: BenchEnvironment, val results: List<BenchResult>) {
    fun toJson(): String = JSON.encodeToString(this)

    private companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/** RTF = elapsed / source audio duration. */
fun rtf(elapsedMs: Long, inputDurationMs: Long): Double {
    require(inputDurationMs > 0) { "input duration must be positive" }
    require(elapsedMs >= 0) { "elapsed must not be negative" }
    return elapsedMs.toDouble() / inputDurationMs
}

/** Throws unless the report is complete: a missing measurement must fail the run, not pass it. */
fun BenchReport.requireValid(expectedModels: Set<String>) {
    require(expectedModels.isNotEmpty() && expectedModels.none { it.isBlank() }) { "no benchmark models requested" }
    require(results.isNotEmpty()) { "report has no results" }
    require(!environment.isEmulator) { "model inference is permitted only on physical devices" }
    val missing = expectedModels - results.map { it.modelId }.toSet()
    require(missing.isEmpty()) { "no measurement for models: $missing" }
    results.forEach { r ->
        val who = "${r.modelId}/${r.workload}"
        require(r.workload.isNotBlank()) { "$who: blank workload" }
        require(r.inputDurationMs > 0) { "$who: input duration missing" }
        require(r.elapsedMs > 0) { "$who: elapsed missing" }
        require(r.peakRamBytes > 0) { "$who: peak RAM missing" }
        require(r.outputChars > 0) { "$who: empty output" }
        require(r.runtime.isNotBlank()) { "$who: runtime metadata missing" }
        require(r.timeBoundary.isNotBlank()) { "$who: time boundary missing" }
        require(r.rtfUnit == "ratio_elapsed_over_source_duration") { "$who: invalid rtfUnit '${r.rtfUnit}'" }
    }
    // Another run can only reproduce this one if the exact inputs are identified, not just their paths.
    if (results.any { it.task == BenchTask.Transcription }) {
        requireSha256("audioSha256")
        require(environment.settings["audioFormat"] == "PCM16_MONO_16KHZ") {
            "settings.audioFormat must be PCM16_MONO_16KHZ"
        }
    }
    if (results.any { it.task == BenchTask.Summary }) {
        requireSha256("summaryTranscriptSha256")
        val sourceSec = environment.settings["summaryTranscriptSourceSeconds"]?.toIntOrNull()
        require(sourceSec != null && sourceSec > 0) {
            "settings.summaryTranscriptSourceSeconds must be positive"
        }
    }
    require(environment.settings["fixtureSensitivity"] == "non-sensitive") {
        "settings.fixtureSensitivity must be declared as 'non-sensitive'"
    }
    require(environment.settings["peakRamMethod"] == "proc_self_clear_refs_5_vmhwm") {
        "settings.peakRamMethod must be proc_self_clear_refs_5_vmhwm"
    }
    require(environment.device.isNotBlank() && environment.androidRelease.isNotBlank() && environment.abi.isNotBlank() && environment.sdkInt > 0) {
        "environment fields missing"
    }
}

private fun BenchReport.requireSha256(key: String) {
    val value = environment.settings[key]
    require(value != null && Regex("[0-9a-f]{64}").matches(value)) { "settings.$key must be a lowercase SHA-256 of the fixed input" }
}
