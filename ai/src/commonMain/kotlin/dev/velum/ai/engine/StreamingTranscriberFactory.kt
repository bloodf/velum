package dev.velum.ai.engine

/**
 * Platform streaming ASR. Android returns a real chunked OfflineRecognizer path when
 * models are available; JVM throws: there is no fake in shipped code.
 */
expect fun createStreamingTranscriber(
    /** Absolute path to extracted sherpa model dir; null → unavailable. */
    modelPath: String? = null,
    modelId: String = "",
): StreamingTranscriber
