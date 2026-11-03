package dev.velum.ai.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchmarkReportTest {
    private val sha = "a".repeat(64)
    private val env = BenchEnvironment(
        "Pixel", "15", 35, "arm64-v8a", false,
        mapOf("threads" to "4", "audioSha256" to sha, "summaryTranscriptSha256" to sha),
    )
    private fun result(model: String = "m", elapsed: Long = 30_000, input: Long = 60_000, ram: Long = 1L shl 30, chars: Int = 10) =
        BenchResult(BenchTask.Transcription, model, "60s", input, elapsed, ram, chars)

    @Test fun rtf_is_elapsed_over_source_duration() {
        assertEquals(0.5, rtf(30_000, 60_000))
        assertEquals(2.0, result(elapsed = 120_000).rtf)
        assertFailsWith<IllegalArgumentException> { rtf(1, 0) }
    }

    @Test fun json_carries_every_required_field() {
        val json = BenchReport(env, listOf(result())).toJson()
        listOf("modelId", "workload", "rtf", "inputDurationMs", "elapsedMs", "peakRamBytes", "sdkInt", "abi", "isEmulator", "device", "settings")
            .forEach { assertTrue("\"$it\"" in json, "missing $it in $json") }
    }

    @Test fun json_rtf_value_is_elapsed_over_input() {
        val json = BenchReport(env, listOf(result(elapsed = 30_000, input = 60_000))).toJson()
        assertTrue(Regex("\"rtf\": 0\\.5").containsMatchIn(json), json)
    }

    @Test fun empty_or_blank_model_request_fails() {
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result())).requireValid(emptySet()) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result())).requireValid(setOf("")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, emptyList()).requireValid(setOf("m")) }
    }

    @Test fun valid_report_passes() = BenchReport(env, listOf(result("a"), result("b"))).requireValid(setOf("a", "b"))

    @Test fun a_transcription_run_without_an_input_hash_fails() {
        val noHash = env.copy(settings = env.settings - "audioSha256")
        assertFailsWith<IllegalArgumentException> { BenchReport(noHash, listOf(result())).requireValid(setOf("m")) }
        val badHash = env.copy(settings = env.settings + ("audioSha256" to "not-a-hash"))
        assertFailsWith<IllegalArgumentException> { BenchReport(badHash, listOf(result())).requireValid(setOf("m")) }
    }

    @Test fun a_summary_run_needs_the_transcript_hash_but_not_the_audio_hash() {
        val summary = BenchResult(BenchTask.Summary, "g", "fixed-transcript", 34_000, 1_000, 1L shl 30, 10)
        val onlyTranscript = env.copy(settings = env.settings - "audioSha256")
        BenchReport(onlyTranscript, listOf(summary)).requireValid(setOf("g"))
        val none = env.copy(settings = env.settings - "summaryTranscriptSha256")
        assertFailsWith<IllegalArgumentException> { BenchReport(none, listOf(summary)).requireValid(setOf("g")) }
    }

    @Test fun missing_model_or_measurement_fails() {
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result("a"))).requireValid(setOf("a", "b")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result(ram = 0))).requireValid(setOf("m")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result(chars = 0))).requireValid(setOf("m")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env.copy(abi = ""), listOf(result())).requireValid(setOf("m")) }
    }
}
