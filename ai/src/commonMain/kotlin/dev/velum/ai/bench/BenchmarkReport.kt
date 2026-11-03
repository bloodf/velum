package dev.velum.ai.bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What was measured: ASR over source audio, or LLM summarization of a fixed transcript. */
enum class BenchTask { Transcription, Summary }

/**
 * One model/workload measurement (#98). [inputDurationMs] is the source audio duration; for
 * [BenchTask.Summary] it is the duration of the audio the fixed transcript stands for.
 * [elapsedMs] covers inference only (model load excluded); [peakRamBytes] is the process peak RSS (VmHWM).
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
) {
    /** Real-time factor: elapsed time divided by source audio duration. */
    val rtf: Double get() = rtf(elapsedMs, inputDurationMs)
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
    val missing = expectedModels - results.map { it.modelId }.toSet()
    require(missing.isEmpty()) { "no measurement for models: $missing" }
    results.forEach { r ->
        val who = "${r.modelId}/${r.workload}"
        require(r.workload.isNotBlank()) { "$who: blank workload" }
        require(r.inputDurationMs > 0) { "$who: input duration missing" }
        require(r.elapsedMs > 0) { "$who: elapsed missing" }
        require(r.peakRamBytes > 0) { "$who: peak RAM missing" }
        require(r.outputChars > 0) { "$who: empty output" }
    }
    require(environment.device.isNotBlank() && environment.abi.isNotBlank() && environment.sdkInt > 0) {
        "environment fields missing"
    }
}
