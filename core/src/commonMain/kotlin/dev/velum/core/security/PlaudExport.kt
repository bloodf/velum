package dev.velum.core.security

import dev.velum.protocol.identity.IdentityBundleFormat
import dev.velum.protocol.identity.IdentityRedaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PLAUD_EXPORT_SUPPORTED_VERSION: Int = IdentityBundleFormat.VERSION
const val PLAUD_EXPORT_REQUIRED_SIGNATURE_BYTES: Int = IdentityBundleFormat.SIGNATURE_BYTES
const val PLAUD_EXPORT_TEMPLATE_ID_PREFIX: String = "plaud-import-"

@Serializable
data class PlaudExportFile(
    val version: Int,
    val source: String,
    val device: PlaudExportDevice,
    val templates: List<PlaudExportTemplate> = emptyList(),
)

@Serializable
data class PlaudExportDevice(
    val serial: String,
    @SerialName("snSignatureBase64")
    val snSignatureBase64: String,
    val bindToken: String,
    val publicKeyPem: String? = null,
    val privateKeyPem: String? = null,
)

@Serializable
data class PlaudExportTemplate(
    val id: String? = null,
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val systemPrompt: String = "",
    val prompt: String? = null,
)

data class ValidatedPlaudDevice(
    val serial: String,
    val snSignatureBase64: String,
    val bindToken: String,
    val publicKeyPem: String?,
    val privateKeyPem: String?,
) {
    override fun toString(): String =
        "ValidatedPlaudDevice(serial=${IdentityRedaction.PLACEHOLDER}, " +
            "snSignatureBase64=${IdentityRedaction.PLACEHOLDER}, " +
            "bindToken=${IdentityRedaction.PLACEHOLDER}, " +
            "publicKeyPem=${IdentityRedaction.PLACEHOLDER}, " +
            "privateKeyPem=${IdentityRedaction.PLACEHOLDER})"
}

data class ValidatedPlaudTemplate(
    val localId: String,
    val name: String,
    val category: String,
    val description: String,
    val systemPrompt: String,
)

sealed class PlaudExportParseResult {
    data class Success(
        val device: ValidatedPlaudDevice,
        val templates: List<ValidatedPlaudTemplate>,
        val source: String,
    ) : PlaudExportParseResult() {
        override fun toString(): String =
            "PlaudExportParseResult.Success(device=$device, templates=${templates.size}, " +
                "source=${IdentityRedaction.PLACEHOLDER})"
    }

    data class Failure(val message: String) : PlaudExportParseResult() {
        override fun toString(): String = "PlaudExportParseResult.Failure(message=$message)"
    }
}
