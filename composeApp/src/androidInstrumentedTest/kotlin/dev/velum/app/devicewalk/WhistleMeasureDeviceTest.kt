package dev.velum.app.devicewalk

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.DefaultLocalCatalog
import dev.velum.ai.needle.NeedleWhistleEngine
import dev.velum.app.VelumApplication
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #13 measurement harness for Whistle (Needle `.cact`) on a device or emulator: loads the model, transcribes a
 * speech clip and reports load time, real-time factor and memory. Same test and same inputs on the constrained
 * emulator and on the physical phone; the results go in docs/ with the environment they came from.
 *
 * Inputs (instrumentation args; files readable by the app, e.g. pushed to /data/local/tmp):
 * - `whistleCact`: `whistle.cact`; its SHA-256 is checked against the catalog entry and printed
 * - `whistleWav`: 16 kHz mono 16-bit PCM WAV with speech, a short clip (10 to 30 s); its SHA-256 is printed
 * - `whistleLang`: optional language hint, default `en`
 * - `appRevision`: optional `git rev-parse HEAD` of the build, printed with the versionName
 * The test is skipped only when `whistleCact` / `whistleWav` are not given; when given, a missing or unreadable
 * file fails. A run that did not print the `velum.whistle` status line measured nothing.
 *
 * Definitions: cold = first transcribe call (includes model load); warm = median of three further calls on the
 * loaded model; loadMs = cold - warm, an ESTIMATE (it is the difference of two timed transcriptions, not a timed
 * load, and is only meaningful for a short clip); RTF = warm seconds / audio seconds (below 1 is faster than real
 * time); rssBeforeKb / peakRssKb = VmRSS before the first call and VmHWM after the last (the whole test process,
 * which includes the JVM and the app).
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.devicewalk.WhistleMeasureDeviceTest \
 *   -e velumDeviceWalk false -e whistleCact /data/local/tmp/whistle.cact \
 *   -e whistleWav /data/local/tmp/speech16k.wav -e appRevision $(git rev-parse HEAD) \
 *   dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class WhistleMeasureDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun whistle_loads_transcribes_and_reports_load_time_rtf_and_memory() {
        val cactArg = args.getString("whistleCact").orEmpty()
        val wavArg = args.getString("whistleWav").orEmpty()
        assumeTrue("whistleCact / whistleWav not given", cactArg.isNotEmpty() && wavArg.isNotEmpty())
        val cact = File(cactArg)
        val wav = File(wavArg)
        assertTrue("whistleCact not a readable file: $cactArg", cact.isFile && cact.canRead())
        assertTrue("whistleWav not a readable file: $wavArg", wav.isFile && wav.canRead())
        val lang = args.getString("whistleLang") ?: "en"
        assertTrue("libvelum_needle.so not available on this ABI (${Build.SUPPORTED_ABIS.toList()})", NeedleWhistleEngine.isNativeAvailable)

        val cactSha = sha256(cact.readBytes())
        val expectedSha = DefaultLocalCatalog.first { it.id == "whistle" }.sha256
        assertEquals("whistle.cact does not match the catalog SHA-256", expectedSha, cactSha)
        val wavBytes = wav.readBytes()
        val pcm = pcm16kMono(wavBytes)
        val audioSec = pcm.size / 2 / 16_000.0

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { (context.applicationContext as VelumApplication).awaitContainer() } // app startup is not timed
        val rssBeforeKb = statusKb("VmRSS")
        val hwmBeforeKb = statusKb("VmHWM")

        fun run(): Pair<Long, String> {
            val t0 = SystemClock.elapsedRealtime()
            val result = runBlocking { NeedleWhistleEngine.transcribe(pcm, 16_000, lang, cact.absolutePath) }
            return SystemClock.elapsedRealtime() - t0 to result.text
        }
        val (coldMs, coldText) = run()
        val warm = List(WARM_RUNS) { run() }
        val warmMs = warm.map { it.first }.sorted()[WARM_RUNS / 2]
        val transcript = warm.last().second
        val peakKb = statusKb("VmHWM")

        assertTrue("empty transcript", coldText.isNotBlank() && transcript.isNotBlank())
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "abi=${Build.SUPPORTED_ABIS.first()} app=${pkg.versionName} rev=${args.getString("appRevision") ?: "unknown"} " +
            "model=whistle sha256=$cactSha clipSha256=${sha256(wavBytes)} lang=$lang " +
            "audioSec=${String.format(Locale.ROOT, "%.1f", audioSec)} coldMs=$coldMs warmMedianMs=$warmMs (n=$WARM_RUNS) " +
            "loadMsEstimate=${coldMs - warmMs} rtf=${String.format(Locale.ROOT, "%.3f", warmMs / 1000.0 / audioSec)} " +
            "rssBeforeKb=$rssBeforeKb hwmBeforeKb=$hwmBeforeKb peakRssKb=$peakKb transcript=\"${transcript.replace('\n', ' ')}\""
        Log.i(TAG, report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.whistle", report) })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /** Samples of the `data` chunk; the clip must already be 16 kHz mono PCM s16le. */
    private fun pcm16kMono(wav: ByteArray): ByteArray {
        fun le(o: Int, n: Int) = (0 until n).sumOf { (wav[o + it].toLong() and 0xFF) shl (8 * it) }
        require(wav.size > 12 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var format = 0L; var rate = 0L; var channels = 0L; var bits = 0L
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val len = le(pos + 4, 4).toInt()
            if (id == "fmt ") { format = le(pos + 8, 2); channels = le(pos + 10, 2); rate = le(pos + 12, 4); bits = le(pos + 22, 2) }
            if (id == "data") {
                require(format == 1L && rate == 16_000L && channels == 1L && bits == 16L) {
                    "need PCM (tag 1) 16 kHz mono 16-bit, got tag $format/$rate Hz/$channels ch/$bits bit"
                }
                return wav.copyOfRange(pos + 8, minOf(wav.size, pos + 8 + len))
            }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk")
    }

    private fun statusKb(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.filter { it.isDigit() }.toLong()

    private companion object {
        const val TAG = "VelumWhistle13"
        const val WARM_RUNS = 3
    }
}
