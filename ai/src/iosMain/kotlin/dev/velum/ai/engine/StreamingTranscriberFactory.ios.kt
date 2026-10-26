package dev.velum.ai.engine

actual fun createStreamingTranscriber(
    modelPath: String?,
    modelId: String,
): StreamingTranscriber {
    throw UnsupportedOperationException(
        "StreamingTranscriber is Android-first; iOS not wired yet. modelId=$modelId",
    )
}
