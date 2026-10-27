package dev.velum.ai.needle

/**
 * JNI surface for Apache-2.0 Needle (`libvelum_needle.so` wrapping `libneedle.a`).
 * Loads Whistle `.cact` bytes and runs [needle_transcribe].
 */
internal object NeedleNative {
    private val loadError: String? = try {
        System.loadLibrary("velum_needle")
        null
    } catch (t: Throwable) {
        t.message ?: t::class.simpleName
    }

    val available: Boolean get() = loadError == null

    fun requireAvailable() {
        val err = loadError
        if (err != null) {
            throw UnsatisfiedLinkError(
                "Needle JNI (libvelum_needle) failed to load: $err. " +
                    "Whistle ASR needs the packaged native library (arm64-v8a / armeabi-v7a).",
            )
        }
    }

    @JvmStatic external fun nativeLoadCact(cactBytes: ByteArray): Boolean

    @JvmStatic external fun nativeLastError(): String

    @JvmStatic external fun nativeTranscribe(
        pcm: FloatArray,
        language: String?,
        wordTimestamps: Boolean,
    ): String?
}
