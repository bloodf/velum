package dev.velum.core.security

import dev.velum.protocol.identity.IdentityBundle
import dev.velum.protocol.identity.IdentityBundleParseResult
import dev.velum.protocol.identity.IdentityBundleParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Import entry point for Plaud JSON v1 / USB identity bundles.
 *
 * Device identity is validated by [IdentityBundleParser] in `:plaud-protocol`.
 * Optional templates are parsed here for local catalog upsert.
 */
object PlaudExportImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawJson: String): PlaudExportParseResult {
        when (val identity = IdentityBundleParser.parse(rawJson)) {
            is IdentityBundleParseResult.Failure ->
                return PlaudExportParseResult.Failure(identity.error.userMessage())
            is IdentityBundleParseResult.Success -> {
                val templates = when (val parsed = parseTemplates(rawJson)) {
                    is TemplateParse.Ok -> parsed.templates
                    is TemplateParse.Fail ->
                        return PlaudExportParseResult.Failure(parsed.message)
                }
                return PlaudExportParseResult.Success(
                    device = identity.bundle.toValidatedDevice(),
                    templates = templates,
                    source = identity.bundle.source,
                )
            }
        }
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

    private fun parseTemplates(rawJson: String): TemplateParse {
        val wire = try {
            json.decodeFromString(PlaudExportTemplatesWire.serializer(), rawJson)
        } catch (_: SerializationException) {
            return TemplateParse.Fail("Identity bundle is malformed or truncated")
        } catch (_: IllegalArgumentException) {
            return TemplateParse.Fail("Identity bundle is malformed or truncated")
        }
        val out = ArrayList<ValidatedPlaudTemplate>(wire.templates.size)
        wire.templates.forEachIndexed { index, t ->
            val name = t.name.trim()
            if (name.isEmpty()) {
                return TemplateParse.Fail("templates[$index].name is required")
            }
            val prompt = t.systemPrompt.trim().ifEmpty { t.prompt.orEmpty().trim() }
            val rawId = t.id?.trim().orEmpty()
            val localId = when {
                rawId.isEmpty() ->
                    "$PLAUD_EXPORT_TEMPLATE_ID_PREFIX${slug(name)}-$index"
                rawId.startsWith(PLAUD_EXPORT_TEMPLATE_ID_PREFIX) -> rawId
                else -> "$PLAUD_EXPORT_TEMPLATE_ID_PREFIX$rawId"
            }
            out += ValidatedPlaudTemplate(
                localId = localId,
                name = name,
                category = t.category?.trim()?.ifBlank { null } ?: "general",
                description = t.description?.trim().orEmpty().ifBlank { name },
                systemPrompt = prompt,
            )
        }
        return TemplateParse.Ok(out)
    }

    private fun slug(name: String): String =
        name.lowercase()
            .map { ch -> if (ch.isLetterOrDigit()) ch else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "template" }
            .take(40)

    private sealed class TemplateParse {
        data class Ok(val templates: List<ValidatedPlaudTemplate>) : TemplateParse()
        data class Fail(val message: String) : TemplateParse()
    }
}

private fun IdentityBundle.toValidatedDevice(): ValidatedPlaudDevice =
    ValidatedPlaudDevice(
        serial = serial,
        snSignatureBase64 = serialSignatureBase64(),
        bindToken = bindToken,
        publicKeyPem = publicKeyPem,
        privateKeyPem = privateKeyPem,
    )

@Serializable
private data class PlaudExportTemplatesWire(
    val templates: List<PlaudExportTemplate> = emptyList(),
)
