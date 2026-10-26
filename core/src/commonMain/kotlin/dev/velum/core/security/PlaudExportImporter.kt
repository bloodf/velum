package dev.velum.core.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Parses a Plaud JSON export and stores identity via [DeviceKeyImporter].
 * Optional templates are returned for the caller to upsert locally.
 */
@OptIn(ExperimentalEncodingApi::class)
object PlaudExportImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawJson: String): PlaudExportParseResult {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return PlaudExportParseResult.Failure("Empty JSON")
        val wire = try {
            json.decodeFromString(PlaudExportFile.serializer(), trimmed)
        } catch (e: SerializationException) {
            return PlaudExportParseResult.Failure("Invalid JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return PlaudExportParseResult.Failure("Invalid JSON: ${e.message}")
        }
        if (wire.version != PLAUD_EXPORT_SUPPORTED_VERSION) {
            return PlaudExportParseResult.Failure(
                "Unsupported export version ${wire.version} (expected $PLAUD_EXPORT_SUPPORTED_VERSION)",
            )
        }
        if (wire.source.isBlank()) {
            return PlaudExportParseResult.Failure("source is required")
        }
        val serial = wire.device.serial.trim()
        if (serial.isEmpty()) return PlaudExportParseResult.Failure("device.serial is required")
        val sigB64 = wire.device.snSignatureBase64.trim()
        if (sigB64.isEmpty()) {
            return PlaudExportParseResult.Failure("device.snSignatureBase64 is required")
        }
        val bind = wire.device.bindToken.trim()
        if (bind.isEmpty()) return PlaudExportParseResult.Failure("device.bindToken is required")

        val signature = try {
            Base64.decode(sigB64)
        } catch (t: Throwable) {
            return PlaudExportParseResult.Failure(
                "device.snSignatureBase64 is not valid base64: ${t.message}",
            )
        }
        if (signature.size != PLAUD_EXPORT_REQUIRED_SIGNATURE_BYTES) {
            return PlaudExportParseResult.Failure(
                "snSignature must decode to $PLAUD_EXPORT_REQUIRED_SIGNATURE_BYTES bytes " +
                    "(got ${signature.size})",
            )
        }

        val templates = wire.templates.mapIndexed { index, t ->
            val name = t.name.trim()
            if (name.isEmpty()) {
                return PlaudExportParseResult.Failure("templates[$index].name is required")
            }
            val prompt = t.systemPrompt.trim().ifEmpty { t.prompt.orEmpty().trim() }
            val rawId = t.id?.trim().orEmpty()
            val localId = when {
                rawId.isEmpty() ->
                    "$PLAUD_EXPORT_TEMPLATE_ID_PREFIX${slug(name)}-$index"
                rawId.startsWith(PLAUD_EXPORT_TEMPLATE_ID_PREFIX) -> rawId
                else -> "$PLAUD_EXPORT_TEMPLATE_ID_PREFIX$rawId"
            }
            ValidatedPlaudTemplate(
                localId = localId,
                name = name,
                category = t.category?.trim()?.ifBlank { null } ?: "general",
                description = t.description?.trim().orEmpty().ifBlank { name },
                systemPrompt = prompt,
            )
        }

        return PlaudExportParseResult.Success(
            device = ValidatedPlaudDevice(
                serial = serial,
                snSignatureBase64 = sigB64,
                bindToken = bind,
                publicKeyPem = wire.device.publicKeyPem?.trim()?.ifBlank { null },
                privateKeyPem = wire.device.privateKeyPem?.trim()?.ifBlank { null },
            ),
            templates = templates,
            source = wire.source.trim(),
        )
    }

    suspend fun importInto(
        rawJson: String,
        keys: DeviceKeyImporter,
        upsertTemplate: (suspend (ValidatedPlaudTemplate) -> Unit)? = null,
    ): PlaudExportParseResult {
        val parsed = parse(rawJson)
        if (parsed is PlaudExportParseResult.Success) {
            keys.importKeys(
                serialNumber = parsed.device.serial,
                serialSignatureBase64 = parsed.device.snSignatureBase64,
                bindToken = parsed.device.bindToken,
                publicKeyPem = parsed.device.publicKeyPem.orEmpty(),
                privateKeyPem = parsed.device.privateKeyPem.orEmpty(),
            )
            if (upsertTemplate != null) {
                parsed.templates.forEach { upsertTemplate(it) }
            }
        }
        return parsed
    }

    private fun slug(name: String): String =
        name.lowercase()
            .map { ch -> if (ch.isLetterOrDigit()) ch else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "template" }
            .take(40)
}
