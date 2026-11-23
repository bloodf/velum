package dev.velum.ai.local.asr

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.pcm16LeToFloats
import dev.velum.ai.pipeline.SummaryLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Implemented by the app's Swift adapter to the same Sherpa and LiteRT-LM engines as Android. */
interface IosLocalInferenceBridge {
    fun start(request: String, samples: FloatArray, onText: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit): IosLocalInferenceTask
}

interface IosLocalInferenceTask {
    fun cancel()
}

object IosLocalInference {
    var bridge: IosLocalInferenceBridge? = null
    internal val mutex = Mutex()
}

actual class LocalAsrRuntime actual constructor() {
    actual suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
        initialPrompt: String?,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        require(sampleRateHz > 0 && pcmOrEncoded.size % 2 == 0) { "Expected mono PCM16 audio" }
        val result = Json.parseToJsonElement(run("transcribe", modelId, modelPath, pcm16LeToFloats(pcmOrEncoded)) {
            put("sampleRate", sampleRateHz)
            put("language", languageHint.orEmpty())
            put("initialPrompt", initialPrompt.orEmpty())
        }).jsonObject
        val text = result.getValue("text").jsonPrimitive.content.trim()
        // A successful native decode may contain silence; it has no transcript segments.
        // ponytail: one segment per input window; use token timestamps when finer citations are required.
        TranscriptionResult(
            text, if (text.isEmpty()) emptyList() else
                listOf(TranscriptSegment(0, pcmOrEncoded.size.toLong() * 500 / sampleRateHz, text)),
            result["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: languageHint,
            "local-asr", modelId,
        )
    }

    actual suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
        embeddingModelPath: String,
        voiceProfiles: VoiceProfileStore?,
        recordingId: String?,
        matchThreshold: Float,
    ): DiarizationResult = withContext(Dispatchers.IO) {
        require(sampleRateHz > 0 && pcmOrEncoded.size % 2 == 0) { "Expected mono PCM16 audio" }
        val result = Json.parseToJsonElement(run("diarize", modelId, modelPath, pcm16LeToFloats(pcmOrEncoded)) {
            put("sampleRate", sampleRateHz)
            put("embeddingPath", embeddingModelPath)
        }).jsonObject
        val spans = result.getValue("spans").jsonArray.map { item ->
            val span = item.jsonObject
            SpeakerSpan(span.getValue("speaker").jsonPrimitive.content,
                span.getValue("startMs").jsonPrimitive.long, span.getValue("endMs").jsonPrimitive.long)
        }
        val embeddings = result.getValue("embeddings").jsonObject.mapValues { (_, values) ->
            values.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
        VoiceProfileStore.SpeakerNaming.resultFor(
            spans, voiceProfiles?.nameSpeakers(embeddings, matchThreshold),
            engineId = "local-diarizer", modelId = modelId, embeddings = embeddings,
        )
    }

    actual suspend fun complete(request: LlmRequest, modelId: String, modelPath: String): LlmResult = withContext(Dispatchers.IO) {
        val text = run("complete", modelId, modelPath) {
            val language = request.outputLanguage.trim().lowercase()
            put("systemPrompt", request.systemPrompt + if (language.isNotEmpty() && language != "auto")
                "\n" + SummaryLocalization.languageInstruction(language) else "")
            put("userPrompt", request.userPrompt)
            put("maxOutputTokens", request.maxOutputTokens)
        }
        check(text.isNotBlank()) { "LiteRT-LM returned empty text" }
        LlmResult(text, "local-llm", modelId)
    }

    private suspend fun run(
        operation: String, modelId: String, modelPath: String, samples: FloatArray = floatArrayOf(),
        fields: JsonObjectBuilder.() -> Unit,
    ): String = IosLocalInference.mutex.withLock {
        val bridge = checkNotNull(IosLocalInference.bridge) { "iOS local inference adapter was not installed" }
        val request = buildJsonObject {
            put("operation", operation); put("modelId", modelId); put("modelPath", modelPath); fields()
        }.toString()
        var task: IosLocalInferenceTask? = null
        awaitLocalText(
            start = { text, done, fail -> task = bridge.start(request, samples, text, done) { fail(IllegalStateException(it)) } },
            cancel = { task?.cancel() },
        )
    }
}
