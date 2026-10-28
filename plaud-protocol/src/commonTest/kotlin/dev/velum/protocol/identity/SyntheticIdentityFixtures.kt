package dev.velum.protocol.identity

/**
 * TEST-ONLY synthetic Note Pro identity material.
 *
 * Generated in-process — never real device data, never owner exports.
 * Do not treat these strings as production credentials.
 */
object SyntheticIdentityFixtures {
    const val TEST_ONLY_MARKER: String = "TEST-ONLY-SYNTHETIC-IDENTITY"

    /** Deterministic 256-byte FE10 stand-in (not a real sn-sign). */
    fun signatureBytes(): ByteArray =
        ByteArray(IdentityBundleFormat.SIGNATURE_BYTES) { index ->
            ((index * 17) xor 0x5A).toByte()
        }

    fun signatureBase64(): String = IdentityBase64.encode(signatureBytes())

    const val SERIAL: String = "SYNTH-SN-0001"
    const val BIND_TOKEN: String = "synth-bind-token-not-real"
    const val SOURCE_USB: String = "usb_route"
    const val SOURCE_EXPORT: String = "plaud_app_export"

    /**
     * Synthetic PEM-shaped text (not a valid RSA key). Marked so grepping the
     * repo cannot confuse it with a real private key.
     */
    const val PUBLIC_KEY_PEM: String =
        "-----BEGIN PUBLIC KEY-----\n" +
            "TESTONLYSYNTHPUBLICKEYMATERIALNOTAREALRSAKEY\n" +
            "-----END PUBLIC KEY-----"

    const val PRIVATE_KEY_PEM: String =
        "-----BEGIN PRIVATE KEY-----\n" +
            "TESTONLYSYNTHPRIVATEKEYMATERIALNOTAREALRSAKEY\n" +
            "-----END PRIVATE KEY-----"

    fun validBundleJson(
        includePems: Boolean = false,
        source: String = SOURCE_USB,
        templatesJson: String? = null,
    ): String {
        val pemFields = if (includePems) {
            """,
      "publicKeyPem": ${jsonString(PUBLIC_KEY_PEM)},
      "privateKeyPem": ${jsonString(PRIVATE_KEY_PEM)}"""
        } else {
            ""
        }
        val templates = templatesJson?.let { ",\n  \"templates\": $it" }.orEmpty()
        return """
            {
              "_comment": "$TEST_ONLY_MARKER — generated fixture, not device data",
              "version": ${IdentityBundleFormat.VERSION},
              "source": ${jsonString(source)},
              "device": {
                "serial": ${jsonString(SERIAL)},
                "snSignatureBase64": ${jsonString(signatureBase64())},
                "bindToken": ${jsonString(BIND_TOKEN)}$pemFields
              }$templates
            }
        """.trimIndent()
    }

    fun validBundle(includePems: Boolean = false): IdentityBundle =
        IdentityBundle(
            version = IdentityBundleFormat.VERSION,
            source = SOURCE_USB,
            serial = SERIAL,
            serialSignature = signatureBytes(),
            bindToken = BIND_TOKEN,
            publicKeyPem = if (includePems) PUBLIC_KEY_PEM else null,
            privateKeyPem = if (includePems) PRIVATE_KEY_PEM else null,
        )

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
}
