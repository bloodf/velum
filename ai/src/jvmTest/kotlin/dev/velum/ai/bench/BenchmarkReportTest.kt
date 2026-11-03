package dev.velum.ai.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchmarkReportTest {
    private val env = BenchEnvironment("Pixel", "15", 35, "arm64-v8a", false, mapOf("threads" to "4"))
    private fun result(model: String = "m", elapsed: Long = 30_000, input: Long = 60_000, ram: Long = 1L shl 30, chars: Int = 10) =
        BenchResult(BenchTask.Transcription, model, "60s", input, elapsed, ram, chars)

    @Test fun rtf_is_elapsed_over_source_duration() {
        assertEquals(0.5, rtf(30_000, 60_000))
        assertEquals(2.0, result(elapsed = 120_000).rtf)
        assertFailsWith<IllegalArgumentException> { rtf(1, 0) }
    }

    @Test fun json_carries_every_required_field() {
        val json = BenchReport(env, listOf(result())).toJson()
        listOf("modelId", "workload", "inputDurationMs", "elapsedMs", "peakRamBytes", "sdkInt", "abi", "isEmulator", "device", "settings")
            .forEach { assertTrue("\"$it\"" in json, "missing $it in $json") }
    }

    @Test fun valid_report_passes() = BenchReport(env, listOf(result("a"), result("b"))).requireValid(setOf("a", "b"))

    @Test fun missing_model_or_measurement_fails() {
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result("a"))).requireValid(setOf("a", "b")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result(ram = 0))).requireValid(setOf("m")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env, listOf(result(chars = 0))).requireValid(setOf("m")) }
        assertFailsWith<IllegalArgumentException> { BenchReport(env.copy(abi = ""), listOf(result())).requireValid(setOf("m")) }
    }
}
