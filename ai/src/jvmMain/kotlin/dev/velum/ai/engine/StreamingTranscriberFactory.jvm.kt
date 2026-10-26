package dev.velum.ai.engine

/**
 * JVM ships the fake streamer only (CI test double). Production streaming is Android.
 */
actual fun createStreamingTranscriber(
    modelPath: String?,
    modelId: String,
): StreamingTranscriber = FakeStreamingTranscriber()
