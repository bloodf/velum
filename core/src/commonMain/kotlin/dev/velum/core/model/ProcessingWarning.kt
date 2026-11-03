package dev.velum.core.model

/** Stable codes stored in `recording.processing_warning`. Codes only, never free text or identity data. */
object ProcessingWarning {
    /** The source Ogg ended mid-page/packet; only the decodable part was transcribed. */
    const val TRUNCATED_AUDIO: String = "truncated_audio"

    /**
     * The device's own checksum of a downloaded session did not match. The file is kept and never shown as
     * verified. Wins over [TRUNCATED_AUDIO] when both apply, because a later job run cannot rederive it.
     */
    const val DEVICE_CRC_MISMATCH: String = "device_crc_mismatch"
}
