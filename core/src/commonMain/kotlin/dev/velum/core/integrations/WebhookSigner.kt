package dev.velum.core.integrations

/**
 * HMAC-SHA256 signing for outbound webhooks (Zapier / n8n / Make).
 * Header: `X-Velum-Signature: sha256=<hex>`.
 */
expect object WebhookCrypto {
    fun hmacSha256Hex(secret: ByteArray, body: ByteArray): String
}

object WebhookSigner {
    const val SIGNATURE_HEADER = "X-Velum-Signature"

    fun signatureHeaderValue(secret: String, bodyUtf8: String): String {
        val hex = WebhookCrypto.hmacSha256Hex(
            secret.encodeToByteArray(),
            bodyUtf8.encodeToByteArray(),
        )
        return "sha256=$hex"
    }

    fun verify(secret: String, bodyUtf8: String, headerValue: String): Boolean {
        val expected = signatureHeaderValue(secret, bodyUtf8)
        return constantTimeEquals(expected, headerValue.trim())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
