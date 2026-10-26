package dev.velum.ai.engine

/**
 * Platform streaming ASR. Android returns a real chunked OfflineRecognizer path when
 * models are available; JVM returns [FakeStreamingTranscriber] for unit tests only.
 */
expect fun createStreamingTranscriber(
    /** Absolute path to extracted sherpa model dir; null → unavailable / fake. */
    modelPath: String? = null,
    modelId: String = "",
): StreamingTranscriber
