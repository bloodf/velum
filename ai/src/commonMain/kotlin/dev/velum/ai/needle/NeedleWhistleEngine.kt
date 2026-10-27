package dev.velum.ai.needle

import dev.velum.ai.engine.TranscriptionResult

/**
 * Needle `.cact` speech runtime (Whistle). Platform actual loads JNI on Android;
 * other targets fail loudly until a desktop Needle path exists.
 */
expect object NeedleWhistleEngine {
    val isNativeAvailable: Boolean

    /**
     * Transcribe 16 kHz mono PCM (s16le bytes or float) with a downloaded `whistle.cact`.
     */
    suspend fun transcribe(
        pcm16LeOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        cactPath: String,
        keywords: String? = null,
    ): TranscriptionResult
}
