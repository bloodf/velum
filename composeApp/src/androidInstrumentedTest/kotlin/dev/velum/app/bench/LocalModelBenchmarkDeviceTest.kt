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
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.createLocalModelManager
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.ai.local.LocalTranscriber
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
 * Models must already be Ready in the app's model store (download them in the app, or push them);
 * a model that is not Ready, fails, or yields empty output FAILS the run. Emulator numbers are not
 * phone-performance evidence (that stays in #12).
 *
 * Instrumentation args: `bench.models` (comma list, default [DEFAULT_MODELS]), `bench.audio` (device path
 * to 16 kHz mono PCM16 WAV; default: bundled walk tone), `bench.workloadSeconds` (source duration; the input is
 * looped to reach it; default 60, use 3600 for the #12 1 h workload).
 */
@RunWith(AndroidJUnit4::class)
class LocalModelBenchmarkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    @Test fun benchmark_local_models() = runBlocking {
        val models = (args.getString("bench.models") ?: DEFAULT_MODELS).split(',').map(String::trim).filter(String::isNotEmpty)
        val workloadSec = (args.getString("bench.workloadSeconds") ?: "60").toInt()
        assertTrue("bench.workloadSeconds must be positive", workloadSec > 0)
        val wavBytes = args.getString("bench.audio")?.let { File(it).readBytes() }
            ?: instrumentation.context.assets.open("device_walk_tone.wav").use { it.readBytes() }
        val pcm = loop(wavBytes.copyOfRange(WAV_HEADER_BYTES, wavBytes.size), workloadSec)
        val inputMs = workloadSec * 1000L
        val manager = createLocalModelManager(defaultModelStoreRoot())
        val results = models.map { id ->
            check(manager.isReady(id)) { "benchmark model '$id' is not Ready (status=${manager.stateFor(id)?.status}); download it first" }
            if (id.startsWith("gemma") || id.startsWith("qwen")) summarize(manager, id, inputMs) else transcribe(manager, id, pcm, inputMs, workloadSec)
        }
        val report = BenchReport(environment(models, workloadSec), results)
        report.requireValid(models.toSet())
        val json = report.toJson()
        Log.i(TAG, json)
        args.getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() }.resolve("bench-report.json").writeText(json) }
    }

    private suspend fun transcribe(manager: LocalModelManager, id: String, pcm: ByteArray, inputMs: Long, sec: Int): BenchResult {
        var text = ""
        resetPeak()
        val ns = measureNanoTime {
            text = LocalTranscriber(manager).transcribe(AudioInput("bench.pcm", pcm, 16_000), id).text
        }
        return BenchResult(BenchTask.Transcription, id, "${sec}s", inputMs, ns / 1_000_000, peakRssBytes(), text.length)
    }

    private suspend fun summarize(manager: LocalModelManager, id: String, inputMs: Long): BenchResult {
        var text = ""
        resetPeak()
        val ns = measureNanoTime {
            text = LocalLlmEngine(manager).complete(LlmRequest("Summarize the meeting in five bullet points.", TRANSCRIPT), id).text
        }
        return BenchResult(BenchTask.Summary, id, "fixed-transcript", inputMs, ns / 1_000_000, peakRssBytes(), text.length)
    }

    private fun environment(models: List<String>, workloadSec: Int) = BenchEnvironment(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.first(),
        isEmulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"),
        settings = mapOf(
            "models" to models.joinToString(","),
            "workloadSeconds" to workloadSec.toString(),
            "audio" to (args.getString("bench.audio") ?: "assets/device_walk_tone.wav (looped)"),
            "summaryTranscriptChars" to TRANSCRIPT.length.toString(),
            "cores" to Runtime.getRuntime().availableProcessors().toString(),
        ),
    )

    private fun loop(pcm: ByteArray, seconds: Int): ByteArray {
        val want = seconds * 16_000 * 2
        val unit = pcm.copyOf(pcm.size and 1.inv()) // whole 16-bit samples only
        require(unit.isNotEmpty()) { "benchmark audio is empty" }
        return ByteArray(want) { unit[it % unit.size] }
    }

    /** Peak resident set (VmHWM) of this process; reset before each run via clear_refs=5. */
    private fun resetPeak() = runCatching { File("/proc/self/clear_refs").writeText("5") }.let { }

    private fun peakRssBytes(): Long = File("/proc/self/status").readLines()
        .first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() * 1024

    private companion object {
        const val TAG = "VelumBench98"
        const val DEFAULT_MODELS = "parakeet-tdt-0.6b-v3-int8,whisper-large-v3-turbo,whisper-tiny,gemma-4-e4b"
        const val WAV_HEADER_BYTES = 44
        /** Fixed workload for the summary benchmark; it stands for ~[TRANSCRIPT_AUDIO_SECONDS] s of meeting audio. */
        const val TRANSCRIPT = "Maria: Let's review the launch plan. The beta ships on Thursday and QA still has two open bugs. " +
            "Joao: Both bugs are in the export flow; I can fix them by Wednesday if design confirms the new icon. " +
            "Maria: Design confirms. Priya, can you update the release notes and tell support? " +
            "Priya: Yes, notes by Wednesday noon. I will also schedule a short training for support on Friday. " +
            "Maria: Good. Next review is Monday at ten; Joao brings the bug status, Priya the notes."
    }
}
