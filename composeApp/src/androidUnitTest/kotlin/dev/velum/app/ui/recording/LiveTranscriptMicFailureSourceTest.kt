package dev.velum.app.ui.recording

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the Android mic-failure → demo-PCM ship blocker in LiveTranscriptPane.
 */
class LiveTranscriptMicFailureSourceTest {
    private fun screenSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/dev/velum/app/ui/recording/RecordingDetailScreen.kt"),
            File("composeApp/src/commonMain/kotlin/dev/velum/app/ui/recording/RecordingDetailScreen.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("RecordingDetailScreen.kt not found")
        return file.readText()
    }

    @Test
    fun micStartFailure_doesNotUnconditionallyEnableDemoPcm() {
        val src = screenSource()
        // Old bug: else -> { useDemo = true } for every non-Started result.
        assertFalse(
            Regex("""else\s*->\s*\{[^}]*useDemo\s*=\s*true""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(src),
            "MicStartResult failure must not fall through to useDemo=true",
        )
        // #196: no synthetic audio or stand-in transcriber anywhere in the screen, on any platform.
        listOf("useDemo", "demoPcmChunk", "FakeStreamingTranscriber", "WavWriter").forEach {
            assertFalse(src.contains(it), "RecordingDetailScreen must not reference $it")
        }
        assertTrue(src.contains("MicStartResult.Unsupported"))
        assertTrue(src.contains("is MicStartResult.Failed"))
        assertTrue(src.contains("micError"))
    }

    @Test
    fun micSourceChips_disabledWhileCapturing() {
        val src = screenSource()
        assertTrue(
            src.contains("enabled = !usingMic"),
            "mic source chips must be disabled while FGS capture is active",
        )
    }
}
