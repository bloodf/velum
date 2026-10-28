package dev.velum.protocol.identity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Defensive parser for the versioned Note Pro identity bundle
 * (`docs/PLAUD_IMPORT.md`).
 *
 * Rejects empty, truncated, oversized, wrong-version, and garbage input.
 * Error messages never include secret field values.
 */
object IdentityBundleParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }

    fun parse(raw: String): IdentityBundleParseResult {
        if (raw.isEmpty() || raw.isBlank()) {
            return IdentityBundleParseResult.Failure(IdentityBundleParseError.Empty)
        }
        if (raw.length > IdentityBundleFormat.MAX_DOCUMENT_CHARS) {
            return IdentityBundleParseResult.Failure(
                IdentityBundleParseError.Oversized(IdentityBundleFormat.MAX_DOCUMENT_CHARS),
            )
        }

        val wire = try {
            json.decodeFromString(IdentityBundleWire.serializer(), raw)
        } catch (_: SerializationException) {
            return IdentityBundleParseResult.Failure(IdentityBundleParseError.Malformed)
        } catch (_: IllegalArgumentException) {
            return IdentityBundleParseResult.Failure(IdentityBundleParseError.Malformed)
        }

        val version = wire.version
            ?: return failMissing(IdentityBundleFormat.FIELD_VERSION)
        if (version != IdentityBundleFormat.VERSION) {
            return IdentityBundleParseResult.Failure(
                IdentityBundleParseError.UnsupportedVersion(version),
            )
        }

        val source = wire.source.trim()
        if (source.isEmpty()) {
            return failMissing(IdentityBundleFormat.FIELD_SOURCE)
        }
        if (source.length > IdentityBundleFormat.MAX_SOURCE_CHARS) {
            return failInvalid(
                IdentityBundleFormat.FIELD_SOURCE,
                "exceeds maximum length of ${IdentityBundleFormat.MAX_SOURCE_CHARS}",
            )
        }

        val device = wire.device
            ?: return failMissing(IdentityBundleFormat.FIELD_DEVICE)

        val serial = device.serial?.trim().orEmpty()
        if (serial.isEmpty()) {
            return failMissing(IdentityBundleFormat.FIELD_SERIAL)
        }
        if (serial.length > IdentityBundleFormat.MAX_SERIAL_CHARS) {
            return failInvalid(
                IdentityBundleFormat.FIELD_SERIAL,
                "exceeds maximum length of ${IdentityBundleFormat.MAX_SERIAL_CHARS}",
            )
        }

        val sigB64 = device.snSignatureBase64?.trim().orEmpty()
        if (sigB64.isEmpty()) {
            return failMissing(IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64)
        }
        if (sigB64.length > IdentityBundleFormat.MAX_SIGNATURE_BASE64_CHARS) {
            return failInvalid(
                IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64,
                "exceeds maximum length of ${IdentityBundleFormat.MAX_SIGNATURE_BASE64_CHARS}",
            )
        }
        val signature = IdentityBase64.decodeOrNull(sigB64)
            ?: return failInvalid(
                IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64,
                "is not valid base64",
            )
        if (signature.size != IdentityBundleFormat.SIGNATURE_BYTES) {
            return failInvalid(
                IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64,
                "must decode to ${IdentityBundleFormat.SIGNATURE_BYTES} bytes " +
                    "(got ${signature.size})",
            )
        }

        val bind = device.bindToken?.trim().orEmpty()
        if (bind.isEmpty()) {
            return failMissing(IdentityBundleFormat.FIELD_BIND_TOKEN)
        }
        if (bind.length > IdentityBundleFormat.MAX_BIND_TOKEN_CHARS) {
            return failInvalid(
                IdentityBundleFormat.FIELD_BIND_TOKEN,
                "exceeds maximum length of ${IdentityBundleFormat.MAX_BIND_TOKEN_CHARS}",
            )
        }

        val publicPem = when (
            val pem = normalizeOptionalPem(
                device.publicKeyPem,
                IdentityBundleFormat.FIELD_PUBLIC_KEY_PEM,
            )
        ) {
            is PemResult.Ok -> pem.value
            is PemResult.Invalid -> return IdentityBundleParseResult.Failure(pem.error)
        }

        val privatePem = when (
            val pem = normalizeOptionalPem(
                device.privateKeyPem,
                IdentityBundleFormat.FIELD_PRIVATE_KEY_PEM,
            )
        ) {
            is PemResult.Ok -> pem.value
            is PemResult.Invalid -> return IdentityBundleParseResult.Failure(pem.error)
        }

        return IdentityBundleParseResult.Success(
            IdentityBundle(
                version = version,
                source = source,
                serial = serial,
                serialSignature = signature,
                bindToken = bind,
                publicKeyPem = publicPem,
                privateKeyPem = privatePem,
            ),
        )
    }

    private fun failMissing(path: String): IdentityBundleParseResult =
        IdentityBundleParseResult.Failure(IdentityBundleParseError.MissingField(path))

    private fun failInvalid(path: String, detail: String): IdentityBundleParseResult =
        IdentityBundleParseResult.Failure(IdentityBundleParseError.InvalidField(path, detail))

    private fun normalizeOptionalPem(raw: String?, path: String): PemResult {
        if (raw == null) return PemResult.Ok(null)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PemResult.Ok(null)
        if (trimmed.length > IdentityBundleFormat.MAX_PEM_CHARS) {
            return PemResult.Invalid(
                IdentityBundleParseError.InvalidField(
                    path,
                    "exceeds maximum length of ${IdentityBundleFormat.MAX_PEM_CHARS}",
                ),
            )
        }
        return PemResult.Ok(trimmed)
    }

    private sealed class PemResult {
        data class Ok(val value: String?) : PemResult()
        data class Invalid(val error: IdentityBundleParseError) : PemResult()
    }
}

/**
 * Wire DTO for kotlinx.serialization. Kept internal so callers only see
 * [IdentityBundle] / [IdentityBundleParseError]. Optional fields are nullable so
 * missing keys become typed [IdentityBundleParseError.MissingField] instead of
 * opaque decode failures.
 */
@Serializable
internal data class IdentityBundleWire(
    val version: Int? = null,
    val source: String = "",
    val device: IdentityBundleDeviceWire? = null,
)

@Serializable
internal data class IdentityBundleDeviceWire(
    val serial: String? = null,
    @SerialName("snSignatureBase64")
    val snSignatureBase64: String? = null,
    val bindToken: String? = null,
    val publicKeyPem: String? = null,
    val privateKeyPem: String? = null,
)
