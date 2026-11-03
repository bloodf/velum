package dev.velum.ai.engine

/** Streaming transcription ships on Android only; JVM has no engine and must not fake one. */
actual fun createStreamingTranscriber(
    modelPath: String?,
    modelId: String,
): StreamingTranscriber {
    throw UnsupportedOperationException("StreamingTranscriber is Android-only. modelId=$modelId")
}
