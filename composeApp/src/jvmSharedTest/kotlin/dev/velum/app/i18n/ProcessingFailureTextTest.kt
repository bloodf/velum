package dev.velum.app.i18n

import dev.velum.ai.ApiErrorSanitizer
import dev.velum.ai.pipeline.INSUFFICIENT_STORAGE_MESSAGE
import dev.velum.ai.pipeline.NO_AUDIO_OR_TRANSCRIPT_MESSAGE
import dev.velum.ai.pipeline.NO_TRANSCRIPT_TO_SUMMARIZE_MESSAGE
import dev.velum.ai.pipeline.PROCESSING_STOPPED_MESSAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #348: a saved processing failure shows in the app's language, whatever language the device uses. */
class ProcessingFailureTextTest {
    /** Saved text as common code and the engines write it, with synthetic paths, and the copy it must become. */
    private val cases: List<Pair<String, (Strings) -> String>> = listOf(
        INSUFFICIENT_STORAGE_MESSAGE to { s -> s.ux.insufficientStorage },
        "Unsupported audio format (WebM/Matroska). This file is not Ogg Opus, WAV or raw 16 kHz PCM, so it was not transcribed; re-download it from the device or convert it to WAV or Ogg Opus." to { s -> s.ux.processingFailedUnsupportedFormat },
        NO_TRANSCRIPT_TO_SUMMARIZE_MESSAGE to { s -> s.ux.processingFailedNoTranscript },
        NO_AUDIO_OR_TRANSCRIPT_MESSAGE to { s -> s.ux.processingFailedNoAudio },
        "Audio is too large to process: 9 bytes exceeds 8" to { s -> s.ux.processingFailedTooLarge },
        "ASR model directory missing at /data/models/x. Download and extract the model in Model manager." to { s -> s.ux.processingFailedModelFiles },
        "Model archive at /data/models/x.tar.bz2 is not extracted. Extract sherpa-onnx weights (encoder/decoder/tokens) before Local ASR." to { s -> s.ux.processingFailedModelFiles },
        "Whisper ASR config incomplete under /data/models/x. Need *tokens*.txt, *encoder*.onnx, *decoder*.onnx. Present: []" to { s -> s.ux.processingFailedModelFiles },
        "Whistle weights missing at /data/models/whistle/whistle.cact. Download model id 'whistle' in Model manager (SHA-verified)." to { s -> s.ux.processingFailedModelFiles },
        "Local LLM weights missing under /data/models/x for modelId=gemma. Expected *.litertlm." to { s -> s.ux.processingFailedModelFiles },
        "Sherpa OfflineRecognizer returned empty text for modelId=parakeet. Check audio." to { s -> s.ux.processingFailedNoText },
        "Whistle returned empty transcript (silence/noise or unsupported language)." to { s -> s.ux.processingFailedNoText },
        "Needle whistle transcription failed: native error" to { s -> s.ux.processingFailedLocalEngine },
        "Whistle/Needle requires 16 kHz mono PCM (got 8000 Hz). Normalize audio before Local ASR." to { s -> s.ux.processingFailedLocalEngine },
        PROCESSING_STOPPED_MESSAGE to { s -> s.ux.processingStoppedByAndroid },
        "Processing was stopped by Android because it could not run in the foreground. Open Velum and retry." to { s -> s.ux.processingStoppedByAndroid },
        "O processamento foi interrompido pelo Android porque não pôde rodar em primeiro plano. Abra o Velum e tente de novo." to { s -> s.ux.processingStoppedByAndroid },
    )

    @Test fun each_known_failure_shows_in_the_apps_language() {
        for ((raw, expected) in cases) {
            for (strings in listOf(En, PtBr)) {
                val shown = processingFailureText(raw, strings)
                assertEquals(expected(strings), shown, raw)
                assertFalse("/data/" in shown.orEmpty(), "no file path reaches the screen: $raw")
            }
            assertNotEquals(expected(En), expected(PtBr), "Portuguese copy differs: $raw")
        }
    }

    @Test fun portuguese_copy_has_no_english_from_the_saved_text() {
        for ((raw, _) in cases) {
            val shown = processingFailureText(raw, PtBr).orEmpty()
            listOf("model", "manager", "stopped", "storage", "transcript", "the").forEach {
                assertFalse(Regex("\\b$it\\b").containsMatchIn(shown), "'$it' in pt-BR copy for: $raw")
            }
        }
    }

    @Test fun out_of_storage_failure_is_shown_in_the_app_language() {
        // The processor stores the sanitized message; the screen must still recognise it after that round trip.
        val stored = ApiErrorSanitizer.sanitize(INSUFFICIENT_STORAGE_MESSAGE)
        assertEquals(En.ux.insufficientStorage, processingFailureText(stored, En))
        assertEquals(PtBr.ux.insufficientStorage, processingFailureText(stored, PtBr))
    }

    @Test fun english_copy_matches_the_stored_text_and_portuguese_is_not_english() {
        assertEquals(INSUFFICIENT_STORAGE_MESSAGE, En.ux.insufficientStorage)
        assertNotEquals(En.ux.insufficientStorage, PtBr.ux.insufficientStorage)
    }

    @Test fun missing_failure_shows_nothing() {
        assertEquals(null, processingFailureText(null, PtBr))
    }

    @Test fun unknown_failure_keeps_its_detail_inside_localized_copy() {
        assertEquals("O processamento falhou (Provider said no (HTTP 500)).", processingFailureText("Provider said no (HTTP 500).", PtBr))
        assertEquals("Processing failed (Provider said no (HTTP 500)).", processingFailureText("Provider said no (HTTP 500)", En))
    }

    @Test fun unknown_failure_detail_drops_paths_emails_and_hardware_addresses() {
        val raw = "open failed: /storage/emulated/0/Velum/meeting.ogg for someone@example.test on AA:BB:CC:DD:EE:FF"
        val shown = processingFailureText(raw, En).orEmpty()
        assertEquals("Processing failed (open failed: … for … on …).", shown)
        val windows = processingFailureText("""cannot read C:\Users\someone\rec.wav""", En).orEmpty()
        assertEquals("Processing failed (cannot read …).", windows)
    }

    @Test fun unknown_failure_detail_is_length_capped() {
        val shown = processingFailureText("x".repeat(500), En).orEmpty()
        assertTrue(shown.length <= "Processing failed ().".length + 120, shown)
        assertTrue(shown.endsWith("…)."), shown)
    }
}
