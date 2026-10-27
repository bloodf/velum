package dev.velum.ai.needle

import dev.velum.ai.engine.TranscriptionResult

actual object NeedleWhistleEngine {
    actual val isNativeAvailable: Boolean = false

    actual suspend fun transcribe(
        pcm16LeOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        cactPath: String,
        keywords: String?,
    ): TranscriptionResult {
        throw UnsupportedOperationException(
            "Needle/Whistle JNI is Android-only today (libvelum_needle). " +
                "modelPath=$cactPath language=$languageHint sampleRate=$sampleRateHz",
        )
    }
}
