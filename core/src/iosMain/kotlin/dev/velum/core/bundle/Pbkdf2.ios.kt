@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)

package dev.velum.core.bundle

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256

actual fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    require(salt.isNotEmpty() && keyBytes > 0) { "salt and key length must be non-empty" }
    val saltU = salt.asUByteArray()
    val out = UByteArray(keyBytes)
    val status = saltU.usePinned { s ->
        out.usePinned { o ->
            CCKeyDerivationPBKDF(
                kCCPBKDF2,
                password, // passed as a UTF-8 C string
                password.encodeToByteArray().size.convert(),
                s.addressOf(0),
                salt.size.convert(),
                kCCPRFHmacAlgSHA256,
                iterations.convert(),
                o.addressOf(0),
                keyBytes.convert(),
            )
        }
    }
    check(status == 0) { "CCKeyDerivationPBKDF failed ($status)" }
    return out.asByteArray()
}
