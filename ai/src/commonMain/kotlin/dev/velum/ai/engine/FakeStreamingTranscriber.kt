package dev.velum.ai.engine

/**
 * JVM / CI test double. Concatenates PCM chunk sizes into a Markdown log.
 * Not for production Android — use [createStreamingTranscriber] there.
 */
class FakeStreamingTranscriber : StreamingTranscriber {
    override val engineId: String = "fake-streaming-asr"
    override var onPartial: (markdown: String) -> Unit = {}

    private var started = false
    private val parts = mutableListOf<String>()
    private var sampleRate = 16_000
    private var prompt: String? = null

    override fun start(
        sampleRateHz: Int,
        languageHint: String?,
        initialPrompt: String?,
    ) {
        started = true
        sampleRate = sampleRateHz
        prompt = initialPrompt
        parts.clear()
        val header = buildString {
            append("**partial** · 0:00\n")
            if (!initialPrompt.isNullOrBlank()) {
                append("_prompt: ${initialPrompt.take(80)}_\n")
            }
        }
        onPartial(header)
        parts += header
    }

    override fun pushPcm(pcm16Le: ByteArray) {
        check(started) { "StreamingTranscriber.start() required before pushPcm" }
        val ms = if (sampleRate <= 0) 0 else (pcm16Le.size / 2) * 1000L / sampleRate
        val line = "chunk ${pcm16Le.size}B (~${ms}ms)\n"
        parts += line
        onPartial(parts.joinToString(""))
    }

    override fun finish(): String {
        check(started) { "StreamingTranscriber.start() required before finish" }
        started = false
        val final = buildString {
            append(parts.joinToString(""))
            append("\n**final**\n")
            append("Streaming transcription complete (${parts.size} parts).\n")
        }
        onPartial(final)
        return final
    }
}
