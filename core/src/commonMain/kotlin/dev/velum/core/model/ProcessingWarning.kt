package dev.velum.core.model

/** Stable codes stored in `recording.processing_warning`. Codes only, never free text or identity data. */
object ProcessingWarning {
    /** The source Ogg ended mid-page/packet; only the decodable part was transcribed. */
    const val TRUNCATED_AUDIO: String = "truncated_audio"
}
