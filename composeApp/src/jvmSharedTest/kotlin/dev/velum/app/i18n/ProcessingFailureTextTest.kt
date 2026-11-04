package dev.velum.app.i18n

import dev.velum.ai.ApiErrorSanitizer
import dev.velum.ai.local.SPEAKER_CACHE_NOT_DROPPED_MESSAGE
import dev.velum.ai.pipeline.AUDIO_FILE_EMPTY_MESSAGE
import dev.velum.ai.pipeline.AUDIO_FILE_MISSING_MESSAGE
import dev.velum.ai.pipeline.INSUFFICIENT_STORAGE_MESSAGE
import dev.velum.ai.pipeline.NO_AUDIO_OR_TRANSCRIPT_MESSAGE
import dev.velum.ai.pipeline.NO_TRANSCRIPT_TO_SUMMARIZE_MESSAGE
import dev.velum.ai.pipeline.PROCESSING_STOPPED_MESSAGE
import dev.velum.ai.pipeline.WAV_NO_DATA_MESSAGE
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
        SPEAKER_CACHE_NOT_DROPPED_MESSAGE to { s -> s.added.speakerCacheNotDropped },
        "Unsupported audio format (WebM/Matroska). This file is not Ogg Opus, WAV or raw 16 kHz PCM, so it was not transcribed; re-download it from the device or convert it to WAV or Ogg Opus." to { s -> s.ux.processingFailedUnsupportedFormat },
        WAV_NO_DATA_MESSAGE to { s -> s.added.processingFailedWavNoData },
        AUDIO_FILE_MISSING_MESSAGE to { s -> s.added.processingFailedAudioMissing },
        AUDIO_FILE_EMPTY_MESSAGE to { s -> s.added.processingFailedAudioEmpty },
        "Unsupported audio format (RF64). This file is not Ogg Opus, WAV or raw 16 kHz PCM, so it was not transcribed; re-download it from the device or convert it to WAV or Ogg Opus." to { s -> s.ux.processingFailedUnsupportedFormat },
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

    @Test fun unknown_failure_detail_drops_every_identifying_value() {
        val probes = listOf(
            "AABBCCDDEEFF", // MAC without separators
            "0123456789abcdef0123", // long hex
            "123e4567-e89b-12d3-a456-426614174000", // UUID
            "2001:db8::8a2e:370:7334", "fe80::1", "::1", "::ffff:192.0.2.1", // IPv6
            "192.0.2.1", // IPv4
            "PLAUD-8812345", "PLAUD8812345", "881FAKE1", "8812345", // serials
            "https://api.example.test/v1/chat?key=x", "ftp://files.example.test", // URLs
        )
        for (probe in probes) {
            assertEquals("Processing failed (failed for … now).", processingFailureText("failed for $probe now", En), probe)
        }
    }

    @Test fun a_url_leaves_no_scheme_fragment() {
        assertEquals("Processing failed (… failed: HTTP 503).", processingFailureText("https://api.example.test/v1/chat failed: HTTP 503", En))
    }

    @Test fun a_quoted_host_is_cut_and_the_words_stay() {
        assertEquals(
            "Processing failed (Unable to resolve host …: No address).",
            processingFailureText("""Unable to resolve host "api.example.test": No address.""", En),
        )
    }

    @Test fun an_unquoted_host_is_cut() {
        assertEquals(
            "Processing failed (Failed to connect to …:11434).",
            processingFailureText("Failed to connect to nas.example.lan/192.0.2.5:11434", En),
        )
        assertEquals("Processing failed (Unable to resolve host …).", processingFailureText("Unable to resolve host nas.example.lan", En))
    }

    @Test fun only_the_first_line_is_shown() {
        val raw = "Hostname nas.example.lan not verified:\n    certificate: sha256/AAAA\n    DN: CN=someone, O=Example"
        assertEquals("Processing failed (Hostname … not verified:).", processingFailureText(raw, En))
    }

    @Test fun a_word_before_a_path_is_kept_whole() {
        assertEquals("Processing failed (Error:…).", processingFailureText("Error:/data/x/y", En))
    }

    @Test fun an_email_with_accented_letters_is_cut() {
        assertEquals("Processing failed (sent to …).", processingFailureText("sent to joão@exâmple.lan", En))
    }

    @Test fun only_the_start_of_a_long_row_is_scanned() {
        // A URL filling the scan limit is all that is left, so text past the limit must not bring a detail back.
        val raw = "https://nas.example.lan/" + "x".repeat(240) + " past the limit"
        assertEquals(null, processingFailureText(raw, En))
    }

    @Test fun ordinary_diagnostics_stay_readable() {
        for (kept in listOf("HTTP 503", "got 8000 Hz", "timeout after 30000 ms", "error: code 12", "java.io.IOException", "gemma-4-e2b")) {
            assertEquals("Processing failed ($kept).", processingFailureText(kept, En), kept)
        }
    }

    @Test fun a_detail_with_nothing_left_adds_no_second_line() {
        for (raw in listOf("", "   ", ".", "/data/user/0/app/x.ogg", "https://api.example.test")) {
            assertEquals(null, processingFailureText(raw, En), "'$raw'")
        }
    }

    @Test fun unknown_failure_detail_is_length_capped() {
        val shown = processingFailureText("x".repeat(500), En).orEmpty()
        assertTrue(shown.length <= "Processing failed ().".length + 120, shown)
        assertTrue(shown.endsWith("…)."), shown)
    }
}
