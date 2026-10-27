package dev.velum.core.integrations

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256

@OptIn(ExperimentalForeignApi::class)
actual object WebhookCrypto {
    actual fun hmacSha256Hex(secret: ByteArray, body: ByteArray): String {
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        secret.usePinned { s ->
            body.usePinned { b ->
                digest.usePinned { d ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        s.addressOf(0),
                        secret.size.convert(),
                        b.addressOf(0),
                        body.size.convert(),
                        d.addressOf(0),
                    )
                }
            }
        }
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
