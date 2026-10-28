package dev.velum.protocol.identity

/**
 * Versioned Note Pro identity bundle (USB extract / Plaud JSON v1).
 *
 * Field **names** are public; field **values** are secrets. [toString] never
 * includes secret material. See `docs/PLAUD_IMPORT.md`.
 *
 * TEST-ONLY fixtures must be synthetic — never real device data.
 */
data class IdentityBundle(
    val version: Int,
    val source: String,
    val serial: String,
    /** Raw 256-byte FE10 serial signature (`sn-sign`). */
    val serialSignature: ByteArray,
    val bindToken: String,
    val publicKeyPem: String?,
    val privateKeyPem: String?,
) {
    init {
        require(version == IdentityBundleFormat.VERSION) {
            "Unsupported identity bundle version"
        }
        require(serialSignature.size == IdentityBundleFormat.SIGNATURE_BYTES) {
            "serialSignature must be ${IdentityBundleFormat.SIGNATURE_BYTES} bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityBundle) return false
        return version == other.version &&
            source == other.source &&
            serial == other.serial &&
            serialSignature.contentEquals(other.serialSignature) &&
            bindToken == other.bindToken &&
            publicKeyPem == other.publicKeyPem &&
            privateKeyPem == other.privateKeyPem
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + source.hashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + serialSignature.contentHashCode()
        result = 31 * result + bindToken.hashCode()
        result = 31 * result + (publicKeyPem?.hashCode() ?: 0)
        result = 31 * result + (privateKeyPem?.hashCode() ?: 0)
        return result
    }

    /** Base64 form of [serialSignature] for secure-store / import APIs. */
    fun serialSignatureBase64(): String = IdentityBase64.encode(serialSignature)

    override fun toString(): String =
        "IdentityBundle(version=$version, source=${IdentityRedaction.PLACEHOLDER}, " +
            "serial=${IdentityRedaction.PLACEHOLDER}, " +
            "serialSignature=${IdentityRedaction.PLACEHOLDER}, " +
            "bindToken=${IdentityRedaction.PLACEHOLDER}, " +
            "publicKeyPem=${IdentityRedaction.PLACEHOLDER}, " +
            "privateKeyPem=${IdentityRedaction.PLACEHOLDER})"
}

/**
 * Documented bounds and constants for identity bundle v1.
 * Lengths are characters (UTF-16 code units / Kotlin [String.length]) unless noted.
 */
object IdentityBundleFormat {
    const val VERSION: Int = 1

    /** FE10 / `sn-sign` payload size in bytes. */
    const val SIGNATURE_BYTES: Int = 256

    /** Reject whole documents larger than this (defensive DoS / truncate guard). */
    const val MAX_DOCUMENT_CHARS: Int = 64 * 1024

    const val MAX_SOURCE_CHARS: Int = 64
    const val MAX_SERIAL_CHARS: Int = 64
    const val MAX_BIND_TOKEN_CHARS: Int = 256
    const val MAX_PEM_CHARS: Int = 16 * 1024

    /**
     * Base64 of 256 bytes is 344 chars with padding; allow a small headroom then reject.
     */
    const val MAX_SIGNATURE_BASE64_CHARS: Int = 512

    const val FIELD_VERSION: String = "version"
    const val FIELD_SOURCE: String = "source"
    const val FIELD_DEVICE: String = "device"
    const val FIELD_SERIAL: String = "device.serial"
    const val FIELD_SN_SIGNATURE_BASE64: String = "device.snSignatureBase64"
    const val FIELD_BIND_TOKEN: String = "device.bindToken"
    const val FIELD_PUBLIC_KEY_PEM: String = "device.publicKeyPem"
    const val FIELD_PRIVATE_KEY_PEM: String = "device.privateKeyPem"
}
