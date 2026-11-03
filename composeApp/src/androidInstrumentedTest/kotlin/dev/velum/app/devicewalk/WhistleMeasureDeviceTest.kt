package dev.velum.app.devicewalk

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.needle.NeedleWhistleEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #13 measurement harness for Whistle (Needle `.cact`) on a device or emulator: loads the model, transcribes a
 * speech clip twice and reports load time, real-time factor and peak memory. Same test, same inputs, run on the
 * constrained emulator and on the physical phone; the results go in docs/ with the environment they came from.
 *
 * Inputs (instrumentation args, files readable by the app, e.g. pushed to /data/local/tmp):
 * - `whistleCact`: path to `whistle.cact` (SHA-256 per docs/WHISTLE_EVAL.md)
 * - `whistleWav`: 16 kHz mono 16-bit PCM WAV with speech (the bundled device-walk tone is not speech)
 * - `whistleLang`: optional language hint, default `en`
 * Skipped when `whistleCact` / `whistleWav` are not given, so default discovery is unaffected.
 *
 * Definitions: cold = first transcribe call (includes model load); warm = second call on the loaded model;
 * loadMs = cold - warm; RTF = warm transcribe seconds / audio seconds (below 1 is faster than real time);
 * peakRssKb = VmHWM from /proc/self/status after both runs (whole test process, includes the JVM).
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.devicewalk.WhistleMeasureDeviceTest \
 *   -e velumDeviceWalk false -e whistleCact /data/local/tmp/whistle.cact \
 *   -e whistleWav /data/local/tmp/speech16k.wav dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class WhistleMeasureDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun whistle_loads_transcribes_and_reports_load_time_rtf_and_memory() {
        val cact = File(args.getString("whistleCact").orEmpty())
        val wav = File(args.getString("whistleWav").orEmpty())
        assumeTrue("whistleCact / whistleWav not given", cact.isFile && wav.isFile)
        val lang = args.getString("whistleLang") ?: "en"
        assertTrue("libvelum_needle.so not available on this ABI (${Build.SUPPORTED_ABIS.toList()})", NeedleWhistleEngine.isNativeAvailable)

        val pcm = pcm16kMono(wav.readBytes())
        val audioSec = pcm.size / 2 / 16_000.0
        val rssBeforeKb = statusKb("VmRSS")

        fun run(): Pair<Long, String> {
            val t0 = SystemClock.elapsedRealtime()
            val result = runBlocking { NeedleWhistleEngine.transcribe(pcm, 16_000, lang, cact.absolutePath) }
            return SystemClock.elapsedRealtime() - t0 to result.text
        }
        val (coldMs, coldText) = run()
        val (warmMs, warmText) = run()
        val peakKb = statusKb("VmHWM")

        assertTrue("empty transcript", coldText.isNotBlank() && warmText.isNotBlank())
        val report = "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.first()} " +
            "audioSec=${"%.1f".format(audioSec)} coldMs=$coldMs warmMs=$warmMs loadMs=${(coldMs - warmMs).coerceAtLeast(0)} " +
            "rtf=${"%.3f".format(warmMs / 1000.0 / audioSec)} rssBeforeKb=$rssBeforeKb peakRssKb=$peakKb chars=${warmText.length}"
        Log.i(TAG, report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.whistle", report) })
    }

    /** Samples of the `data` chunk; the clip must already be 16 kHz mono s16le. */
    private fun pcm16kMono(wav: ByteArray): ByteArray {
        fun le(o: Int, n: Int) = (0 until n).sumOf { (wav[o + it].toLong() and 0xFF) shl (8 * it) }
        require(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0L; var channels = 0L; var bits = 0L
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val len = le(pos + 4, 4).toInt()
            if (id == "fmt ") { channels = le(pos + 10, 2); rate = le(pos + 12, 4); bits = le(pos + 22, 2) }
            if (id == "data") {
                require(rate == 16_000L && channels == 1L && bits == 16L) { "need 16 kHz mono 16-bit, got $rate Hz/$channels ch/$bits bit" }
                return wav.copyOfRange(pos + 8, minOf(wav.size, pos + 8 + len))
            }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk")
    }

    private fun statusKb(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.filter { it.isDigit() }.toLong()

    private companion object { const val TAG = "VelumWhistle13" }
}
