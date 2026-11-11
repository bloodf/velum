@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)

package dev.velum.core.bundle

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.posix.RTLD_DEFAULT
import platform.posix.dlsym

// The platform binding coerces password to String. Resolve the same native API to pass bytes directly.
private val nativePbkdf2 = checkNotNull(dlsym(RTLD_DEFAULT, "CCKeyDerivationPBKDF"))
    .reinterpret<CFunction<(
        UInt, CPointer<ByteVar>?, ULong, CPointer<UByteVar>?, ULong,
        UInt, UInt, CPointer<UByteVar>?, ULong,
    ) -> Int>>()

actual fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    require(salt.isNotEmpty() && keyBytes > 0) { "salt and key length must be non-empty" }
    require(iterations > 0) { "iterations must be positive" }
    val passwordBytes = password.encodeToByteArray()
    val passwordBuffer = if (passwordBytes.isEmpty()) byteArrayOf(0) else passwordBytes
    val saltU = salt.asUByteArray()
    val out = UByteArray(keyBytes)
    val status = passwordBuffer.usePinned { p ->
        saltU.usePinned { s ->
            out.usePinned { o ->
                nativePbkdf2(
                    kCCPBKDF2,
                    p.addressOf(0),
                    passwordBytes.size.convert(),
                    s.addressOf(0),
                    salt.size.convert(),
                    kCCPRFHmacAlgSHA256,
                    iterations.convert(),
                    o.addressOf(0),
                    keyBytes.convert(),
                )
            }
        }
    }
    check(status == 0) { "CCKeyDerivationPBKDF failed ($status)" }
    return out.asByteArray()
}
