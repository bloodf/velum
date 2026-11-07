package dev.velum.app.bench

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.bench.BenchEnvironment
import dev.velum.ai.bench.BenchReport
import dev.velum.ai.bench.BenchResult
import dev.velum.ai.bench.BenchTask
import dev.velum.ai.bench.requireValid
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.LocalTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.createLocalModelManager
import dev.velum.ai.pipeline.WindowedAsr
import java.io.File
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #98 benchmark entry point: real local inference (no mocks) for each model in `bench.models`,
 * reporting elapsed time, RTF and peak RAM. See docs/BENCHMARK.md for commands and definitions.
 *
 * Models must already be Ready in the app's store (`filesDir/models`, same as VelumApplication); a model that
 * is not Ready, fails, or yields empty output FAILS the run. Emulator numbers are not phone-performance
 * evidence (that stays in #12).
 *
 * ASR runs the shipping windowed path: 30 s windows, 28 s hop ([WindowedAsr]), one `transcribe` per window, with each
 * window built on demand so the whole workload never sits in the heap.
 *
 * The report is written to getExternalFilesDir/bench-report.json (/sdcard/Android/data/dev.velum.app/files/).
 *
 * Instrumentation args: `bench.audio` (REQUIRED device path, e.g. /sdcard/Android/data/dev.velum.app/files/bench.wav, to a 16 kHz mono PCM16 WAV of real speech; the engines
 * reject silence/tones), `bench.models` (comma list, default [DEFAULT_MODELS]), `bench.workloadSeconds`
 * (source duration, input looped to reach it; default 60, 3600 for the #12 1 h workload).
 */
@RunWith(AndroidJUnit4::class)
class LocalModelBenchmarkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    /** App-specific external dir: adb can push/pull it and the app process can read/write it without storage permission. */
    private val reportFile = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)) { "no external files dir" }, "bench-report.json")

    @Test fun benchmark_local_models() = runBlocking {
        reportFile.parentFile?.mkdirs()
        check(!reportFile.exists() || reportFile.delete()) { "cannot remove stale benchmark report" }
        check(!isEmulator()) { "model inference is permitted only on physical devices" }
        require(args.getString("bench.fixtureSensitivity") == "non-sensitive") {
            "bench.fixtureSensitivity must explicitly declare non-sensitive input"
        }
        val models = (args.getString("bench.models") ?: DEFAULT_MODELS).split(',').map(String::trim).filter(String::isNotEmpty)
        assertTrue("bench.models is empty", models.isNotEmpty())
        val workloadSec = (args.getString("bench.workloadSeconds") ?: "60").toInt()
        assertTrue("bench.workloadSeconds must be positive", workloadSec > 0)
        val audioPath = requireNotNull(args.getString("bench.audio")) { "bench.audio (device path to a 16 kHz mono PCM16 speech WAV) is required" }
        val clip = readWavPcm16Mono16k(File(audioPath))
        val manager = createLocalModelManager(ModelStoreDir(File(instrumentation.targetContext.filesDir, "models").absolutePath))
        val results = models.map { id ->
            val kind = requireNotNull(manager.stateFor(id)) { "unknown benchmark model '$id'" }.spec.kind
            check(manager.isReady(id)) { "benchmark model '$id' is not Ready (status=${manager.stateFor(id)?.status}); download it first" }
            when (kind) {
                LocalModelKind.Asr -> transcribe(manager, id, clip, workloadSec)
                LocalModelKind.Llm -> summarize(manager, id)
                else -> error("model '$id' is $kind; the benchmark runs ASR and LLM models")
            }
        }
        val report = BenchReport(environment(models, workloadSec, audioPath, clip), results)
        // Log first so a failed validation or write never loses a finished run.
        Log.i(TAG, report.toJson())
        report.requireValid(models.toSet())
        reportFile.writeText(report.toJson()) // a failed write fails the run; the old report was deleted at start
    }

    private suspend fun transcribe(manager: LocalModelManager, id: String, clip: ByteArray, sec: Int): BenchResult {
        val total = Math.multiplyExact(sec, SAMPLE_RATE * 2)
        val transcriber = LocalTranscriber(manager)
        var chars = 0
        var ns = 0L
        resetPeak()
        for (w in WindowedAsr.windows(total, SAMPLE_RATE)) {
            val window = ByteArray(w.endByte - w.startByte) { clip[(w.startByte + it) % clip.size] }
            ns += measureNanoTime { chars += transcriber.transcribe(AudioInput("bench.pcm", window, SAMPLE_RATE), id).text.length }
        }
        return BenchResult(
            task = BenchTask.Transcription,
            modelId = id,
            workload = "${sec}s",
            inputDurationMs = sec * 1000L,
            elapsedMs = ns / 1_000_000,
            peakRamBytes = peakRssBytes(),
            outputChars = chars,
            runtime = transcriber.engineId,
            timeBoundary = "windowed_asr_engine_transcribe_wall_clock_sum",
            rtfUnit = "ratio_elapsed_over_source_duration",
        )
    }

    private suspend fun summarize(manager: LocalModelManager, id: String): BenchResult {
        val engine = LocalLlmEngine(manager)
        var text = ""
        resetPeak()
        val ns = measureNanoTime {
            text = engine.complete(LlmRequest("Summarize the meeting in five bullet points.", TRANSCRIPT), id).text
        }
        return BenchResult(
            task = BenchTask.Summary,
            modelId = id,
            workload = "fixed-transcript",
            inputDurationMs = TRANSCRIPT_SOURCE_SECONDS * 1000L,
            elapsedMs = ns / 1_000_000,
            peakRamBytes = peakRssBytes(),
            outputChars = text.length,
            runtime = engine.engineId,
            timeBoundary = "single_complete_call_wall_clock",
            rtfUnit = "ratio_elapsed_over_source_duration",
        )
    }

    private fun environment(models: List<String>, workloadSec: Int, audio: String, clip: ByteArray) = BenchEnvironment(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.first(),
        isEmulator = isEmulator(),
        settings = mapOf(
            "models" to models.joinToString(","),
            "workloadSeconds" to workloadSec.toString(),
            "audio" to audio,
            "audioSha256" to sha256(clip),
            "audioFormat" to "PCM16_MONO_16KHZ",
            "fixtureSensitivity" to requireNotNull(args.getString("bench.fixtureSensitivity")),
            "summaryTranscriptSha256" to sha256(TRANSCRIPT.encodeToByteArray()),
            "asrWindowSeconds" to "${WindowedAsr.DEFAULT_WINDOW_SECONDS}/hop ${WindowedAsr.DEFAULT_HOP_SECONDS}",
            "summaryTranscriptChars" to TRANSCRIPT.length.toString(),
            "summaryTranscriptSourceSeconds" to TRANSCRIPT_SOURCE_SECONDS.toString(),
            "peakRamMethod" to PEAK_RAM_METHOD,
            "rtfUnit" to "ratio_elapsed_over_source_duration",
            "cores" to Runtime.getRuntime().availableProcessors().toString(),
        ),
    )

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("unknown") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) || Build.PRODUCT.contains("sdk", ignoreCase = true)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Minimal RIFF parse: must be PCM16, mono, 16 kHz; returns the `data` chunk (whole samples only). */
    private fun readWavPcm16Mono16k(file: File): ByteArray {
        val b = file.readBytes()
        fun u16(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "${file.path} is not a WAV file" }
        var pos = 12
        var fmtOk = false
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = u32(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                fmtOk = u16(body) == 1 && u16(body + 2) == 1 && u32(body + 4) == SAMPLE_RATE && u16(body + 14) == 16
                require(fmtOk) { "bench.audio must be PCM16 mono 16 kHz (format=${u16(body)}, ch=${u16(body + 2)}, rate=${u32(body + 4)}, bits=${u16(body + 14)})" }
            } else if (id == "data") {
                require(fmtOk) { "WAV data chunk before fmt chunk" }
                val end = minOf(b.size, body + len)
                val data = b.copyOfRange(body, end - ((end - body) and 1))
                require(data.isNotEmpty()) { "WAV has no audio samples" }
                return data
            }
            pos = body + len + (len and 1)
        }
        error("WAV has no data chunk")
    }

    /** Reset the process peak RSS (VmHWM) and fail if the reset did not take, since peaks would then be meaningless. */
    private fun resetPeak() {
        File("/proc/self/clear_refs").writeText("5")
        val rss = status("VmRSS:")
        check(status("VmHWM:") <= rss + HWM_TOLERANCE_KB) { "clear_refs did not reset VmHWM (rss=${rss}kB); peak RAM would be unreliable" }
    }

    private fun peakRssBytes(): Long = status("VmHWM:") * 1024

    private fun status(key: String): Long = File("/proc/self/status").readLines()
        .first { it.startsWith(key) }.filter(Char::isDigit).toLong()

    private companion object {
        const val TAG = "VelumBench98"
        const val SAMPLE_RATE = 16_000
        const val HWM_TOLERANCE_KB = 4096L
        const val PEAK_RAM_METHOD = "proc_self_clear_refs_5_vmhwm"
        const val DEFAULT_MODELS = "parakeet-tdt-0.6b-v3-int8,whisper-large-v3-turbo,whisper-tiny,gemma-4-e4b"

        /** The fixed summary workload (84 words). */
        const val TRANSCRIPT = "Maria: Let's review the launch plan. The beta ships on Thursday and QA still has two open bugs. " +
            "Joao: Both bugs are in the export flow; I can fix them by Wednesday if design confirms the new icon. " +
            "Maria: Design confirms. Priya, can you update the release notes and tell support? " +
            "Priya: Yes, notes by Wednesday noon. I will also schedule a short training for support on Friday. " +
            "Maria: Good. Next review is Monday at ten; Joao brings the bug status, Priya the notes."

        /** Source duration the transcript stands for: 84 words at an assumed 150 words per minute (about 34 s). */
        const val TRANSCRIPT_SOURCE_SECONDS = 34
    }
}
