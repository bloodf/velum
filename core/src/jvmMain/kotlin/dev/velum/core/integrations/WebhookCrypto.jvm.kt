package dev.velum.core.integrations

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual object WebhookCrypto {
    actual fun hmacSha256Hex(secret: ByteArray, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { b ->
            (b.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
