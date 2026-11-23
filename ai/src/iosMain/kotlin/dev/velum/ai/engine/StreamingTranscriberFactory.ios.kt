package dev.velum.ai.engine

import dev.velum.ai.local.asr.IosLocalInference
import dev.velum.ai.local.asr.LocalAsrRuntime
import dev.velum.ai.pipeline.BoundedPcmRingBuffer
import dev.velum.ai.pipeline.WindowedAsr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

actual fun createStreamingTranscriber(
    modelPath: String?,
    modelId: String,
): StreamingTranscriber {
    require(!modelPath.isNullOrBlank()) { "iOS streaming requires a Ready local ASR model path" }
    require(modelId in setOf("whisper-tiny", "whisper-large-v3-turbo", "sensevoice-small", "parakeet-tdt-0.6b-v3-int8")) {
        "Unsupported iOS chunked ASR model: $modelId"
    }
    checkNotNull(IosLocalInference.bridge) { "iOS local inference adapter was not installed" }
    val runtime = LocalAsrRuntime()
    return IosChunkedStreamingTranscriber(object : Transcriber {
        override val engineId = "local-asr"
        override suspend fun transcribe(input: AudioInput, modelId: String) = runtime.transcribe(
            input.bytes, input.sampleRateHz, input.languageHint, modelId, modelPath, input.initialPrompt,
        )
    }, modelId)
}

/** Caller supplies mono PCM16 LE. Offline Sherpa windows do not imply microphone or online-model support. */
internal class IosChunkedStreamingTranscriber(
    private val transcriber: Transcriber,
    private val modelId: String,
) : StreamingTranscriber {
    override val engineId = "local-streaming-asr"
    override var onPartial: (String) -> Unit = {}
    private var pcm = BoundedPcmRingBuffer(2)
    private var started = false
    private var sampleRate = 16_000
    private var language: String? = null
    private var prompt: String? = null
    private val committed = StringBuilder()
    private var samples = 0L

    override suspend fun start(sampleRateHz: Int, languageHint: String?, initialPrompt: String?) {
        currentCoroutineContext().ensureActive()
        check(!started) { "Already started" }
        require(sampleRateHz in 8_000..192_000) { "Unsupported PCM sample rate: $sampleRateHz" }
        sampleRate = sampleRateHz
        language = languageHint
        prompt = initialPrompt
        pcm = BoundedPcmRingBuffer(WindowedAsr.pcmWindowBytes(sampleRateHz, 3))
        committed.clear()
        samples = 0
        started = true
        onPartial("**partial** · 0:00\n")
    }

    override suspend fun pushPcm(pcm16Le: ByteArray) {
        currentCoroutineContext().ensureActive()
        check(started) { "start() required" }
        require(pcm16Le.size % 2 == 0) { "Expected mono PCM16 LE audio" }
        var offset = 0
        while (offset < pcm16Le.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(pcm.capacity - pcm.size, pcm16Le.size - offset)
            pcm.write(pcm16Le.copyOfRange(offset, offset + count))
            samples += count / 2
            offset += count
            if (pcm.size == pcm.capacity) {
                decodePending()
                val seconds = samples / sampleRate
                onPartial("**partial** · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}\n$committed\n")
            }
        }
    }

    override suspend fun finish(): String {
        currentCoroutineContext().ensureActive()
        check(started) { "start() required" }
        decodePending()
        started = false
        // A single speaker label matches the streaming contract; this path performs no diarization.
        val markdown = "**Speaker 1** · 0:00\n$committed\n"
        onPartial(markdown)
        return markdown
    }

    private suspend fun decodePending() {
        if (pcm.size == 0) return
        val result = transcriber.transcribe(AudioInput(
            filename = "live.pcm", bytes = pcm.toByteArray(), sampleRateHz = sampleRate,
            languageHint = language, initialPrompt = prompt,
        ), modelId)
        currentCoroutineContext().ensureActive()
        pcm.clear()
        if (result.text.isNotBlank()) {
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(result.text.trim())
        }
    }

    override suspend fun cancel() {
        started = false
        pcm.clear()
        committed.clear()
        onPartial = {}
    }
}
