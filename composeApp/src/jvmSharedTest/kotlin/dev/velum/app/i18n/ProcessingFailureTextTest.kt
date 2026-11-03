package dev.velum.app.i18n

import dev.velum.ai.pipeline.INSUFFICIENT_STORAGE_MESSAGE
import dev.velum.ai.pipeline.NO_AUDIO_OR_TRANSCRIPT_MESSAGE
import dev.velum.ai.pipeline.NO_TRANSCRIPT_TO_SUMMARIZE_MESSAGE
import dev.velum.ai.pipeline.PROCESSING_STOPPED_MESSAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** #348: a saved processing failure shows in the app's language, whatever language the device uses. */
class ProcessingFailureTextTest {
    /** Saved text as common code and the engines write it, with synthetic paths, and the copy it must become. */
    private val cases: List<Pair<String, (Strings) -> String>> = listOf(
        INSUFFICIENT_STORAGE_MESSAGE to { s -> s.ux.processingFailedStorage },
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
}
