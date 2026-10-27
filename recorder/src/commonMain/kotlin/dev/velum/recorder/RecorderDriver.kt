package dev.velum.recorder

/**
 * Pluggable BLE / SoftAP recorder family (Plaud, Omi, Pocket, …).
 *
 * Protocol codecs behind each driver are pure Kotlin and unit-tested with fixtures.
 * Live GATT I/O stays in `:ble`; drivers describe how to identify and what ops exist.
 */
interface RecorderDriver {
    /** Stable id, e.g. `plaud`, `omi`, `pocket`. */
    val id: String

    val displayName: String

    /** Human-readable license / provenance for this protocol surface. */
    val protocolLicenseNote: String

    /**
     * Whether an advertisement likely belongs to this family.
     * [serviceUuids] are lowercase 128-bit or 16-bit hex strings without dashes when short.
     */
    fun matchesAdvertisement(
        name: String?,
        serviceUuids: List<String> = emptyList(),
        manufacturerId: Int? = null,
        manufacturerPayload: ByteArray? = null,
    ): Boolean

    fun capabilitiesForAdvertisement(
        name: String?,
        manufacturerPayload: ByteArray? = null,
    ): RecorderCapabilities

    /** Pure protocol helpers exposed for tests / session adapters. */
    val protocol: RecorderProtocol
}

/**
 * Capability surface a driver can offer. Unsupported ops throw [UnsupportedRecorderOpException]
 * from session adapters (not from this data class).
 */
data class RecorderCapabilities(
    val driverId: String,
    val productLabel: String,
    val supportsFileListDownload: Boolean,
    val supportsLiveAudioStream: Boolean,
    val supportsWifiFastPath: Boolean,
    val audioCodecs: Set<RecorderAudioCodec>,
    val needsImportedIdentityKeys: Boolean,
    val notes: String = "",
)

enum class RecorderAudioCodec {
    OggOpusInContainer,
    RawOpus,
    Pcm16,
    MuLaw,
    Mp3,
    Adpcm,
    Unknown,
}

/**
 * Protocol-level operations that do not need a live radio (parse / build packets).
 */
interface RecorderProtocol {
    fun describe(): String
}

class UnsupportedRecorderOpException(
    driverId: String,
    op: String,
) : UnsupportedOperationException(
    "Recorder driver '$driverId' does not support '$op' on this firmware/path.",
)

/** Scan hit enriched with the matched driver (if any). */
data class RecorderScanMatch(
    val driver: RecorderDriver,
    val capabilities: RecorderCapabilities,
)
